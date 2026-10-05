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

// The Client's local pre-send validation and encoding of an OutboundSms: every rejection
// is INVALID_REQUEST with possiblySubmitted = false and nothing reaches the mock, while
// every accepted shape lands on the wire with the expected data_coding/esm_class/TON/NPI.
import ballerina/test;

const int OUTBOUND_VALIDATION_PORT = 28300;
const int OUTBOUND_QUERY_STATES_PORT = 28301;

Client? outboundTestClient = ();
int outboundTestMockId = -1;

function cleanupOutboundTest() returns error? {
    error? closeResult = ();
    Client? c = outboundTestClient;
    if c is Client {
        closeResult = c.close();
        outboundTestClient = ();
    }
    if outboundTestMockId != -1 {
        mockSmscClose(outboundTestMockId);
        outboundTestMockId = -1;
    }
    return closeResult;
}

function assertLocalRejection(SubmitResult|MultiSubmitResult|Error r, string expectedFragment) {
    test:assertTrue(r is Error, string `expected a local rejection mentioning '${expectedFragment}'`);
    Error e = <Error>r;
    test:assertEquals(e.detail().failureMode, INVALID_REQUEST, e.message());
    test:assertEquals(e.detail().possiblySubmitted, false, "a local refusal provably never left this connector");
    test:assertTrue(e.message().includes(expectedFragment), string `unexpected wording: ${e.message()}`);
}

function repeat(string s, int n) returns string {
    string out = "";
    foreach int _ in 0 ..< n {
        out += s;
    }
    return out;
}

@test:Config {after: cleanupOutboundTest, groups: ["client", "validation"]}
function testClientOutboundEncodingsAndFlagsReachTheWire() returns error? {
    var [mockId, conn, smppClient] = check startClient(OUTBOUND_VALIDATION_PORT);
    outboundTestMockId = mockId;
    outboundTestClient = smppClient;

    // ASCII text -> data_coding 0x01.
    _ = check smppClient->submit({destinationAddress: "264811234567", shortMessage: "plain", encoding: ASCII});
    int ascii = check mockSmscAwaitNextSubmit(mockId, conn, 5000);
    test:assertEquals(mockSmscSubmitDataCoding(ascii), 0x01);
    test:assertEquals(mockSmscSubmitShortMessage(ascii), "plain");

    // UCS-2 text -> data_coding 0x08, two octets per character.
    _ = check smppClient->submit({destinationAddress: "264811234567", shortMessage: "héllo ✓", encoding: UCS2});
    int ucs2 = check mockSmscAwaitNextSubmit(mockId, conn, 5000);
    test:assertEquals(mockSmscSubmitDataCoding(ucs2), 0x08);
    test:assertEquals(mockSmscSubmitShortMessageBytes(ucs2).length(), 14);

    // Pre-encoded binary with a UDH: the raw data_coding and the UDHI bit (esm_class 0x40).
    _ = check smppClient->submit({
        destinationAddress: "264811234567",
        shortMessageBytes: [0x05, 0x00, 0x03, 0x01, 0x02, 0x01, 0x41],
        dataCoding: 0,
        udhi: true
    });
    int binary = check mockSmscAwaitNextSubmit(mockId, conn, 5000);
    test:assertEquals(mockSmscSubmitDataCoding(binary), 0x00);
    test:assertEquals(mockSmscSubmitEsmClass(binary) & 0x40, 0x40, "udhi must set esm_class bit 6");
    test:assertEquals(mockSmscSubmitShortMessageBytes(binary).length(), 7);

    // Delivery-receipt request, explicit TON/NPI on the destination, a NULL source address,
    // and an absolute validity period, all forwarded verbatim.
    _ = check smppClient->submit({
        destinationAddress: {value: "SENDER", typeOfNumber: TON_ALPHANUMERIC, numberingPlanIndicator: NPI_UNKNOWN},
        sourceAddress: {value: ""},
        shortMessage: "flags",
        registeredDelivery: ON_FAILURE_ONLY,
        validityPeriod: "240115143000000+"
    });
    int flagged = check mockSmscAwaitNextSubmit(mockId, conn, 5000);
    test:assertEquals(mockSmscSubmitRegisteredDelivery(flagged), 0x02);
    test:assertEquals(mockSmscSubmitDestAddrTon(flagged), 5, "TON_ALPHANUMERIC is 5 on the wire");
    test:assertEquals(mockSmscSubmitDestAddrNpi(flagged), 0, "NPI_UNKNOWN is 0 on the wire");
    test:assertEquals(mockSmscSubmitSourceAddrTon(flagged), 0, "an empty source address ships as TON Unknown");
    test:assertEquals(mockSmscSubmitValidityPeriod(flagged), "240115143000000+");

    // A relative validity period is the other accepted shape.
    _ = check smppClient->submit({destinationAddress: "264811234567", shortMessage: "rel",
            validityPeriod: "000000020000000R"});
    int relative = check mockSmscAwaitNextSubmit(mockId, conn, 5000);
    test:assertEquals(mockSmscSubmitValidityPeriod(relative), "000000020000000R");
}

