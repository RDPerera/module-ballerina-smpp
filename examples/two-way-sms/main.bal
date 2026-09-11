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

import ballerina/log;
import ballerina/smpp;

configurable string host = ?;
configurable int port = ?;
configurable string systemId = ?;
configurable string password = ?;
configurable string shortCode = ?;

listener smpp:Listener smsListener = check new ({
    host,
    port,
    systemId,
    password,
    bindType: smpp:TRANSCEIVER,
    responseMode: smpp:ASYNC
});

// Stand-in for a real billing/OCS lookup.
isolated function lookupBalance(string subscriber) returns decimal {
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

// Replies on the same session, from the configured short code.
isolated function replyTo(smpp:Caller caller, string subscriber, string text) {
    smpp:SubmitResult|smpp:Error result = caller->submit({
        destinationAddress: subscriber,
        sourceAddress: {value: shortCode, typeOfNumber: smpp:TON_ABBREVIATED, numberingPlanIndicator: smpp:NPI_UNKNOWN},
        shortMessage: text
    });
    if result is smpp:Error {
        log:printError("reply failed", 'error = result, failureMode = result.detail().failureMode);
    } else {
        log:printInfo("reply submitted", to = subscriber, messageId = result.messageId);
    }
}

isolated function firstWord(string message) returns string {
    string trimmed = message.trim();
    int? space = trimmed.indexOf(" ");
    return space is int ? trimmed.substring(0, space) : trimmed;
}
