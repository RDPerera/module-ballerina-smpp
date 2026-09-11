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

// send-sms — the smallest possible `ballerina/smpp` program: bind to an SMSC as a
// TRANSMITTER, submit one text message, and close. This is the starting point for
// any send-side integration (OTPs, alerts, notifications, ...).
import ballerina/io;
import ballerina/smpp;

// Connection settings. The defaults match the bundled mock SMSC, so this example
// runs as-is against `examples/mock-smsc$ ./gradlew run --args="steady 2775"`.
// Override with a Config.toml or `bal run -- -Chost=... -Cport=...` for a real SMSC.
configurable string host = "localhost";
configurable int port = 2775;
configurable string systemId = "esme";
configurable string password = "password";

// The recipient MSISDN. A plain string is shorthand for an international ISDN
// address (smpp:TON_INTERNATIONAL / smpp:NPI_ISDN) — see smpp:Address for short
// codes or alphanumeric sender IDs.
configurable string destinationNumber = "447700900001";

public function main() returns error? {
    // TRANSMITTER is the right bind for a send-only program: it never receives
    // deliver_sm/data_sm, so a RECEIVER/TRANSCEIVER-only credential is not needed.
    smpp:Client smppClient = check new ({
        host,
        port,
        systemId,
        password,
        bindType: smpp:TRANSMITTER
    });

    smpp:SubmitResult|smpp:Error result = smppClient->submit({
        destinationAddress: destinationNumber,
        shortMessage: "Hello from Ballerina SMPP!",
        // Ask the SMSC for a receipt so a receive-side listener (see the
        // receive-sms example) can observe delivery — this program itself does
        // not wait for one; a Client has no receive path.
        registeredDelivery: smpp:ON_SUCCESS_OR_FAILURE
    });

    if result is smpp:Error {
        // `possiblySubmitted` is the bit to branch retry logic on: `false` means a
        // retry cannot duplicate the message.
        io:println(string `submit failed: ${result.message()}`,
                ` failureMode=${result.detail().failureMode ?: "()"}`,
                ` possiblySubmitted=${result.detail().possiblySubmitted ?: "()"}`);
    } else {
        io:println(string `submitted - messageId=${result.messageId}`);
    }

    check smppClient.close();
}
