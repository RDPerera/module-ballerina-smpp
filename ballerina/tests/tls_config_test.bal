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

// The remaining SecureSocket knobs and failure shapes not covered by tls_test.bal: an
// explicit cipher-suite list, a truststore that cannot be opened, and a PEM with no
// certificate in it.
import ballerina/test;

const int TLS_CIPHERS_PORT = 28311;
const int TLS_BAD_TRUSTSTORE_PASSWORD_PORT = 28312;
const int TLS_EMPTY_PEM_PORT = 28313;

const string EMPTY_CERT_PEM = CERTS + "/empty.pem";

Client? tlsConfigTestClient = ();
int tlsConfigTestMockId = -1;

function cleanupTlsConfigTest() returns error? {
    error? closeResult = ();
    Client? c = tlsConfigTestClient;
    if c is Client {
        closeResult = c.close();
        tlsConfigTestClient = ();
    }
    if tlsConfigTestMockId != -1 {
        mockSmscClose(tlsConfigTestMockId);
        tlsConfigTestMockId = -1;
    }
    return closeResult;
}

@test:Config {groups: ["tls"], after: cleanupTlsConfigTest}
function testClientTlsWithAnExplicitCipherSuiteList() returns error? {
    int mockId = check mockSmscOpenTls(TLS_CIPHERS_PORT, SERVER_KEYSTORE, CERT_PASS);
    tlsConfigTestMockId = mockId;

    Client smppClient = check new ("localhost", "test", "test", port = TLS_CIPHERS_PORT,
            secureSocket = {
                cert: {path: CLIENT_TRUSTSTORE, password: CERT_PASS},
                protocolVersions: ["TLSv1.3"],
                ciphers: ["TLS_AES_256_GCM_SHA384", "TLS_AES_128_GCM_SHA256"]
            });
    tlsConfigTestClient = smppClient;
    int conn = check mockSmscAwaitNextBind(mockId, 5000);
    _ = check smppClient->submit({destinationAddress: "264811234567", shortMessage: "ciphers"});
    _ = check mockSmscAwaitNextSubmit(mockId, conn, 5000);
}

@test:Config {groups: ["tls"], after: cleanupTlsConfigTest}
function testClientTlsFailsWhenTheTruststoreCannotBeOpened() returns error? {
    int mockId = check mockSmscOpenTls(TLS_BAD_TRUSTSTORE_PASSWORD_PORT, SERVER_KEYSTORE, CERT_PASS);
    tlsConfigTestMockId = mockId;

    Client|error result = new ("localhost", "test", "test", port = TLS_BAD_TRUSTSTORE_PASSWORD_PORT,
            secureSocket = {cert: {path: CLIENT_TRUSTSTORE, password: "not-the-password"}});
    test:assertTrue(result is error, "a truststore that cannot be opened must fail init");
    test:assertFalse((<error>result).message().includes("not-the-password"),
            "the failure must not echo the password: " + (<error>result).message());
}

@test:Config {groups: ["tls"], after: cleanupTlsConfigTest}
function testClientTlsFailsWhenThePemHoldsNoCertificate() returns error? {
    int mockId = check mockSmscOpenTls(TLS_EMPTY_PEM_PORT, SERVER_KEYSTORE, CERT_PASS);
    tlsConfigTestMockId = mockId;

    Client|error result = new ("localhost", "test", "test", port = TLS_EMPTY_PEM_PORT,
            secureSocket = {cert: EMPTY_CERT_PEM});
    test:assertTrue(result is error, "a PEM with no X.509 certificate in it must fail init");
    test:assertTrue((<error>result).message().includes("no X.509 certificates"), (<error>result).message());
}
