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

// Client's other submit-family operations: submitMulti (submit_multi), submitData
// (data_sm), queryStatus (query_sm), cancel (cancel_sm), replace (replace_sm). Each is
// exercised against the mock's scriptable per-operation capture/response.
import ballerina/test;

const int CLIENT_SUBMIT_MULTI_PORT = 28010;
const int CLIENT_SUBMIT_MULTI_EMPTY_DESTS_PORT = 28011;
const int CLIENT_SUBMIT_DATA_PORT = 28012;
const int CLIENT_SUBMIT_DATA_FAILURE_PORT = 28013;
const int CLIENT_QUERY_PORT = 28014;
const int CLIENT_QUERY_NULL_ADDR_PORT = 28015;
const int CLIENT_CANCEL_PORT = 28016;
const int CLIENT_CANCEL_NO_DEST_PORT = 28017;
const int CLIENT_CANCEL_FAILURE_PORT = 28018;
const int CLIENT_REPLACE_PORT = 28019;
const int CLIENT_REPLACE_FAILURE_PORT = 28020;

Client? submitFamilyTestClient = ();
int submitFamilyTestMockId = -1;

function cleanupSubmitFamilyTest() returns error? {
    Client? c = submitFamilyTestClient;
    submitFamilyTestClient = ();
    error? closeResult = ();
    if c is Client {
        closeResult = c.close();
    }
    if submitFamilyTestMockId != -1 {
        mockSmscClose(submitFamilyTestMockId);
        submitFamilyTestMockId = -1;
    }
    return closeResult;
}

@test:Config {after: cleanupSubmitFamilyTest, groups: ["client", "submit-family"]}
function testClientSubmitMulti() returns error? {
    var [mockId, conn, smppClient] = check startClient(CLIENT_SUBMIT_MULTI_PORT);
    submitFamilyTestMockId = mockId;
    submitFamilyTestClient = smppClient;

    // One destination scripted as unsuccessful, so the batch-partial-failure shape is
    // exercised too, not just the all-accepted happy path.
    mockSmscSetSubmitMultiResponse(mockId, "", ["264800000002"]);
    MultiSubmitResult r = check smppClient->submitMulti(
            {destinationAddress: "unused", shortMessage: "broadcast text"},
            ["264800000001", "264800000002", "264800000003"]);

    int ref = check mockSmscAwaitNextSubmitMulti(mockId, conn, 5000);
    test:assertEquals(mockSmscSubmitMultiDestAddrs(ref),
            ["264800000001", "264800000002", "264800000003"]);
    test:assertEquals(mockSmscSubmitMultiShortMessage(ref), "broadcast text");
    test:assertEquals(r.messageId, mockSmscSubmitMultiMessageId(ref),
            "the returned id must be the id the mock minted for this submit_multi");
    test:assertEquals(r.unsuccessfulAddresses, ["264800000002"],
            "the destination scripted as unsuccessful must be surfaced back");
}

@test:Config {after: cleanupSubmitFamilyTest, groups: ["client", "submit-family"]}
function testClientSubmitMultiRequiresAtLeastOneDestination() returns error? {
    var [mockId, _, smppClient] = check startClient(CLIENT_SUBMIT_MULTI_EMPTY_DESTS_PORT);
    submitFamilyTestMockId = mockId;
    submitFamilyTestClient = smppClient;

    MultiSubmitResult|Error r = smppClient->submitMulti({destinationAddress: "unused", shortMessage: "x"}, []);
    test:assertTrue(r is Error, "an empty destinationAddresses must be rejected locally");
    test:assertEquals((<Error>r).detail().failureMode, INVALID_REQUEST);
}

@test:Config {after: cleanupSubmitFamilyTest, groups: ["client", "submit-family"]}
function testClientSubmitData() returns error? {
    var [mockId, conn, smppClient] = check startClient(CLIENT_SUBMIT_DATA_PORT);
    submitFamilyTestMockId = mockId;
    submitFamilyTestClient = smppClient;

    mockSmscSetClientDataSmMessageId(mockId, "9001");
    SubmitResult r = check smppClient->submitData({
        destinationAddress: "264811234567",
        shortMessage: "via data_sm"
    });
    test:assertEquals(r.messageId, "9001", "the scripted message_id must be returned verbatim");

    int ref = check mockSmscAwaitNextClientDataSm(mockId, conn, 5000);
    test:assertEquals(mockSmscClientDataSmDestAddr(ref), "264811234567");
    test:assertEquals(mockSmscClientDataSmShortMessage(ref), "via data_sm",
            "data_sm carries its payload in the message_payload TLV, decoded the same way");
    test:assertEquals(mockSmscClientDataSmMessageId(ref), "9001");
}

