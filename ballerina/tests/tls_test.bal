// Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org).
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

// TLS transport: handshake + bind + submit round-trip (Client) / deliver round-trip
// (Listener), the dev-only no-verify path, hostname verification, mTLS, and the negative
// tests that prove verification actually happens. `secureSocket`/`resolveTls`/
// `validateSecureSocket` are shared code between `Client` and `Listener` (types.bal), so
// most of the depth is pinned once against `Client` and cross-checked once against
// `Listener`.
import ballerina/test;

const int TLS_CLIENT_HAPPY_PORT = 28200;
const int TLS_CLIENT_PEM_PORT = 28201;
const int TLS_CLIENT_NOVERIFY_PORT = 28202;
const int TLS_CLIENT_UNTRUSTED_PORT = 28203;
const int TLS_CLIENT_UNTRUSTED_PEM_PORT = 28204;
const int TLS_CLIENT_HOSTNAME_MISMATCH_PORT = 28205;
const int TLS_CLIENT_HOSTNAME_RELAXED_PORT = 28206;
const int TLS_CLIENT_MTLS_PORT = 28207;
const int TLS_CLIENT_MTLS_NEGATIVE_PORT = 28208;
const int TLS_LISTENER_HAPPY_PORT = 28209;

const string CERTS = "tests/resources/certs";
const string SERVER_KEYSTORE = CERTS + "/server-keystore.p12";
const string CLIENT_TRUSTSTORE = CERTS + "/client-truststore.p12";
const string SERVER_CERT_PEM = CERTS + "/server.crt";
const string WRONG_TRUSTSTORE = CERTS + "/wrong-truststore.p12";
const string WRONG_CERT_PEM = CERTS + "/wrong.crt";
const string CLIENT_KEYSTORE = CERTS + "/client-keystore.p12";
const string SERVER_TRUSTSTORE = CERTS + "/server-truststore.p12";
const string WRONGHOST_KEYSTORE = CERTS + "/wronghost-keystore.p12";
const string WRONGHOST_TRUSTSTORE = CERTS + "/wronghost-truststore.p12";
const string CERT_PASS = "password";

Client? tlsTestClient = ();
Listener? tlsTestListener = ();
int tlsTestMockId = -1;

function cleanupTlsTest() returns error? {
    error? closeResult = ();
    Client? c = tlsTestClient;
    if c is Client {
        closeResult = c.close();
        tlsTestClient = ();
    }
    Listener? l = tlsTestListener;
    if l is Listener {
        error? stopResult = l.gracefulStop();
        closeResult = closeResult is error ? closeResult : stopResult;
        tlsTestListener = ();
    }
    if tlsTestMockId != -1 {
        mockSmscClose(tlsTestMockId);
        tlsTestMockId = -1;
    }
    return closeResult;
}

// (a) HAPPY PATH: full connect+bind+submit round-trip over TLS, the Client verifying the
//     mock's cert against a committed truststore. Proves the whole encrypted path works.
@test:Config {groups: ["tls"], after: cleanupTlsTest}
function testClientTlsBindAndSubmitRoundTrip() returns error? {
    int mockId = check mockSmscOpenTls(TLS_CLIENT_HAPPY_PORT, SERVER_KEYSTORE, CERT_PASS);
    tlsTestMockId = mockId;

    Client smppClient = check new ({
        host: "localhost",
        port: TLS_CLIENT_HAPPY_PORT,
        systemId: "test",
        password: "test",
        bindType: TRANSCEIVER,
        secureSocket: {
            cert: {path: CLIENT_TRUSTSTORE, password: CERT_PASS}
        }
    });
    tlsTestClient = smppClient;
    int conn = check mockSmscAwaitNextBind(mockId, 5000);

    SubmitResult r = check smppClient->submit({destinationAddress: "264811234567", shortMessage: "tls round-trip"});
    int submitId = check mockSmscAwaitNextSubmit(mockId, conn, 5000);
    test:assertEquals(mockSmscSubmitShortMessage(submitId), "tls round-trip");
    test:assertEquals(r.messageId, mockSmscSubmitMessageId(submitId));
}

