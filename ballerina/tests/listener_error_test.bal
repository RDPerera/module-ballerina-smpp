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

// onError firing on a simulated drop (both an abrupt severance and a clean peer-initiated
// unbind), and the RECEIVER-bind fail-fast pre-check on Caller.submit.
import ballerina/test;
import ballerina/time;

const int LISTENER_SEVER_PORT = 28110;
const int LISTENER_PEER_UNBIND_PORT = 28111;
const int LISTENER_RECEIVER_CALLER_PORT = 28112;

Listener? listenerErrorTestListener = ();
int listenerErrorTestMockId = -1;

function cleanupListenerErrorTest() returns error? {
    Listener? l = listenerErrorTestListener;
    listenerErrorTestListener = ();
    error? stopResult = ();
    if l is Listener {
        stopResult = l.gracefulStop();
    }
    if listenerErrorTestMockId != -1 {
        mockSmscClose(listenerErrorTestMockId);
        listenerErrorTestMockId = -1;
    }
    return stopResult;
}

@test:Config {after: cleanupListenerErrorTest, groups: ["listener", "error"]}
function testOnErrorFiresOnAbruptSeveranceThenRebinds() returns error? {
    clearRecorded();
    clearRecordedErrors();
    int mockId = check mockSmscOpen(LISTENER_SEVER_PORT);
    listenerErrorTestMockId = mockId;

    Listener smsListener = check new ({
        host: "localhost",
        port: LISTENER_SEVER_PORT,
        systemId: "test",
        password: "test",
        bindType: TRANSCEIVER,
        rebindPolicy: {initialRebindDelay: 0.3, maxRebindDelay: 1, backOffMultiplier: 2.0}
    });
    listenerErrorTestListener = smsListener;
    check smsListener.attach(new LifecycleRecordingService());
    check smsListener.'start();
    int conn1 = check mockSmscAwaitNextBind(mockId, 5000);

    check mockSmscSendDeliverSm(mockId, conn1, "before drop", "", 0);
    test:assertEquals(recordedCount(), 1);

    decimal t0 = time:monotonicNow();
    check mockSmscSever(mockId, conn1);

    test:assertTrue(pollUntil(isolated function() returns boolean {
        return recordedErrorCount() >= 1;
    }, 5), "onError must fire within 5s of the abrupt severance");
    test:assertTrue(recordedErrorsContaining("closed unexpectedly") >= 1
            || recordedErrorsContaining("transport died") >= 1,
            "onError's message must describe the unexpected drop");

    int conn2 = check mockSmscAwaitNextBind(mockId, 10000);
    decimal rebindElapsed = time:monotonicNow() - t0;
    test:assertTrue(rebindElapsed >= 0.2d,
            string `rebind landed after only ${rebindElapsed}s - the configured initial backoff was not honored`);

    check mockSmscSendDeliverSm(mockId, conn2, "after rebind", "", 0);
    test:assertEquals(recordedCount(), 2, "the rebound session must keep dispatching");
    test:assertEquals(recordedErrorCount(), 1, "exactly one drop notification for one drop");
}

@test:Config {after: cleanupListenerErrorTest, groups: ["listener", "error"]}
function testPeerInitiatedUnbindAlsoFiresOnErrorAndRebinds() returns error? {
    clearRecorded();
    clearRecordedErrors();
    int mockId = check mockSmscOpen(LISTENER_PEER_UNBIND_PORT);
    listenerErrorTestMockId = mockId;

    Listener smsListener = check new ({
        host: "localhost",
        port: LISTENER_PEER_UNBIND_PORT,
        systemId: "test",
        password: "test",
        bindType: TRANSCEIVER,
        rebindPolicy: {initialRebindDelay: 0.3, maxRebindDelay: 1, backOffMultiplier: 2.0}
    });
    listenerErrorTestListener = smsListener;
    check smsListener.attach(new LifecycleRecordingService());
    check smsListener.'start();
    int conn1 = check mockSmscAwaitNextBind(mockId, 5000);

    // Returning without error is itself an assertion: the connector answered the unbind_resp
    // exchange, which the abrupt-severance path never has to.
    check mockSmscPeerUnbind(mockId, conn1);

    test:assertTrue(pollUntil(isolated function() returns boolean {
        return recordedErrorCount() >= 1;
    }, 5), "onError must fire after a clean peer-initiated unbind too");

    int conn2 = check mockSmscAwaitNextBind(mockId, 10000);
    check mockSmscSendDeliverSm(mockId, conn2, "after peer unbind", "", 0);
    test:assertEquals(recordedCount(), 1, "the rebound session must dispatch normally");
}

@test:Config {after: cleanupListenerErrorTest, groups: ["listener", "error"]}
function testCallerSubmitOnReceiverBindIsRejected() returns error? {
    clearCapturedCaller();
    var [mockId, conn, smsListener] = check startReceiverListener(LISTENER_RECEIVER_CALLER_PORT);
    listenerErrorTestMockId = mockId;
    listenerErrorTestListener = smsListener;

    check mockSmscSendDeliverSm(mockId, conn, "capture", "", 0);
    test:assertTrue(pollUntil(isolated function() returns boolean {
        return capturedCaller() !is ();
    }, 5), "a RECEIVER bind still dispatches - the Caller parameter itself is legal to declare");
    Caller caller = <Caller>capturedCaller();

    SubmitResult|Error r = caller->submit({destinationAddress: "264811234567", shortMessage: "x"});
    test:assertTrue(r is Error, "submit on a RECEIVER-bound Listener must fail");
    Error e = <Error>r;
    test:assertTrue(e.message().includes("bindType") && e.message().includes("TRANSCEIVER"),
            string `must name the config field and the fix: ${e.message()}`);
    test:assertEquals(e.detail().failureMode, INVALID_REQUEST);
}

# Binds a RECEIVER `Listener` (as opposed to `testutils.bal`'s `startListener`, which
# always binds TRANSCEIVER) to a fresh mock, attaching the shared `CallerCapturingService`.
#
# + port - the mock's port
# + return - a `[mockId, connectionId, listener]` tuple, or an error
function startReceiverListener(int port) returns [int, int, Listener]|error {
    int mockId = check mockSmscOpen(port);
    Listener smsListener = check new ({
        host: "localhost",
        port,
        systemId: "test",
        password: "test",
        bindType: RECEIVER
    });
    check smsListener.attach(new CallerCapturingService());
    check smsListener.'start();
    int conn = check mockSmscAwaitNextBind(mockId, 5000);
    return [mockId, conn, smsListener];
}
