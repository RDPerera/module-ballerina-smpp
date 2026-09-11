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

import ballerina/io;
import ballerina/smpp;

configurable string host = ?;
configurable int port = ?;
configurable string systemId = ?;
configurable string password = ?;
configurable string destinationNumber = ?;

public function main() returns error? {
    // TRANSMITTER is enough for a send-only program.
    smpp:Client smppClient = check new (host, systemId, password, port = port, bindType = smpp:TRANSMITTER);

    smpp:SubmitResult|smpp:Error result = smppClient->submit({
        destinationAddress: destinationNumber,
        shortMessage: "Hello from Ballerina SMPP!",
        registeredDelivery: smpp:ON_SUCCESS_OR_FAILURE
    });

    if result is smpp:Error {
        io:println(string `submit failed: ${result.message()}`);
    } else {
        io:println(string `submitted - messageId=${result.messageId}`);
    }

    check smppClient.close();
}
