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

// receive-sms — the smallest possible `ballerina/smpp` listener program: bind to an
// SMSC as a RECEIVER and log every inbound message and delivery receipt. This is the
// starting point for any receive-side integration (two-way SMS, delivery tracking,
// campaign ingestion, ...).
import ballerina/log;
import ballerina/smpp;

// Connection settings. The defaults match the bundled mock SMSC, so this example
// runs as-is against `examples/mock-smsc$ ./gradlew run --args="steady 2775"`.
// Override with a Config.toml or `bal run -- -Chost=... -Cport=...` for a real SMSC.
configurable string host = "localhost";
configurable int port = 2775;
configurable string systemId = "esme";
configurable string password = "password";

listener smpp:Listener smsListener = check new ({
    host,
    port,
    systemId,
    password,
    // RECEIVER is enough here: this program never replies, so it needs no Caller
    // and no TRANSCEIVER bind. See two-way-sms for the reply-capable counterpart.
    bindType: smpp:RECEIVER
});

service on smsListener {

    // Invoked for every inbound deliver_sm. A RECEIVER bind never sends, so this is
    // the only callback needed. `sms.deliveryReceipt` tells an ordinary
    // mobile-originated (MO) message apart from a delivery receipt (DLR) for
    // something previously submitted (e.g. by the send-sms example).
    remote function onDeliverSm(smpp:Sms sms) returns error? {
        if sms.deliveryReceipt {
            // `receiptedMessageId` (the receipted_message_id TLV) is the only
            // correlation key SMPP guarantees back to a submit's messageId; the
            // Appendix-B body's `receipt?.id` is vendor-specific and best-effort.
            log:printInfo("delivery receipt received",
                    'from = sms.sourceAddress,
                    status = sms.receipt?.finalStatus,
                    id = sms.receiptedMessageId ?: sms.receipt?.id);
        } else {
            log:printInfo("inbound SMS received",
                    'from = sms.sourceAddress,
                    to = sms.destinationAddress,
                    text = sms.shortMessage);
        }
    }

    // An unexpected session drop (network failure, SMSC restart, ...). If this
    // method were omitted, the same drop would still be logged automatically via
    // `ballerina/log` — implementing it lets an application react (e.g. alerting)
    // to a drop distinct from the connector's own automatic rebind attempts.
    remote function onError(error err) returns error? {
        log:printWarn("SMPP session drop", 'error = err);
    }
}
