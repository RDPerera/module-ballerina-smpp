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

// Listener: bind + receive deliver_sm dispatched to onDeliverSm, data_sm to onDataSm, and a
// delivery receipt correctly flagged (deliveryReceipt=true, receipt parsed, and correlated
// against the guaranteed receipted_message_id TLV rather than the vendor-specific body id).
import ballerina/test;

const int LISTENER_DELIVER_SM_PORT = 28100;
const int LISTENER_DATA_SM_PORT = 28101;
const int LISTENER_DLR_PORT = 28102;
const int LISTENER_DLR_MALFORMED_PORT = 28103;

Listener? listenerDispatchTestListener = ();
int listenerDispatchTestMockId = -1;

function cleanupListenerDispatchTest() returns error? {
    Listener? l = listenerDispatchTestListener;
    listenerDispatchTestListener = ();
    error? stopResult = ();
    if l is Listener {
        stopResult = l.gracefulStop();
    }
    if listenerDispatchTestMockId != -1 {
        mockSmscClose(listenerDispatchTestMockId);
        listenerDispatchTestMockId = -1;
    }
    return stopResult;
}

@test:Config {after: cleanupListenerDispatchTest, groups: ["listener", "dispatch"]}
function testListenerDeliverSmDispatched() returns error? {
    clearRecorded();
    var [mockId, conn, smsListener] = check startListener(LISTENER_DELIVER_SM_PORT, new RecordingService());
    listenerDispatchTestMockId = mockId;
    listenerDispatchTestListener = smsListener;

    check mockSmscSendDeliverSm(mockId, conn, "hello inbound", "", 0);
    test:assertTrue(pollUntil(isolated function() returns boolean {
        return recordedCount() == 1;
    }, 5), "onDeliverSm must have been invoked");

    Sms sms = recordedAt(0);
    test:assertEquals(sms.shortMessage, "hello inbound");
    test:assertFalse(sms.deliveryReceipt, "an ordinary MO must not be flagged as a delivery receipt");
    test:assertEquals(sms.sourceAddress, "12345");
    test:assertEquals(sms.destinationAddress, "99999");
}

@test:Config {after: cleanupListenerDispatchTest, groups: ["listener", "dispatch"]}
function testListenerDataSmDispatched() returns error? {
    clearRecorded();
    var [mockId, conn, smsListener] = check startListener(LISTENER_DATA_SM_PORT, new RecordingService());
    listenerDispatchTestMockId = mockId;
    listenerDispatchTestListener = smsListener;

    check mockSmscSendDataSm(mockId, conn, "hello via data_sm", 0);
    test:assertTrue(pollUntil(isolated function() returns boolean {
        return recordedCount() == 1;
    }, 5), "onDataSm must have been invoked");

    Sms sms = recordedAt(0);
    test:assertEquals(sms.shortMessage, "hello via data_sm");
    test:assertEquals(check string:fromBytes(sms.shortMessageBytes), "hello via data_sm",
            "shortMessageBytes must carry the same payload bytes");
    test:assertFalse(sms.deliveryReceipt, "data_sm is never a delivery receipt");
}

@test:Config {after: cleanupListenerDispatchTest, groups: ["listener", "dispatch"]}
function testListenerDeliveryReceiptFlaggedAndCorrelatedByTlv() returns error? {
    clearRecorded();
    var [mockId, conn, smsListener] = check startListener(LISTENER_DLR_PORT, new RecordingService());
    listenerDispatchTestMockId = mockId;
    listenerDispatchTestListener = smsListener;

    // The TLV (0x001E) is the spec's only GUARANTEED correlation key; the Appendix-B body's
    // `id:` is vendor specific and deliberately given a different radix here to prove
    // correlation pins to the TLV, not the body.
    check mockSmscSendDeliveryReceiptWithTlv(mockId, conn,
            "id:deadbeef sub:001 dlvrd:001 submit date:2601010101 done date:2601010102 "
            + "stat:DELIVRD err:000 text:Hello", "1000");
    test:assertTrue(pollUntil(isolated function() returns boolean {
        return recordedCount() == 1;
    }, 5), "the delivery receipt must be dispatched");

    Sms sms = recordedAt(0);
    test:assertTrue(sms.deliveryReceipt, "deliveryReceipt must be true for an SMSC delivery receipt");
    test:assertEquals(sms.receiptedMessageId, "1000",
            "receiptedMessageId (the TLV) must equal what was sent, not the body's id:");
    DeliveryReceipt? receipt = sms.receipt;
    test:assertTrue(receipt is DeliveryReceipt, "a well-formed Appendix-B receipt must parse");
    if receipt is DeliveryReceipt {
        test:assertEquals(receipt.id, "deadbeef", "the vendor-specific body id stays independently available");
        test:assertEquals(receipt.finalStatus, DELIVRD);
        test:assertEquals(receipt.submitted, 1);
        test:assertEquals(receipt.delivered, 1);
        test:assertEquals(receipt.submitDate, "2601010101");
        test:assertEquals(receipt.doneDate, "2601010102");
        test:assertEquals(receipt.errorCode, "000");
    }

    // A receipt with no TLV at all must leave receiptedMessageId nil - absence is meaningful,
    // not a stale/invented value from the previous receipt.
    clearRecorded();
    check mockSmscSendDeliveryReceipt(mockId, conn,
            "id:0000000042 sub:001 dlvrd:001 submit date:2601010101 done date:2601010102 "
            + "stat:DELIVRD err:000 text:x");
    test:assertTrue(pollUntil(isolated function() returns boolean {
        return recordedCount() == 1;
    }, 5), "the second receipt must arrive");
    test:assertEquals(recordedAt(0).receiptedMessageId, (),
            "no TLV on the wire must surface as (), never a stale value");
}

@test:Config {after: cleanupListenerDispatchTest, groups: ["listener", "dispatch"]}
function testListenerMalformedDeliveryReceiptDispatchesRawWithNilReceipt() returns error? {
    clearRecorded();
    var [mockId, conn, smsListener] = check startListener(LISTENER_DLR_MALFORMED_PORT, new RecordingService());
    listenerDispatchTestMockId = mockId;
    listenerDispatchTestListener = smsListener;

    string rawBody = "id:9 stat:BUFFERED err:XYZ done date:whenever";
    check mockSmscSendDeliveryReceipt(mockId, conn, rawBody);
    test:assertTrue(pollUntil(isolated function() returns boolean {
        return recordedCount() == 1;
    }, 5), "even a malformed DLR must still be dispatched, not NACKed");

    Sms sms = recordedAt(0);
    test:assertTrue(sms.deliveryReceipt, "the esm_class DLR bit is still set, so deliveryReceipt is true");
    test:assertTrue(sms.receipt is (), "an unparseable receipt body yields a nil receipt");
    test:assertEquals(sms.shortMessage, rawBody, "the raw receipt body stays available on shortMessage");
}
