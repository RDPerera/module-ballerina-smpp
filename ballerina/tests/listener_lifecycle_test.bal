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

// The Listener's one-way lifecycle and the dispatcher's fallback paths: double start,
// attach/detach rules, stop idempotency, drops on services without an onError (or whose
// onError itself fails), PDUs with no handler, backpressure throttling, and the rebind
// policy's give-up and disabled modes.
import ballerina/lang.runtime;
import ballerina/test;

const int LIFECYCLE_PORT = 28304;
const int NO_ONERROR_DROP_PORT = 28305;
const int ERRORING_ONERROR_PORT = 28306;
const int MISSING_HANDLER_PORT = 28307;
const int THROTTLE_PORT = 28308;
const int REBIND_DISABLED_PORT = 28309;
const int REBIND_GIVE_UP_PORT = 28310;

Listener? lifecycleListener = ();
int lifecycleMockId = -1;

function cleanupLifecycleTest() returns error? {
    Listener? l = lifecycleListener;
    lifecycleListener = ();
    error? stopResult = ();
    if l is Listener {
        stopResult = l.immediateStop();
    }
    if lifecycleMockId != -1 {
        mockSmscClose(lifecycleMockId);
        lifecycleMockId = -1;
    }
    return stopResult;
}

# Records deliveries; its onError deliberately fails, so the dispatcher's "the onError
# handler itself returned an error" fallback is exercised.
service class ErroringOnErrorService {
    *Service;

    remote function onDeliverSm(Sms sms) returns error? {
        recordSms(sms);
    }

    remote function onError(error err) returns error? {
        recordError(err);
        return error("onError failed on purpose");
    }
}

# Handles deliver_sm only: a data_sm has no handler here.
service class DeliverOnlyService {
    *Service;

    remote function onDeliverSm(Sms sms) returns error? {
        recordSms(sms);
    }
}

# Holds each delivery for a second, so a second concurrent deliver_sm exceeds a
# maxConcurrentDispatch of 1 and must be throttled.
service class SlowService {
    *Service;

    remote function onDeliverSm(Sms sms) returns error? {
        runtime:sleep(1);
        recordSms(sms);
    }
}

@test:Config {after: cleanupLifecycleTest, groups: ["listener", "lifecycle"]}
function testListenerLifecycleIsOneWayAndAttachIsExclusive() returns error? {
    RecordingService first = new;
    var [mockId, _, smsListener] = check startListener(LIFECYCLE_PORT, first);
    lifecycleMockId = mockId;
    lifecycleListener = smsListener;

    error? secondStart = smsListener.'start();
    test:assertTrue(secondStart is error, "a second start() on a running listener must be rejected");
    test:assertTrue((<error>secondStart).message().includes("already started"), (<error>secondStart).message());

    error? secondAttach = smsListener.attach(new RecordingService());
    test:assertTrue(secondAttach is error, "one service per listener");
    test:assertTrue((<error>secondAttach).message().includes("detach"), (<error>secondAttach).message());

    check smsListener.detach(first);
    check smsListener.detach(first); // a no-op for a service that is no longer attached
    check smsListener.attach(new RecordingService()); // the slot is free again

    check smsListener.gracefulStop();
    check smsListener.gracefulStop(); // idempotent
    check smsListener.immediateStop(); // and so is the other flavour

    error? restart = smsListener.'start();
    test:assertTrue(restart is error, "a stopped listener cannot be restarted");
    test:assertTrue((<error>restart).message().includes("stopped"), (<error>restart).message());

    error? lateAttach = smsListener.attach(new RecordingService());
    test:assertTrue(lateAttach is error, "nothing can be attached to a stopped listener");
    lifecycleListener = ();
}

@test:Config {after: cleanupLifecycleTest, groups: ["listener", "error"]}
function testDropOnAServiceWithoutOnErrorIsLoggedAndStillRebinds() returns error? {
    clearRecorded();
    int mockId = check mockSmscOpen(NO_ONERROR_DROP_PORT);
    lifecycleMockId = mockId;
    Listener smsListener = check new ("localhost", "test", "test", port = NO_ONERROR_DROP_PORT,
            rebindPolicy = {initialRebindDelay: 0.2, maxRebindDelay: 0.5});
    lifecycleListener = smsListener;
    check smsListener.attach(new RecordingService()); // no onError at all
    check smsListener.'start();
    int conn1 = check mockSmscAwaitNextBind(mockId, 5000);

    check mockSmscSever(mockId, conn1);
    int conn2 = check mockSmscAwaitNextBind(mockId, 10000);
    check mockSmscSendDeliverSm(mockId, conn2, "after rebind", "", 0);
    test:assertEquals(recordedCount(), 1, "a service without onError still gets the rebound session");
}

@test:Config {after: cleanupLifecycleTest, groups: ["listener", "error"]}
function testAFailingOnErrorHandlerDoesNotBreakTheRebind() returns error? {
    clearRecorded();
    clearRecordedErrors();
    int mockId = check mockSmscOpen(ERRORING_ONERROR_PORT);
    lifecycleMockId = mockId;
    Listener smsListener = check new ("localhost", "test", "test", port = ERRORING_ONERROR_PORT,
            rebindPolicy = {initialRebindDelay: 0.2, maxRebindDelay: 0.5});
    lifecycleListener = smsListener;
    check smsListener.attach(new ErroringOnErrorService());
    check smsListener.'start();
    int conn1 = check mockSmscAwaitNextBind(mockId, 5000);

    check mockSmscSever(mockId, conn1);
    test:assertTrue(pollUntil(isolated function() returns boolean {
        return recordedErrorCount() >= 1;
    }, 5), "onError must still be invoked");
    int conn2 = check mockSmscAwaitNextBind(mockId, 10000);
    check mockSmscSendDeliverSm(mockId, conn2, "after rebind", "", 0);
    test:assertEquals(recordedCount(), 1);
}