// (a2) HAPPY PATH via a PEM CA-cert path instead of a truststore record - proves the
//      `cert: string` form of the config resolves and verifies identically.
@test:Config {groups: ["tls"], after: cleanupTlsTest}
function testClientTlsWithPemCertRoundTrip() returns error? {
    int mockId = check mockSmscOpenTls(TLS_CLIENT_PEM_PORT, SERVER_KEYSTORE, CERT_PASS);
    tlsTestMockId = mockId;

    Client smppClient = check new ({
        host: "localhost",
        port: TLS_CLIENT_PEM_PORT,
        systemId: "test",
        password: "test",
        bindType: TRANSCEIVER,
        secureSocket: {
            cert: SERVER_CERT_PEM // PEM path, not a truststore record
        }
    });
    tlsTestClient = smppClient;
    int conn = check mockSmscAwaitNextBind(mockId, 5000);
    SubmitResult r = check smppClient->submit({destinationAddress: "264811234567", shortMessage: "pem tls"});
    test:assertTrue(r.messageId.length() > 0);
    _ = check mockSmscAwaitNextSubmit(mockId, conn, 5000);
}

// (b) DEV-ONLY NO-VERIFY PATH: the Client trusts the self-signed cert WITHOUT verification.
@test:Config {groups: ["tls"], after: cleanupTlsTest}
function testClientTlsVerificationDisabledConnects() returns error? {
    int mockId = check mockSmscOpenTls(TLS_CLIENT_NOVERIFY_PORT, SERVER_KEYSTORE, CERT_PASS);
    tlsTestMockId = mockId;

    Client smppClient = check new ({
        host: "localhost",
        port: TLS_CLIENT_NOVERIFY_PORT,
        systemId: "test",
        password: "test",
        bindType: TRANSCEIVER,
        secureSocket: {disableSslVerification: true} // InsecureSocket: encrypt but don't verify
    });
    tlsTestClient = smppClient; // succeeded despite no trust anchor
    int conn = check mockSmscAwaitNextBind(mockId, 5000);
    SubmitResult r = check smppClient->submit({destinationAddress: "264811234567", shortMessage: "insecure tls"});
    test:assertTrue(r.messageId.length() > 0);
    _ = check mockSmscAwaitNextSubmit(mockId, conn, 5000);
}

// (c) NEGATIVE: the Client verifies against a truststore that does NOT contain the mock's
//     cert. `new Client(...)` (which connects AND binds synchronously) MUST fail. This is
//     the test that makes the security claim real: if it ever passes, "verification" is a
//     lie.
@test:Config {groups: ["tls"], after: cleanupTlsTest}
function testClientTlsUntrustedServerCertFailsHandshake() returns error? {
    int mockId = check mockSmscOpenTls(TLS_CLIENT_UNTRUSTED_PORT, SERVER_KEYSTORE, CERT_PASS);
    tlsTestMockId = mockId;

    Client|error result = new ({
        host: "localhost",
        port: TLS_CLIENT_UNTRUSTED_PORT,
        systemId: "test",
        password: "test",
        bindType: TRANSCEIVER,
        secureSocket: {
            cert: {path: WRONG_TRUSTSTORE, password: CERT_PASS} // does NOT trust the mock
        }
    });
    test:assertTrue(result is error, "Client init MUST fail a TLS handshake against an untrusted server certificate");
    if result is error {
        test:assertTrue(result.message().includes("failed to connect/bind to SMSC"),
                string `unexpected error surface: ${result.message()}`);
        // Discriminating check: the failure must be a TLS trust rejection, not an
        // incidental local keystore-load error (a false green - the test would pass
        // without ever proving verification happened).
        test:assertFalse(result.message().includes("unable to load the key store"),
                string `negative test failed for the wrong reason: ${result.message()}`);
    }
    // Belt-and-suspenders: nothing ever bound on the mock side.
    int|error boundConn = mockSmscAwaitNextBind(mockId, 1500);
    test:assertTrue(boundConn is error, "no bind may complete on an aborted TLS handshake");
}

