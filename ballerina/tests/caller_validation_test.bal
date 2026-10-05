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

// Caller.submit has its own copy of the outbound validation/encoding path (NativeCaller):
// the same contract as the Client's, pinned against the Caller a transceiver listener hands
// out, plus the lifecycle refusals only a Caller can hit.
import ballerina/test;

const int CALLER_VALIDATION_PORT = 28302;
const int CALLER_STOPPED_PORT = 28303;

Listener? callerValidationListener = ();
int callerValidationMockId = -1;

function cleanupCallerValidationTest() returns error? {
    Listener? l = callerValidationListener;
    callerValidationListener = ();
    error? stopResult = ();
    if l is Listener {
        stopResult = l.immediateStop();
    }
    if callerValidationMockId != -1 {
        mockSmscClose(callerValidationMockId);
        callerValidationMockId = -1;
    }
    return stopResult;
}

# Starts a transceiver listener with a `CallerCapturingService`, drives one deliver_sm
# through it, and returns the `Caller` the handler captured.
#
# + port - the mock SMSC's port
# + return - `[mockId, connectionId, listener, caller]`, or an error
function captureCaller(int port) returns [int, int, Listener, Caller]|error {
    clearCapturedCaller();
    var [mockId, conn, smsListener] = check startListener(port, new CallerCapturingService());
    callerValidationMockId = mockId;
    callerValidationListener = smsListener;
    check mockSmscSendDeliverSm(mockId, conn, "capture", "", 0);
    test:assertTrue(pollUntil(isolated function() returns boolean {
        return capturedCaller() is Caller;
    }, 5), "the handler must have captured its Caller");
    return [mockId, conn, smsListener, <Caller>capturedCaller()];
}

function assertCallerRejection(SubmitResult|Error r, string expectedFragment) {
    test:assertTrue(r is Error, string `expected a local rejection mentioning '${expectedFragment}'`);
    Error e = <Error>r;
    test:assertEquals(e.detail().failureMode, INVALID_REQUEST, e.message());
    test:assertEquals(e.detail().possiblySubmitted, false);
    test:assertTrue(e.message().includes(expectedFragment), string `unexpected wording: ${e.message()}`);
}

@test:Config {after: cleanupCallerValidationTest, groups: ["listener", "caller", "validation"]}
function testCallerEncodingsAndFlagsReachTheWire() returns error? {
    var [mockId, conn, _, caller] = check captureCaller(CALLER_VALIDATION_PORT);

    _ = check caller->submit({destinationAddress: "264811234567", shortMessage: "plain", encoding: ASCII});
    int ascii = check mockSmscAwaitNextSubmit(mockId, conn, 5000);
    test:assertEquals(mockSmscSubmitDataCoding(ascii), 0x01);

    _ = check caller->submit({destinationAddress: "264811234567", shortMessage: "héllo ✓", encoding: UCS2});
    int ucs2 = check mockSmscAwaitNextSubmit(mockId, conn, 5000);
    test:assertEquals(mockSmscSubmitDataCoding(ucs2), 0x08);
    test:assertEquals(mockSmscSubmitShortMessageBytes(ucs2).length(), 14);

    _ = check caller->submit({
        destinationAddress: {value: "SENDER", typeOfNumber: TON_ALPHANUMERIC, numberingPlanIndicator: NPI_UNKNOWN},
        sourceAddress: {value: "32100", typeOfNumber: TON_ABBREVIATED, numberingPlanIndicator: NPI_ISDN},
        shortMessageBytes: [0x05, 0x00, 0x03, 0x01, 0x02, 0x01, 0x41],
        dataCoding: 0,
        udhi: true,
        registeredDelivery: ON_FAILURE_ONLY,
        validityPeriod: "000000020000000R"
    });
    int binary = check mockSmscAwaitNextSubmit(mockId, conn, 5000);
    test:assertEquals(mockSmscSubmitEsmClass(binary) & 0x40, 0x40, "udhi must set esm_class bit 6");
    test:assertEquals(mockSmscSubmitRegisteredDelivery(binary), 0x02);
    test:assertEquals(mockSmscSubmitDestAddrTon(binary), 5);
    test:assertEquals(mockSmscSubmitSourceAddrTon(binary), 6, "TON_ABBREVIATED is 6 on the wire");
    test:assertEquals(mockSmscSubmitValidityPeriod(binary), "000000020000000R");
}

@test:Config {after: cleanupCallerValidationTest, groups: ["listener", "caller", "validation"]}
function testCallerRejectionsNeverReachTheWire() returns error? {
    var [mockId, conn, _, caller] = check captureCaller(CALLER_VALIDATION_PORT);
    string dest = "264811234567";

    // An action call cannot be passed straight into a function, hence the intermediate variable.
    SubmitResult|Error r = caller->submit({destinationAddress: dest, shortMessage: "é", encoding: ASCII});
    assertCallerRejection(r, "not representable");
    r = caller->submit({destinationAddress: dest, shortMessage: "€", encoding: LATIN1});
    assertCallerRejection(r, "not representable");
    r = caller->submit({destinationAddress: dest, shortMessageBytes: [0x41], dataCoding: -1});
    assertCallerRejection(r, "dataCoding must be 0-255");
    r = caller->submit({destinationAddress: dest, shortMessageBytes: [], dataCoding: 0});
    assertCallerRejection(r, "must not be empty");
    r = caller->submit({destinationAddress: dest, shortMessage: repeat("a", 255)});
    assertCallerRejection(r, "octets");
    r = caller->submit({destinationAddress: dest, shortMessage: "x", validityPeriod: "soon"});
    assertCallerRejection(r, "validityPeriod");
    r = caller->submit({destinationAddress: dest, shortMessage: "x", validityPeriod: "240115143000000X"});
    assertCallerRejection(r, "validityPeriod");
    r = caller->submit({destinationAddress: "+94é71234567", shortMessage: "x"});
    assertCallerRejection(r, "non-ASCII");
    r = caller->submit({destinationAddress: "2648\u{0}1234567", shortMessage: "x"});
    assertCallerRejection(r, "embedded NUL");
    r = caller->submit({destinationAddress: repeat("9", 21), shortMessage: "x"});
    assertCallerRejection(r, "characters");
    r = caller->submit({destinationAddress: "", shortMessage: "x"});
    assertCallerRejection(r, "destinationAddress");

    int|error observed = mockSmscAwaitNextSubmit(mockId, conn, 500);
    test:assertTrue(observed is error, "a locally rejected request must never reach the SMSC");
}

@test:Config {after: cleanupCallerValidationTest, groups: ["listener", "caller", "lifecycle"]}
function testCallerSubmitAfterStopIsRefused() returns error? {
    var [_, _, smsListener, caller] = check captureCaller(CALLER_STOPPED_PORT);
    check smsListener.immediateStop();

    SubmitResult|Error r = caller->submit({destinationAddress: "264811234567", shortMessage: "late"});
    test:assertTrue(r is Error, "a Caller must refuse to submit on a stopped listener");
    Error e = <Error>r;
    test:assertEquals(e.detail().failureMode, INVALID_REQUEST);
    test:assertTrue(e.message().includes("stopped"), e.message());
}
