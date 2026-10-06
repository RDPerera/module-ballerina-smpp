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

import ballerina/lang.runtime;
import ballerina/test;

const int LISTENER_KEEPALIVE_PORT = 28315;

Listener? keepaliveTestListener = ();
int keepaliveTestMockId = -1;

function cleanupKeepaliveTest() returns error? {
    Listener? l = keepaliveTestListener;
    keepaliveTestListener = ();
    error? stopResult = ();
    if l is Listener {
        stopResult = l.gracefulStop();
    }
    if keepaliveTestMockId != -1 {
        mockSmscClose(keepaliveTestMockId);
        keepaliveTestMockId = -1;
    }
    return stopResult;
}

// An idle listener sends enquire_link every `enquireLinkInterval` seconds and waits up to
// `enquireLinkTimeout` for each enquire_link_resp (the mock answers promptly). The session
// must survive several probe cycles with no onError, and still dispatch afterwards.
@test:Config {after: cleanupKeepaliveTest, groups: ["listener", "lifecycle", "keepalive"]}
function testListenerSurvivesIdleProbesWithinEnquireLinkTimeout() returns error? {
    clearRecorded();
    clearRecordedErrors();
    int mockId = check mockSmscOpen(LISTENER_KEEPALIVE_PORT);
    keepaliveTestMockId = mockId;
    Listener smsListener = check new ("localhost", "test", "test", port = LISTENER_KEEPALIVE_PORT,
            bindType = RECEIVER, enquireLinkInterval = 5, enquireLinkTimeout = 3);
    keepaliveTestListener = smsListener;
    check smsListener.attach(new LifecycleRecordingService());
    check smsListener.'start();
    int conn = check mockSmscAwaitNextBind(mockId, 5000);

    // ≥ 2 probe cycles of idle time.
    runtime:sleep(12);
    test:assertEquals(recordedErrorCount(), 0, "no onError may fire while the SMSC answers every probe");

    check mockSmscSendDeliverSm(mockId, conn, "after idle", "", 0);
    test:assertTrue(pollUntil(isolated function() returns boolean {
        return recordedCount() == 1;
    }, 5), "the session must still dispatch after the idle period");
}
