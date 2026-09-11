// Copyright (c) 2026 WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
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

// Client.close(): idempotence, behavior after close, and the documented in-flight-submit
// caveat (no drain - a submit already parked awaiting its response completes only at
// transactionTimeout, with LINK_DOWN/possiblySubmitted:true). Also covers the RECEIVER-bind
// fail-fast pre-check shared by every submit-family operation.
import ballerina/test;

const int CLIENT_CLOSE_INFLIGHT_PORT = 28030;
const int CLIENT_CLOSE_IDEMPOTENT_PORT = 28031;
const int CLIENT_CLOSE_THEN_SUBMIT_PORT = 28032;
const int CLIENT_RECEIVER_BIND_PORT = 28033;

Client? clientCloseTestClient = ();
int clientCloseTestMockId = -1;

function cleanupClientCloseTest() returns error? {
    Client? c = clientCloseTestClient;
    clientCloseTestClient = ();
    if c is Client {
        // Already closed by the test body in every case here; close() is idempotent, so
        // calling it again in cleanup is always safe and never masks the test's own result -
        // any close-of-an-already-closed-Client error is deliberately swallowed here (not
        // propagated), so it can never mask the test's own pass/fail outcome.
        Error? swallowed = c.close();
        if swallowed is Error {
            // Nothing to do - see the comment above.
        }
    }
    if clientCloseTestMockId != -1 {
        mockSmscClose(clientCloseTestMockId);
        clientCloseTestMockId = -1;
    }
}

isolated function submitBlocker(Client c) returns SubmitResult|Error {
    return c->submit({destinationAddress: "264811234567", shortMessage: "in-flight"});
}

@test:Config {after: cleanupClientCloseTest, groups: ["client", "close"]}
function testClientCloseDuringInFlightSubmitYieldsLinkDown() returns error? {
    int mockId = check mockSmscOpen(CLIENT_CLOSE_INFLIGHT_PORT);
    clientCloseTestMockId = mockId;
    Client smppClient = check new ({
        host: "localhost",
        port: CLIENT_CLOSE_INFLIGHT_PORT,
        systemId: "test",
        password: "test",
        bindType: TRANSCEIVER,
        transactionTimeout: 2 // keep the test fast: the parked submit gives up at this bound
    });
    clientCloseTestClient = smppClient;
    int conn = check mockSmscAwaitNextBind(mockId, 5000);

    // Delay the submit_sm_resp well beyond transactionTimeout, so the submit is still
    // parked awaiting its response when close() runs.
    mockSmscSetSubmitDelay(mockId, 10000);
    future<SubmitResult|Error> blocked = start submitBlocker(smppClient);
    // Wait for the PDU to actually reach the mock (proves the write completed and the
    // submit is now parked on the response wait, not merely queued locally) before closing.
    int submitId = check mockSmscAwaitNextSubmit(mockId, conn, 5000);
    test:assertEquals(mockSmscSubmitShortMessage(submitId), "in-flight");

    check smppClient.close();

    SubmitResult|Error result = wait blocked;
    test:assertTrue(result is Error,
            "an in-flight submit across close() must fail - it is documented as never woken by the close");
    Error e = <Error>result;
    test:assertEquals(e.detail().failureMode, LINK_DOWN,
            string `close()-during-inflight must map to LINK_DOWN, got: ${e.message()}`);
    test:assertEquals(e.detail().possiblySubmitted, true,
            "the SMSC may have already accepted it before the close - a retry may duplicate it");
    mockSmscSetSubmitDelay(mockId, 0);
}

@test:Config {after: cleanupClientCloseTest, groups: ["client", "close"]}
function testClientCloseIsIdempotentAndDisallowsFurtherUse() returns error? {
    int mockId = check mockSmscOpen(CLIENT_CLOSE_IDEMPOTENT_PORT);
    clientCloseTestMockId = mockId;
    Client smppClient = check new ({
        host: "localhost",
        port: CLIENT_CLOSE_IDEMPOTENT_PORT,
        systemId: "test",
        password: "test",
        bindType: TRANSCEIVER
    });
    clientCloseTestClient = smppClient;
    int _ = check mockSmscAwaitNextBind(mockId, 5000);

    Error? first = smppClient.close();
    test:assertTrue(first is (), "the first close() must succeed");
    Error? second = smppClient.close();
    test:assertTrue(second is (), "closing an already-closed Client must be a no-op, not an error");

    SubmitResult|Error afterClose = smppClient->submit({destinationAddress: "264811234567", shortMessage: "x"});
    test:assertTrue(afterClose is Error, "every remote method must fail once closed");
    Error e = <Error>afterClose;
    test:assertTrue(e.message().includes("closed"),
            string `must name the lifecycle state: ${e.message()}`);
    test:assertEquals(e.detail().failureMode, INVALID_REQUEST);
    test:assertEquals(e.detail().possiblySubmitted, false);
}

@test:Config {after: cleanupClientCloseTest, groups: ["client", "close"]}
function testClientCancelAndReplaceAlsoFailAfterClose() returns error? {
    // Every submit-family method shares the same precheck - pin two more, not just submit.
    int mockId = check mockSmscOpen(CLIENT_CLOSE_THEN_SUBMIT_PORT);
    clientCloseTestMockId = mockId;
    Client smppClient = check new ({
        host: "localhost",
        port: CLIENT_CLOSE_THEN_SUBMIT_PORT,
        systemId: "test",
        password: "test",
        bindType: TRANSCEIVER
    });
    clientCloseTestClient = smppClient;
    int _ = check mockSmscAwaitNextBind(mockId, 5000);
    check smppClient.close();

    Error? cancelResult = smppClient->cancel("1000", "264811234567", "264899999999");
    test:assertTrue(cancelResult is Error);
    test:assertEquals((<Error>cancelResult).detail().failureMode, INVALID_REQUEST);

    Error? replaceResult = smppClient->replace("1000", "264811234567",
            {destinationAddress: "unused", shortMessage: "x"});
    test:assertTrue(replaceResult is Error);
    test:assertEquals((<Error>replaceResult).detail().failureMode, INVALID_REQUEST);
}

@test:Config {after: cleanupClientCloseTest, groups: ["client", "close"]}
function testClientSubmitOnReceiverBindFailsFastWithoutReachingTheWire() returns error? {
    int mockId = check mockSmscOpen(CLIENT_RECEIVER_BIND_PORT);
    clientCloseTestMockId = mockId;
    Client smppClient = check new ({
        host: "localhost",
        port: CLIENT_RECEIVER_BIND_PORT,
        systemId: "test",
        password: "test",
        bindType: RECEIVER
    });
    clientCloseTestClient = smppClient;
    int conn = check mockSmscAwaitNextBind(mockId, 5000);

    SubmitResult|Error r = smppClient->submit({destinationAddress: "264811234567", shortMessage: "x"});
    test:assertTrue(r is Error, "submit on a RECEIVER-bound Client must be rejected");
    Error e = <Error>r;
    test:assertTrue(e.message().includes("RECEIVER-bound") && e.message().includes("TRANSMITTER or TRANSCEIVER"),
            string `must name the fix: ${e.message()}`);
    test:assertEquals(e.detail().failureMode, INVALID_REQUEST);

    // Nothing may have reached the wire - a RECEIVER-bound Client's remote methods fail
    // fast, locally, before ever touching jsmpp.
    int|error observed = mockSmscAwaitNextSubmit(mockId, conn, 500);
    test:assertTrue(observed is error, "the RECEIVER pre-check must run before anything is sent");
}
