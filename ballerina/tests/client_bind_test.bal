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

// Client.init: successful bind + submit_sm round trip, and bind rejection (bad
// credentials). `Client.init` connects AND binds synchronously - unlike `Listener`, there
// is no separate `'start()` to await.
import ballerina/test;

const int CLIENT_BIND_TEST_PORT = 28001;
const int CLIENT_BIND_BAD_SYSID_PORT = 28002;
const int CLIENT_BIND_BAD_PASSWORD_PORT = 28003;

Client? clientBindTestClient = ();
int clientBindTestMockId = -1;

function cleanupClientBindTest() returns error? {
    Client? c = clientBindTestClient;
    clientBindTestClient = ();
    error? closeResult = ();
    if c is Client {
        closeResult = c.close();
    }
    if clientBindTestMockId != -1 {
        mockSmscClose(clientBindTestMockId);
        clientBindTestMockId = -1;
    }
    return closeResult;
}

@test:Config {after: cleanupClientBindTest, groups: ["client", "bind"]}
function testClientBindAndSubmitRoundTrip() returns error? {
    int mockId = check mockSmscOpen(CLIENT_BIND_TEST_PORT);
    clientBindTestMockId = mockId;
    mockSmscExpectCredentials(mockId, "esme1", "pw-ok");

    // `new` returning without error means the connect AND the bind_resp both succeeded -
    // by the time this line completes, `submit`/`submitMulti`/etc. are ready to use.
    Client smppClient = check new ({
        host: "localhost",
        port: CLIENT_BIND_TEST_PORT,
        systemId: "esme1",
        password: "pw-ok",
        bindType: TRANSCEIVER
    });
    clientBindTestClient = smppClient;
    int conn = check mockSmscAwaitNextBind(mockId, 5000);

    SubmitResult r = check smppClient->submit({
        destinationAddress: "264811234567",
        shortMessage: "hello from client"
    });
    int submitId = check mockSmscAwaitNextSubmit(mockId, conn, 5000);
    test:assertEquals(mockSmscSubmitDestAddr(submitId), "264811234567");
    test:assertEquals(mockSmscSubmitShortMessage(submitId), "hello from client");
    test:assertEquals(mockSmscSubmitSourceAddr(submitId), "", "no sourceAddress configured -> absent, unlike a Listener with a default");
    test:assertEquals(r.messageId, mockSmscSubmitMessageId(submitId),
            "the returned id must be the id the mock minted for THIS submit");
    test:assertEquals(mockSmscPendingSubmitCount(mockId, conn), 0, "and no more submits");
}

@test:Config {after: cleanupClientBindTest, groups: ["client", "bind"]}
function testClientBindFailsForInvalidSystemId() returns error? {
    int mockId = check mockSmscOpen(CLIENT_BIND_BAD_SYSID_PORT);
    clientBindTestMockId = mockId;
    mockSmscExpectCredentials(mockId, "expected-sys", "pw-ok");

    Client|error result = new ({
        host: "localhost",
        port: CLIENT_BIND_BAD_SYSID_PORT,
        systemId: "wrong-sys",
        password: "pw-ok",
        bindType: TRANSCEIVER
    });
    test:assertTrue(result is error, "bind with a wrong systemId must fail Client.init");
    if result is error {
        test:assertTrue(result is Error, "the init error must be the distinct smpp:Error type");
        test:assertTrue(result.message().includes("Invalid System ID"),
                string `expected an Invalid System ID rejection, got: ${result.message()}`);
    }

    // The mock observed the same rejection from its own side - proves the validator
    // actually fired rather than the connector failing for an unrelated reason.
    int|error bindOutcome = mockSmscAwaitNextBind(mockId, 5000);
    test:assertTrue(bindOutcome is error, "the mock must report the rejected bind as an error");
}

@test:Config {after: cleanupClientBindTest, groups: ["client", "bind"]}
function testClientBindFailsForInvalidPassword() returns error? {
    int mockId = check mockSmscOpen(CLIENT_BIND_BAD_PASSWORD_PORT);
    clientBindTestMockId = mockId;
    mockSmscExpectCredentials(mockId, "expected-sys", "pw-ok");

    Client|error result = new ({
        host: "localhost",
        port: CLIENT_BIND_BAD_PASSWORD_PORT,
        systemId: "expected-sys",
        password: "wrong-pw",
        bindType: TRANSCEIVER
    });
    test:assertTrue(result is error, "bind with a wrong password must fail Client.init");
    if result is error {
        test:assertTrue(result is Error, "the init error must be the distinct smpp:Error type");
        test:assertTrue(result.message().includes("Invalid Password"),
                string `expected an Invalid Password rejection, got: ${result.message()}`);
    }
    int|error bindOutcome = mockSmscAwaitNextBind(mockId, 5000);
    test:assertTrue(bindOutcome is error, "the mock must report the rejected bind as an error");
}