// (c2) NEGATIVE via the PEM `cert: string` form - proves the PEM trust path actually
//      REJECTS an untrusted cert (the happy-path PEM test only proves it accepts a trusted
//      one).
@test:Config {groups: ["tls"], after: cleanupTlsTest}
function testClientTlsUntrustedServerCertViaPemFailsHandshake() returns error? {
    int mockId = check mockSmscOpenTls(TLS_CLIENT_UNTRUSTED_PEM_PORT, SERVER_KEYSTORE, CERT_PASS);
    tlsTestMockId = mockId;

    Client|error result = new ({
        host: "localhost",
        port: TLS_CLIENT_UNTRUSTED_PEM_PORT,
        systemId: "test",
        password: "test",
        bindType: TRANSCEIVER,
        secureSocket: {
            cert: WRONG_CERT_PEM // PEM of an unrelated cert; does NOT match the mock
        }
    });
    test:assertTrue(result is error, "Client init MUST fail against an untrusted server cert (PEM trust form)");
    if result is error {
        test:assertTrue(result.message().includes("failed to connect/bind to SMSC"),
                string `unexpected error surface: ${result.message()}`);
        test:assertFalse(result.message().includes("unable to load the key store"),
                string `PEM negative failed for the wrong reason: ${result.message()}`);
    }
}

// (d) HOSTNAME VERIFICATION, default ON: the mock presents a cert the Client TRUSTS (chain
//     verifies) whose CN/SAN is `not-localhost`, but the Client dials `localhost` - so the
//     ONLY thing that can fail is the hostname check.
@test:Config {groups: ["tls"], after: cleanupTlsTest}
function testClientTlsHostnameMismatchFailsWithVerifyOn() returns error? {
    int mockId = check mockSmscOpenTls(TLS_CLIENT_HOSTNAME_MISMATCH_PORT, WRONGHOST_KEYSTORE, CERT_PASS);
    tlsTestMockId = mockId;

    Client|error result = new ({
        host: "localhost", // dialed name...
        port: TLS_CLIENT_HOSTNAME_MISMATCH_PORT,
        systemId: "test",
        password: "test",
        bindType: TRANSCEIVER,
        secureSocket: {
            cert: {path: WRONGHOST_TRUSTSTORE, password: CERT_PASS} // ...trusts the CN=not-localhost cert
            // verifyHostName defaults to true
        }
    });
    test:assertTrue(result is error,
            "must fail: cert is trusted but its identity (not-localhost) does not match the dialed host (localhost)");
    if result is error {
        test:assertTrue(result.message().includes("failed to connect/bind to SMSC"),
                string `unexpected error surface: ${result.message()}`);
        test:assertFalse(result.message().includes("unable to load the key store"),
                string `hostname test failed for the wrong reason: ${result.message()}`);
    }
}

// (d2) HOSTNAME VERIFICATION disabled: the same trusted-but-wrong-host cert now connects,
//      because verifyHostName:false relaxes ONLY the hostname check - the chain is STILL
//      verified.
@test:Config {groups: ["tls"], after: cleanupTlsTest}
function testClientTlsHostnameMismatchAllowedWithVerifyOff() returns error? {
    int mockId = check mockSmscOpenTls(TLS_CLIENT_HOSTNAME_RELAXED_PORT, WRONGHOST_KEYSTORE, CERT_PASS);
    tlsTestMockId = mockId;

    Client smppClient = check new ({
        host: "localhost",
        port: TLS_CLIENT_HOSTNAME_RELAXED_PORT,
        systemId: "test",
        password: "test",
        bindType: TRANSCEIVER,
        secureSocket: {
            cert: {path: WRONGHOST_TRUSTSTORE, password: CERT_PASS},
            verifyHostName: false
        }
    });
    tlsTestClient = smppClient; // succeeds: chain verified, hostname check relaxed
    int conn = check mockSmscAwaitNextBind(mockId, 5000);
    SubmitResult r = check smppClient->submit({destinationAddress: "264811234567", shortMessage: "hostname relaxed"});
    test:assertTrue(r.messageId.length() > 0);
    _ = check mockSmscAwaitNextSubmit(mockId, conn, 5000);
}

