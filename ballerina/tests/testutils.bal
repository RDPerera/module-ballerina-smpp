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

// Shared recording stores + services for the bal test suite. All test files compile into
// this one module, so this store and its services are shared; tests run sequentially
// (Ballerina's default), and every test that uses a store clears it in its own setup, so
// cross-test contamination can't occur.
import ballerina/lang.runtime;

isolated Sms[] recordedSms = [];

isolated function recordSms(Sms sms) {
    lock {
        recordedSms.push(sms.clone());
    }
}

isolated function recordedCount() returns int {
    lock {
        return recordedSms.length();
    }
}

isolated function recordedAt(int i) returns Sms {
    lock {
        return recordedSms[i].clone();
    }
}

isolated function clearRecorded() {
    lock {
        recordedSms.removeAll();
    }
}

# Records every deliver_sm and data_sm it receives; never fails.
service class RecordingService {
    *Service;

    remote function onDeliverSm(Sms sms) returns error? {
        recordSms(sms);
    }

    remote function onDataSm(Sms sms) returns error? {
        recordSms(sms);
    }
}

// ---- onError recording (drop / lifecycle tests) ----
isolated string[] recordedErrors = [];

isolated function recordError(error err) {
    lock {
        recordedErrors.push(err.message());
    }
}

isolated function recordedErrorCount() returns int {
    lock {
        return recordedErrors.length();
    }
}

# Count of recorded onError messages containing `substring`. Drop/lifecycle tests assert
# per-cause counts, not ordering — onError notifications run on their own strand, so
# arrival order is not guaranteed.
#
# + substring - the message fragment to count
# + return - how many recorded onError messages contain it
isolated function recordedErrorsContaining(string substring) returns int {
    lock {
        int n = 0;
        foreach string msg in recordedErrors {
            if msg.includes(substring) {
                n += 1;
            }
        }
        return n;
    }
}

isolated function clearRecordedErrors() {
    lock {
        recordedErrors.removeAll();
    }
}

# Records every delivery and every onError notification; never fails.
service class LifecycleRecordingService {
    *Service;

    remote function onDeliverSm(Sms sms) returns error? {
        recordSms(sms);
    }

    remote function onError(error err) returns error? {
        recordError(err);
    }
}

// ---- Caller capture (Caller.submit reply tests) ----
isolated Caller? capturedCallerVar = ();
isolated int dispatchCountVar = 0;

isolated function capturedCaller() returns Caller? {
    lock {
        return capturedCallerVar;
    }
}

isolated function clearCapturedCaller() {
    lock {
        capturedCallerVar = ();
    }
    lock {
        dispatchCountVar = 0;
    }
}

isolated function dispatchCount() returns int {
    lock {
        return dispatchCountVar;
    }
}

# Captures the `Caller` handed to `onDeliverSm`/`onDataSm` and records every dispatch, so a
# test can pull the `Caller` out and drive `Caller.submit` from outside the handler.
isolated service class CallerCapturingService {
    *Service;

    remote isolated function onError(error err) returns error? {
        recordError(err);
    }

    remote isolated function onDeliverSm(Sms sms, Caller caller) returns error? {
        lock {
            capturedCallerVar = caller;
        }
        recordSms(sms);
        lock {
            dispatchCountVar += 1;
        }
    }

    remote isolated function onDataSm(Sms sms, Caller caller) returns error? {
        lock {
            capturedCallerVar = caller;
        }
        recordSms(sms);
        lock {
            dispatchCountVar += 1;
        }
    }
}

# Replies inline, from inside `onDeliverSm`, with text derived from the inbound message -
# the "Caller.submit from within a handler" shape.
isolated service class InlineReplyService {
    *Service;

    remote isolated function onDeliverSm(Sms sms, Caller caller) returns error? {
        string replyText = string `re:${sms.shortMessage}`;
        SubmitResult|Error r = caller->submit({destinationAddress: sms.sourceAddress, shortMessage: replyText});
        if r is SubmitResult {
            lock {
                dispatchCountVar += 1;
            }
        }
    }

    remote isolated function onError(error err) returns error? {
        recordError(err);
    }
}

# Polls `cond` every 100 ms until true or `timeoutSeconds` elapses; returns the final value.
#
# + cond - the condition to poll
# + timeoutSeconds - how long to keep polling
# + return - the condition's final value
function pollUntil(function () returns boolean cond, decimal timeoutSeconds) returns boolean {
    int attempts = <int>(timeoutSeconds * 10);
    int i = 0;
    while i < attempts {
        if cond() {
            return true;
        }
        runtime:sleep(0.1);
        i += 1;
    }
    return cond();
}

# Binds a TRANSCEIVER `Listener` to a fresh mock on `port`, attaches `svc`, starts it, and
# waits for the bind. Callers are responsible for stopping the listener/closing the mock.
#
# + port - the mock's port
# + svc - the service to attach
# + return - `[mockId, connectionId, listener]`, or an error
function startListener(int port, Service svc) returns [int, int, Listener]|error {
    int mockId = check mockSmscOpen(port);
    Listener smsListener = check new ({
        host: "localhost",
        port,
        systemId: "test",
        password: "test",
        bindType: TRANSCEIVER
    });
    check smsListener.attach(svc);
    check smsListener.'start();
    int conn = check mockSmscAwaitNextBind(mockId, 5000);
    return [mockId, conn, smsListener];
}

# Binds a TRANSCEIVER `Client` to a fresh mock on `port` and returns the mock id, the
# accepted connection id, and the bound client. Callers are responsible for closing the
# client/mock.
#
# + port - the mock's port
# + return - a `[mockId, connectionId, client]` tuple, or an error
function startClient(int port) returns [int, int, Client]|error {
    int mockId = check mockSmscOpen(port);
    Client smppClient = check new ({
        host: "localhost",
        port,
        systemId: "test",
        password: "test",
        bindType: TRANSCEIVER
    });
    int conn = check mockSmscAwaitNextBind(mockId, 5000);
    return [mockId, conn, smppClient];
}
