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

// Init-time validation that needs no SMSC at all: the shared `validateSecureSocket`
// constraints (types.bal), the bind-credential length/charset limits, and a plain
// connection failure. Every case must fail before anything is dialled.
import ballerina/test;

// Nothing listens here: a connect attempt must be refused, not hang.
const int UNREACHABLE_PORT = 28399;

@test:Config {groups: ["tls", "validation"]}
function testSecureSocketRejectsEmptyPemPath() {
    Client|error c = new ("localhost", "test", "test", secureSocket = {cert: "   "});
    test:assertTrue(c is Error, "an empty PEM path must be rejected at init");
    test:assertTrue((<error>c).message().includes("secureSocket.cert path must not be empty"),
            (<error>c).message());
}

@test:Config {groups: ["tls", "validation"]}
function testSecureSocketRejectsEmptyTruststorePathOnListener() {
    Listener|error l = new ("localhost", "test", "test",
            secureSocket = {cert: {path: " ", password: fixtureKeystorePass}});
    test:assertTrue(l is error, "an empty truststore path must be rejected at init");
    test:assertTrue((<error>l).message().includes("secureSocket.cert.path"), (<error>l).message());
}

@test:Config {groups: ["tls", "validation"]}
function testSecureSocketRejectsNoProtocolVersions() {
    Client|error c = new ("localhost", "test", "test",
            secureSocket = {cert: SERVER_CERT_PEM, protocolVersions: []});
    test:assertTrue(c is Error, "an empty protocolVersions list must be rejected at init");
    test:assertTrue((<error>c).message().includes("at least one TLS version"), (<error>c).message());
}

@test:Config {groups: ["tls", "validation"]}
function testSecureSocketRejectsTlsBelowTheFloorOnListener() {
    Listener|error l = new ("localhost", "test", "test",
            secureSocket = {cert: SERVER_CERT_PEM, protocolVersions: ["TLSv1.3", "TLSv1"]});
    test:assertTrue(l is error, "TLSv1 is below this connector's TLS 1.2 floor");
    test:assertTrue((<error>l).message().includes("'TLSv1' is not allowed"), (<error>l).message());
}

@test:Config {groups: ["client", "validation"]}
function testClientInitRejectsOversizedOrNonAsciiCredentials() {
    // SMPP v3.4 limits: system_id 16, password 9, system_type 13 characters; ASCII only.
    Client|error longSystemId = new ("localhost", "abcdefghijklmnopq", "test", port = UNREACHABLE_PORT);
    test:assertTrue(longSystemId is Error, "a 17-character systemId must be rejected before connecting");

    Client|error tenCharBind = new ("localhost", "test", "0123456789", port = UNREACHABLE_PORT);
    test:assertTrue(tenCharBind is Error, "a 10-character bind credential must be rejected before connecting");

    Client|error longSystemType = new ("localhost", "test", "test", port = UNREACHABLE_PORT,
            systemType = "abcdefghijklmn");
    test:assertTrue(longSystemType is Error, "a 14-character systemType must be rejected before connecting");

    Client|error nonAscii = new ("localhost", "tést", "test", port = UNREACHABLE_PORT);
    test:assertTrue(nonAscii is Error, "a non-ASCII systemId must be rejected before connecting");
}

@test:Config {groups: ["listener", "validation"]}
function testListenerRejectsOversizedOrNonAsciiCredentials() returns error? {
    // The Listener validates the bind credentials no later than start(); either way it
    // must never reach the (unreachable) SMSC with them.
    foreach [string, string, string] [systemId, password, systemType] in [
        ["abcdefghijklmnopq", "test", ""],
        ["test", "0123456789", ""],
        ["test", "test", "abcdefghijklmn"],
        ["tést", "test", ""]
    ] {
        Listener|error l = new ("localhost", systemId, password, port = UNREACHABLE_PORT,
                systemType = systemType, bindTimeout = 1);
        if l is Listener {
            error? started = l.'start();
            test:assertTrue(started is error,
                    string `credentials (${systemId}, ${password}, ${systemType}) must be rejected`);
            check l.immediateStop();
        }
    }
}

@test:Config {groups: ["client", "validation"]}
function testClientInitFailsWhenNothingListens() {
    Client|error c = new ("127.0.0.1", "test", "test", port = UNREACHABLE_PORT, bindTimeout = 2);
    test:assertTrue(c is Error, "connecting to a port nothing listens on must fail");
    test:assertTrue((<error>c).message().includes("SMSC"), (<error>c).message());
}