@test:Config {after: cleanupOutboundTest, groups: ["client", "validation"]}
function testClientOutboundRejectionsNeverReachTheWire() returns error? {
    var [mockId, conn, smppClient] = check startClient(OUTBOUND_VALIDATION_PORT);
    outboundTestMockId = mockId;
    outboundTestClient = smppClient;
    string dest = "264811234567";

    // Each rejection is checked locally, before the request is composed - an action call
    // cannot be passed straight into a function, hence the intermediate variable.
    SubmitResult|Error r = smppClient->submit({destinationAddress: dest, shortMessage: "é", encoding: ASCII});
    assertLocalRejection(r, "not representable");
    r = smppClient->submit({destinationAddress: dest, shortMessage: "€", encoding: LATIN1});
    assertLocalRejection(r, "not representable");
    r = smppClient->submit({destinationAddress: dest, shortMessageBytes: [0x41], dataCoding: 300});
    assertLocalRejection(r, "dataCoding must be 0-255");
    r = smppClient->submit({destinationAddress: dest, shortMessageBytes: [], dataCoding: 0});
    assertLocalRejection(r, "must not be empty");
    r = smppClient->submit({destinationAddress: dest, shortMessage: repeat("a", 255)});
    assertLocalRejection(r, "octets");
    r = smppClient->submit({destinationAddress: dest, shortMessage: "x", validityPeriod: "soon"});
    assertLocalRejection(r, "validityPeriod");
    r = smppClient->submit({destinationAddress: dest, shortMessage: "x", validityPeriod: "24011514300000X+"});
    assertLocalRejection(r, "validityPeriod");
    r = smppClient->submit({destinationAddress: dest, shortMessage: "x", validityPeriod: "240115143000000X"});
    assertLocalRejection(r, "validityPeriod");
    r = smppClient->submit({destinationAddress: "+94é71234567", shortMessage: "x"});
    assertLocalRejection(r, "non-ASCII");
    r = smppClient->submit({destinationAddress: "2648\u{0}1234567", shortMessage: "x"});
    assertLocalRejection(r, "embedded NUL");
    r = smppClient->submit({destinationAddress: repeat("9", 21), shortMessage: "x"});
    assertLocalRejection(r, "characters");
    // (An EMPTY destinationAddress is not rejected here: the Client forwards it as a
    // spec-legal NULL address, unlike Caller.submit - see caller_validation_test.bal.)
    MultiSubmitResult|Error m = smppClient->submitMulti({destinationAddress: "", shortMessage: "x"}, [dest, ""]);
    assertLocalRejection(m, "destinationAddresses[1]");

    int|error observed = mockSmscAwaitNextSubmit(mockId, conn, 500);
    test:assertTrue(observed is error, "a locally rejected request must never reach the SMSC");
}

@test:Config {after: cleanupOutboundTest, groups: ["client", "submit-family"]}
function testClientQueryStatusMapsEveryFinalState() returns error? {
    var [mockId, _, smppClient] = check startClient(OUTBOUND_QUERY_STATES_PORT);
    outboundTestMockId = mockId;
    outboundTestClient = smppClient;

    // jsmpp's MessageState names on the left; this module's Appendix-B tokens on the right.
    // Anything outside the eight spec tokens (SCHEDULED, SKIPPED, ...) folds into UNKNOWN.
    [string, DeliveryReceiptStatus][] cases = [
        ["EXPIRED", EXPIRED],
        ["DELETED", DELETED],
        ["UNDELIVERABLE", UNDELIV],
        ["REJECTED", REJECTD],
        ["UNKNOWN", UNKNOWN],
        ["SCHEDULED", UNKNOWN]
    ];
    foreach var [jsmppState, expected] in cases {
        mockSmscSetQuerySmResponse(mockId, jsmppState, "", 0);
        QueryResult r = check smppClient->queryStatus("1000", "264811234567");
        test:assertEquals(r.messageState, expected, string `jsmpp ${jsmppState} must map to ${expected}`);
    }
}
