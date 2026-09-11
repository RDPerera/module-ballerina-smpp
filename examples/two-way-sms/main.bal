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

// two-way-sms — a balance-enquiry short code, modeled on the classic telecom
// "text BAL to 12345" self-care flow: a subscriber texts a keyword to a short code
// and a service replies on the same session, inline, with the answer.
//
// This needs the connector's bidirectional surface: a service declares an
// `smpp:Caller` parameter and replies with `caller->submit` on the SAME SMSC
// session the inbound message arrived on. Replying requires `bindType:
// TRANSCEIVER` (a RECEIVER bind cannot transmit), and `responseMode: ASYNC` is the
// documented recommendation for reply-style services: in SYNC mode a slow inline
// reply can delay the deliver_sm_resp past the SMSC's own transaction timer,
// causing the SMSC to redeliver the inbound message and this service to answer it
// twice.
import ballerina/log;
import ballerina/smpp;

configurable string host = "localhost";
configurable int port = 2775;
configurable string systemId = "esme";
configurable string password = "password";
// The short code subscribers see as the sender of every reply.
configurable string shortCode = "12345";

listener smpp:Listener smsListener = check new ({
    host,
    port,
    systemId,
    password,
    bindType: smpp:TRANSCEIVER,
    responseMode: smpp:ASYNC
});

// A pretend account balance lookup. Production code would call a billing/OCS
// system here; the shape of the reply flow is the point of this example.
isolated function lookupBalance(string subscriber) returns decimal {
    // Deterministic "balance" derived from the subscriber number, purely so the
    // mock produces a stable, repeatable demo value per MSISDN.
    int codepointSum = 0;
    foreach int cp in subscriber.toCodePointInts() {
        codepointSum += cp;
    }
    return <decimal>(codepointSum % 50) + 10.50d;
}

isolated service on smsListener {

    isolated remote function onDeliverSm(smpp:Sms sms, smpp:Caller caller) returns error? {
        if sms.deliveryReceipt {
            log:printInfo("balance-enquiry reply delivery receipt",
                    status = sms.receipt?.finalStatus,
                    id = sms.receiptedMessageId ?: sms.receipt?.id);
            return;
        }

        string subscriber = sms.sourceAddress;
        // Keywords are matched on the first word, case-insensitively - real
        // subscribers send "BAL", "bal please", "Bal now", etc.
        string keyword = firstWord(sms.shortMessage).toUpperAscii();

        match keyword {
            "BAL"|"BALANCE" => {
                decimal balance = lookupBalance(subscriber);
                replyTo(caller, subscriber,
                        string `Your current balance is $${balance}. Reply HELP for other commands.`);
            }
            "HELP"|"INFO" => {
                replyTo(caller, subscriber,
                        "Reply BAL to check your balance. Msg&data rates may apply.");
            }
            _ => {
                replyTo(caller, subscriber,
                        string `Unrecognized command "${keyword}". Reply HELP for a list of commands.`);
            }
        }
    }
}

// Replies to `subscriber` from the configured short code and logs the outcome.
// Errors carry the retry-safety bit `possiblySubmitted`: `false` means a retry
// cannot duplicate the message; `true` means the SMSC may already have accepted it.
isolated function replyTo(smpp:Caller caller, string subscriber, string text) {
    smpp:SubmitResult|smpp:Error result = caller->submit({
        destinationAddress: subscriber,
        sourceAddress: {value: shortCode, typeOfNumber: smpp:TON_ABBREVIATED, numberingPlanIndicator: smpp:NPI_UNKNOWN},
        shortMessage: text
    });
    if result is smpp:Error {
        log:printError("reply failed", 'error = result,
                failureMode = result.detail().failureMode,
                possiblySubmitted = result.detail().possiblySubmitted);
    } else {
        log:printInfo("reply submitted", to = subscriber, messageId = result.messageId);
    }
}

// Returns the first whitespace-delimited token of a message (trimmed), or the whole
// trimmed string when there is no space.
isolated function firstWord(string message) returns string {
    string trimmed = message.trim();
    int? space = trimmed.indexOf(" ");
    return space is int ? trimmed.substring(0, space) : trimmed;
}
