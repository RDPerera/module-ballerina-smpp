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

listener smpp:Listener smsListener = check new (host, systemId, password, port = port, bindType = smpp:RECEIVER);

service on smsListener {

    remote function onDeliverSm(smpp:Sms sms) returns error? {
        if sms.deliveryReceipt {
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

    remote function onError(error err) returns error? {
        log:printWarn("SMPP session drop", 'error = err);
    }
}