@test:Config {after: cleanupSubmitFamilyTest, groups: ["client", "submit-family"]}
function testClientSubmitDataFailureMapsToRejected() returns error? {
    var [mockId, _, smppClient] = check startClient(CLIENT_SUBMIT_DATA_FAILURE_PORT);
    submitFamilyTestMockId = mockId;
    submitFamilyTestClient = smppClient;

    mockSmscSetClientDataSmFailure(mockId, 0x0B); // ESME_RINVDSTADR
    SubmitResult|Error r = smppClient->submitData({destinationAddress: "264811234567", shortMessage: "x"});
    test:assertTrue(r is Error, "an injected negative data_sm_resp must surface as an Error");
    Error e = <Error>r;
    test:assertEquals(e.detail().failureMode, REJECTED);
    test:assertEquals(e.detail().commandStatus, 0x0B);
    test:assertEquals(e.detail().possiblySubmitted, false,
            "the SMSC definitively refused it - a retry cannot duplicate");
}

@test:Config {after: cleanupSubmitFamilyTest, groups: ["client", "submit-family"]}
function testClientQueryStatus() returns error? {
    var [mockId, conn, smppClient] = check startClient(CLIENT_QUERY_PORT);
    submitFamilyTestMockId = mockId;
    submitFamilyTestClient = smppClient;

    // A message that has reached a final state. jsmpp's own outbound PDU composer only
    // accepts an EMPTY final_date or EXACTLY 16 characters for this C-octet-string field
    // (StringValidator's "null-or-N" rule) - so the mock, itself built on jsmpp, can only
    // script one of those two shapes; a real SMSC's own encoder is a separate concern this
    // pass-through field is documented as agnostic to (Client.queryStatus forwards
    // final_date verbatim, with no format validation of its own).
    mockSmscSetQuerySmResponse(mockId, "DELIVERED", "2601010101000000", 5);
    QueryResult delivered = check smppClient->queryStatus("1000", "264811234567");
    int ref = check mockSmscAwaitNextQuerySm(mockId, conn, 5000);
    test:assertEquals(mockSmscQuerySmMessageId(ref), "1000");
    test:assertEquals(mockSmscQuerySmSourceAddr(ref), "264811234567");
    test:assertEquals(delivered.messageState, DELIVRD,
            "jsmpp's MessageState.DELIVERED must be renamed to this module's DELIVRD");
    test:assertEquals(delivered.finalDate, "2601010101000000");
    test:assertEquals(delivered.errorCode, 5);

    // A message still in flight: no final_date yet.
    mockSmscSetQuerySmResponse(mockId, "ENROUTE", "", 0);
    QueryResult enroute = check smppClient->queryStatus("1000", "264811234567");
    test:assertEquals(enroute.messageState, ENROUTE);
    test:assertEquals(enroute.finalDate, (), "an absent final_date must surface as (), not empty string");
    test:assertEquals(enroute.errorCode, 0);
}

@test:Config {after: cleanupSubmitFamilyTest, groups: ["client", "submit-family"]}
function testClientQueryStatusWithEmptySourceAddrIsNullAddressShorthand() returns error? {
    var [mockId, conn, smppClient] = check startClient(CLIENT_QUERY_NULL_ADDR_PORT);
    submitFamilyTestMockId = mockId;
    submitFamilyTestClient = smppClient;

    mockSmscSetQuerySmResponse(mockId, "ACCEPTED", "", 0);
    QueryResult r = check smppClient->queryStatus("1000", "");
    int ref = check mockSmscAwaitNextQuerySm(mockId, conn, 5000);
    test:assertEquals(mockSmscQuerySmSourceAddr(ref), "", "empty sourceAddress must ship as a NULL address");
    test:assertEquals(r.messageState, ACCEPTD);
}