// (e) mTLS round-trip: the Client presents a client cert the mock's truststore accepts,
//     and verifies the server cert in the same handshake.
@test:Config {groups: ["tls"], after: cleanupTlsTest}
function testClientMutualTlsRoundTrip() returns error? {
    int mockId = check mockSmscOpenMutualTls(TLS_CLIENT_MTLS_PORT, SERVER_KEYSTORE, CERT_PASS,
            SERVER_TRUSTSTORE, CERT_PASS);
    tlsTestMockId = mockId;

    Client smppClient = check new ({
        host: "localhost",
        port: TLS_CLIENT_MTLS_PORT,
        systemId: "test",
        password: "test",
        bindType: TRANSCEIVER,
        secureSocket: {
            cert: {path: CLIENT_TRUSTSTORE, password: CERT_PASS}, // verify the server
            key: {path: CLIENT_KEYSTORE, password: CERT_PASS} // present client identity
        }
    });
    tlsTestClient = smppClient;
    int conn = check mockSmscAwaitNextBind(mockId, 5000);
    SubmitResult r = check smppClient->submit({destinationAddress: "264811234567", shortMessage: "mtls round-trip"});
    test:assertTrue(r.messageId.length() > 0);
    _ = check mockSmscAwaitNextSubmit(mockId, conn, 5000);
}

// (e2) NEGATIVE mTLS: the mock REQUIRES a client cert, but the Client presents none. This
//      is what proves the server actually demands mutual auth - the happy-path test alone
//      proves nothing about enforcement.
@test:Config {groups: ["tls"], after: cleanupTlsTest}
function testClientMutualTlsRequiresClientCert() returns error? {
    int mockId = check mockSmscOpenMutualTls(TLS_CLIENT_MTLS_NEGATIVE_PORT, SERVER_KEYSTORE, CERT_PASS,
            SERVER_TRUSTSTORE, CERT_PASS);
    tlsTestMockId = mockId;

    Client|error result = new ({
        host: "localhost",
        port: TLS_CLIENT_MTLS_NEGATIVE_PORT,
        systemId: "test",
        password: "test",
        bindType: TRANSCEIVER,
        secureSocket: {
            cert: {path: CLIENT_TRUSTSTORE, password: CERT_PASS} // verifies the server, but NO client key
        }
    });
    test:assertTrue(result is error,
            "must fail: the mock requires a client cert and the Client presented none");
    if result is error {
        test:assertTrue(result.message().includes("failed to connect/bind to SMSC"),
                string `unexpected error surface: ${result.message()}`);
    }
}

// Cross-check: the same `secureSocket` machinery also works for a `Listener` (shared
// `resolveTls`/`validateSecureSocket` code in types.bal) - one round trip is enough since
// the depth above already covers the shared machinery exhaustively via `Client`.
@test:Config {groups: ["tls"], after: cleanupTlsTest}
function testListenerTlsBindAndDeliverRoundTrip() returns error? {
    clearRecorded();
    int mockId = check mockSmscOpenTls(TLS_LISTENER_HAPPY_PORT, SERVER_KEYSTORE, CERT_PASS);
    tlsTestMockId = mockId;

    Listener smsListener = check new ({
        host: "localhost",
        port: TLS_LISTENER_HAPPY_PORT,
        systemId: "test",
        password: "test",
        bindType: TRANSCEIVER,
        secureSocket: {
            cert: {path: CLIENT_TRUSTSTORE, password: CERT_PASS}
        }
    });
    tlsTestListener = smsListener;
    check smsListener.attach(new RecordingService());
    check smsListener.'start();
    int connectionId = check mockSmscAwaitNextBind(mockId, 5000);

    check mockSmscSendDeliverSm(mockId, connectionId, "tls round-trip", "", 0);
    test:assertEquals(recordedCount(), 1, "the deliver_sm should have arrived over TLS");
    test:assertEquals(recordedAt(0).shortMessage, "tls round-trip");
}