@test:Config {after: cleanupLifecycleTest, groups: ["listener", "dispatch"]}
function testPduWithNoHandlerIsNackedAndWarnedOnce() returns error? {
    clearRecorded();
    var [mockId, conn, smsListener] = check startListener(MISSING_HANDLER_PORT, new DeliverOnlyService());
    lifecycleMockId = mockId;
    lifecycleListener = smsListener;

    error? first = mockSmscSendDataSm(mockId, conn, "no handler", 0);
    test:assertTrue(first is error, "a data_sm with no onDataSm must be answered negatively");
    error? second = mockSmscSendDataSm(mockId, conn, "still no handler", 0);
    test:assertTrue(second is error, "and consistently so");

    check mockSmscSendDeliverSm(mockId, conn, "handled", "", 0);
    test:assertEquals(recordedCount(), 1, "the handler that does exist keeps working");
}

@test:Config {after: cleanupLifecycleTest, groups: ["listener", "dispatch"]}
function testInboundBeyondMaxConcurrentDispatchIsThrottled() returns error? {
    clearRecorded();
    int mockId = check mockSmscOpen(THROTTLE_PORT);
    lifecycleMockId = mockId;
    Listener smsListener = check new ("localhost", "test", "test", port = THROTTLE_PORT,
            maxConcurrentDispatch = 1, responseMode = SYNC);
    lifecycleListener = smsListener;
    check smsListener.attach(new SlowService());
    check smsListener.'start();
    int conn = check mockSmscAwaitNextBind(mockId, 5000);
    mockSmscSetTransactionTimer(mockId, conn, 10000);

    future<error?> f1 = start mockSmscSendDeliverSm(mockId, conn, "first", "", 0);
    future<error?> f2 = start mockSmscSendDeliverSm(mockId, conn, "second", "", 0);
    future<error?> f3 = start mockSmscSendDeliverSm(mockId, conn, "third", "", 0);
    error? r1 = wait f1;
    error? r2 = wait f2;
    error? r3 = wait f3;
    int rejected = (r1 is error ? 1 : 0) + (r2 is error ? 1 : 0) + (r3 is error ? 1 : 0);
    test:assertTrue(rejected >= 1, "with one dispatch slot, concurrent inbound PDUs must be throttled");
    test:assertTrue(rejected <= 2, "at least the first one must have been dispatched");
    test:assertEquals(recordedCount(), 3 - rejected, "throttled PDUs are not dispatched to the service");
}

@test:Config {after: cleanupLifecycleTest, groups: ["listener", "error", "rebind"]}
function testRebindDisabledLatchesLinkAbandoned() returns error? {
    clearRecordedErrors();
    clearCapturedCaller();
    int mockId = check mockSmscOpen(REBIND_DISABLED_PORT);
    lifecycleMockId = mockId;
    Listener smsListener = check new ("localhost", "test", "test", port = REBIND_DISABLED_PORT,
            bindType = TRANSCEIVER, rebindPolicy = {maxRebindAttempts: 0});
    lifecycleListener = smsListener;
    check smsListener.attach(new CallerCapturingService());
    check smsListener.'start();
    int conn = check mockSmscAwaitNextBind(mockId, 5000);
    check mockSmscSendDeliverSm(mockId, conn, "capture", "", 0);
    test:assertTrue(pollUntil(isolated function() returns boolean {
        return capturedCaller() is Caller;
    }, 5));
    Caller caller = <Caller>capturedCaller();

    check mockSmscSever(mockId, conn);
    test:assertTrue(pollUntil(isolated function() returns boolean {
        return recordedErrorCount() >= 1;
    }, 5), "the drop must be reported even though no rebind will follow");

    SubmitResult|Error r = caller->submit({destinationAddress: "264811234567", shortMessage: "x"});
    test:assertTrue(r is Error);
    test:assertEquals((<Error>r).detail().failureMode, LINK_ABANDONED,
            "with rebinding disabled, the link is abandoned for the rest of this listener's life");
}

@test:Config {after: cleanupLifecycleTest, groups: ["listener", "error", "rebind"]}
function testRebindGivesUpAfterMaxAttempts() returns error? {
    clearRecordedErrors();
    int mockId = check mockSmscOpen(REBIND_GIVE_UP_PORT);
    lifecycleMockId = mockId;
    Listener smsListener = check new ("localhost", "test", "test", port = REBIND_GIVE_UP_PORT,
            bindTimeout = 2,
            rebindPolicy = {initialRebindDelay: 0.1, maxRebindDelay: 0.2, maxRebindAttempts: 1});
    lifecycleListener = smsListener;
    check smsListener.attach(new LifecycleRecordingService());
    check smsListener.'start();
    int conn = check mockSmscAwaitNextBind(mockId, 5000);

    mockSmscStopAccepting(mockId);
    check mockSmscSever(mockId, conn);
    test:assertTrue(pollUntil(isolated function() returns boolean {
        return recordedErrorsContaining("gave up") >= 1;
    }, 15), "after maxRebindAttempts consecutive failures the listener must report giving up");
    test:assertTrue(recordedErrorsContaining("rebind attempt") >= 1,
            "each failed attempt is reported on its own");
}