@test:Config {after: cleanupSubmitFamilyTest, groups: ["client", "submit-family"]}
function testClientCancel() returns error? {
    var [mockId, conn, smppClient] = check startClient(CLIENT_CANCEL_PORT);
    submitFamilyTestMockId = mockId;
    submitFamilyTestClient = smppClient;

    Error? result = check smppClient->cancel("1000", "264811234567", "264899999999");
    test:assertTrue(result is (), "a successful cancel_sm must return ()");
    int ref = check mockSmscAwaitNextCancelSm(mockId, conn, 5000);
    test:assertEquals(mockSmscCancelSmMessageId(ref), "1000");
    test:assertEquals(mockSmscCancelSmSourceAddr(ref), "264811234567");
    test:assertEquals(mockSmscCancelSmDestAddr(ref), "264899999999");
}

@test:Config {after: cleanupSubmitFamilyTest, groups: ["client", "submit-family"]}
function testClientCancelRequiresDestAddr() returns error? {
    var [mockId, _, smppClient] = check startClient(CLIENT_CANCEL_NO_DEST_PORT);
    submitFamilyTestMockId = mockId;
    submitFamilyTestClient = smppClient;

    Error? result = smppClient->cancel("1000", "264811234567", "");
    test:assertTrue(result is Error, "cancel's destinationAddress is required - unlike sourceAddress, it has no NULL shorthand");
    test:assertEquals((<Error>result).detail().failureMode, INVALID_REQUEST);
}

@test:Config {after: cleanupSubmitFamilyTest, groups: ["client", "submit-family"]}
function testClientCancelFailureMapsToRejected() returns error? {
    var [mockId, _, smppClient] = check startClient(CLIENT_CANCEL_FAILURE_PORT);
    submitFamilyTestMockId = mockId;
    submitFamilyTestClient = smppClient;

    mockSmscSetCancelSmFailure(mockId, 0x0B); // ESME_RINVDSTADR
    Error? result = smppClient->cancel("no-such-id", "264811234567", "264899999999");
    test:assertTrue(result is Error);
    Error e = <Error>result;
    test:assertEquals(e.detail().failureMode, REJECTED);
    test:assertEquals(e.detail().commandStatus, 0x0B);
}

@test:Config {after: cleanupSubmitFamilyTest, groups: ["client", "submit-family"]}
function testClientReplace() returns error? {
    var [mockId, conn, smppClient] = check startClient(CLIENT_REPLACE_PORT);
    submitFamilyTestMockId = mockId;
    submitFamilyTestClient = smppClient;

    Error? result = check smppClient->replace("1000", "264811234567", {
        destinationAddress: "unused - replace_sm carries no destination_addr on the wire",
        shortMessage: "replacement text",
        registeredDelivery: ON_SUCCESS_OR_FAILURE
    });
    test:assertTrue(result is (), "a successful replace_sm must return ()");

    int ref = check mockSmscAwaitNextReplaceSm(mockId, conn, 5000);
    test:assertEquals(mockSmscReplaceSmMessageId(ref), "1000");
    test:assertEquals(mockSmscReplaceSmSourceAddr(ref), "264811234567");
    test:assertEquals(mockSmscReplaceSmShortMessage(ref), "replacement text");
    test:assertEquals(mockSmscReplaceSmRegisteredDelivery(ref), 0x01);
}

@test:Config {after: cleanupSubmitFamilyTest, groups: ["client", "submit-family"]}
function testClientReplaceFailureMapsToRejected() returns error? {
    var [mockId, _, smppClient] = check startClient(CLIENT_REPLACE_FAILURE_PORT);
    submitFamilyTestMockId = mockId;
    submitFamilyTestClient = smppClient;

    mockSmscSetReplaceSmFailure(mockId, 0x0B);
    Error? result = smppClient->replace("no-such-id", "264811234567",
            {destinationAddress: "unused", shortMessage: "x"});
    test:assertTrue(result is Error);
    test:assertEquals((<Error>result).detail().failureMode, REJECTED);
    test:assertEquals((<Error>result).detail().commandStatus, 0x0B);
}
