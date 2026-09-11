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

// A `Caller.submit()` reply issued from WITHIN a `onDeliverSm` handler (the documented
// reply-style-service shape), plus driving the same `Caller` from OUTSIDE a handler once
// captured - both submit on the listener's live session and both land at the mock.
import ballerina/test;

const int LISTENER_CALLER_INLINE_REPLY_PORT = 28120;
const int LISTENER_CALLER_CAPTURED_PORT = 28121;
const int LISTENER_CALLER_ON_DATA_SM_PORT = 28122;

Listener? listenerCallerTestListener = ();
int listenerCallerTestMockId = -1;

function cleanupListenerCallerTest() returns error? {
    Listener? l = listenerCallerTestListener;
    listenerCallerTestListener = ();
    error? stopResult = ();
    if l is Listener {
        stopResult = l.gracefulStop();
    }
    if listenerCallerTestMockId != -1 {
        mockSmscClose(listenerCallerTestMockId);
        listenerCallerTestMockId = -1;
    }
    return stopResult;
}

@test:Config {after: cleanupListenerCallerTest, groups: ["listener", "caller"]}
function testCallerSubmitReplyFromWithinOnDeliverSmHandler() returns error? {
    clearRecorded();
    var [mockId, conn, smsListener] = check startListener(
            LISTENER_CALLER_INLINE_REPLY_PORT, new InlineReplyService());
    listenerCallerTestMockId = mockId;
    listenerCallerTestListener = smsListener;

    // The mobile-originated message that triggers the reply.
    check mockSmscSendDeliverSm(mockId, conn, "ping", "", 0);

    // The handler's own reply (`re:ping`) is a submit_sm on the SAME session; it must land
    // at the mock as an ordinary captured submit.
    int submitId = check mockSmscAwaitNextSubmit(mockId, conn, 5000);
    test:assertEquals(mockSmscSubmitShortMessage(submitId), "re:ping");
    test:assertEquals(mockSmscSubmitDestAddr(submitId), "12345",
            "the reply must be addressed back to the inbound message's sourceAddress");
    test:assertTrue(mockSmscSubmitMessageId(submitId).length() > 0,
            "the SMSC must have accepted and minted an id for the inline reply");
}

@test:Config {after: cleanupListenerCallerTest, groups: ["listener", "caller"]}
function testCallerCapturedFromOnDeliverSmSubmitsOutsideTheHandler() returns error? {
    clearRecorded();
    clearCapturedCaller();
    var [mockId, conn, smsListener] = check startListener(
            LISTENER_CALLER_CAPTURED_PORT, new CallerCapturingService());
    listenerCallerTestMockId = mockId;
    listenerCallerTestListener = smsListener;

    check mockSmscSendDeliverSm(mockId, conn, "capture me", "", 0);
    test:assertTrue(pollUntil(isolated function() returns boolean {
        return capturedCaller() !is ();
    }, 5), "the dispatch never delivered a Caller");
    Caller caller = <Caller>capturedCaller();

    // Driven from ordinary test code, well outside the handler that captured it - the
    // Caller stays valid and keeps resolving the listener's live session.
    SubmitResult first = check caller->submit({
        destinationAddress: "264811234567",
        shortMessage: "reply one"
    });
    int submit1 = check mockSmscAwaitNextSubmit(mockId, conn, 5000);
    test:assertEquals(mockSmscSubmitShortMessage(submit1), "reply one");
    test:assertEquals(first.messageId, mockSmscSubmitMessageId(submit1));

    SubmitResult second = check caller->submit({
        destinationAddress: "264811234567",
        shortMessage: "reply two"
    });
    int submit2 = check mockSmscAwaitNextSubmit(mockId, conn, 5000);
    test:assertEquals(mockSmscSubmitShortMessage(submit2), "reply two");
    test:assertTrue(first.messageId != second.messageId, "distinct submits must get distinct ids");
    test:assertEquals(mockSmscPendingSubmitCount(mockId, conn), 0, "and no more submits");
}

@test:Config {after: cleanupListenerCallerTest, groups: ["listener", "caller"]}
function testCallerParameterPositionedIndependentlyPerDispatchSite() returns error? {
    // onDeliverSm and onDataSm are separate dispatch sites; CallerCapturingService declares
    // the Caller on both, and the parameter must be bound (and usable) from either.
    clearRecorded();
    clearCapturedCaller();
    var [mockId, conn, smsListener] = check startListener(
            LISTENER_CALLER_ON_DATA_SM_PORT, new CallerCapturingService());
    listenerCallerTestMockId = mockId;
    listenerCallerTestListener = smsListener;

    check mockSmscSendDataSm(mockId, conn, "via data_sm", 0);
    test:assertTrue(pollUntil(isolated function() returns boolean {
        return capturedCaller() !is ();
    }, 5), "onDataSm must also receive a usable Caller");
    Caller caller = <Caller>capturedCaller();

    SubmitResult r = check caller->submit({destinationAddress: "264811234567", shortMessage: "from onDataSm"});
    int submitId = check mockSmscAwaitNextSubmit(mockId, conn, 5000);
    test:assertEquals(mockSmscSubmitShortMessage(submitId), "from onDataSm");
    test:assertTrue(r.messageId.length() > 0);
}
