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

// Thin wrapper over the test-only MockSmscBridge native jar (native/src/testBridge). Every
// function here is a straight `@java:Method` passthrough - the mock's actual behavior lives
// in MockSmsc.java/MockSmscBridge.java, not here. Two roles are exercised through the same
// accepted connection: the mock PUSHING PDUs at a `Listener` (deliver_sm/data_sm/receipts),
// and the mock RECEIVING+ANSWERING a `Client`'s submit-family requests (submit_sm,
// submit_multi, data_sm, query_sm, cancel_sm, replace_sm) with scripted/configurable
// responses.
import ballerina/jballerina.java;

# Opens a mock SMSC (listening socket + background accept-loop) and returns its ref.
# Multiple mocks (and multiple accepted connections per mock) can coexist — nothing is a
# singleton, so tests never collide through shared static state.
#
# + port - the port to listen on
# + return - the mock's ref, or an `error` if the socket can't be opened
isolated function mockSmscOpen(int port) returns int|error = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "openMock"
} external;

# Configures the mock to only accept binds carrying exactly these credentials, rejecting
# others with the distinguishing SMPP status (invalid-systemId vs invalid-password).
# Call before the connector's connect/bind. Without this, the mock accepts any credentials.
#
# + mockId - the mock's ref from `mockSmscOpen`
# + systemId - the only accepted system_id
# + password - the only accepted password
isolated function mockSmscExpectCredentials(int mockId, string systemId, string password) = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "expectCredentials"
} external;

# Blocks until the next bind attempt on this mock resolves. The accept-loop runs in the
# background, so this need not run concurrently with the connector's connect/bind.
#
# + mockId - the mock's ref
# + timeoutMillis - how long to wait for a bind attempt
# + return - the accepted connection's ref, or an `error` carrying the rejection/timeout
isolated function mockSmscAwaitNextBind(int mockId, int timeoutMillis) returns int|error = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "awaitNextBind"
} external;

// ---- SMSC-initiated push (drives a smpp:Listener) --------------------------------------

# Sends one deliver_sm on the given connection, blocking until its deliver_sm_resp
# arrives; a negative response (e.g. the attached service's handler returned an error in
# SYNC mode) surfaces here as an `error`. `messagePayload` empty means "no message_payload
# TLV" — pass a non-empty value to exercise the payload-over-short_message precedence.
#
# + mockId - the mock's ref
# + connectionId - the connection ref from `mockSmscAwaitNextBind`
# + shortMessage - the short_message field's text
# + messagePayload - message_payload TLV text; empty string = omit the TLV entirely
# + dataCoding - the raw data_coding byte value to stamp on the PDU
# + return - an `error` on a negative deliver_sm_resp or send failure
isolated function mockSmscSendDeliverSm(int mockId, int connectionId, string shortMessage,
        string messagePayload, int dataCoding) returns error? = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "sendDeliverSm"
} external;

# Sends one deliver_sm carrying exactly `shortMessage` as raw short_message bytes (no
# charset encoding mock-side), so a test can put a precise on-wire byte sequence in front
# of the connector's decoder.
#
# + mockId - the mock's ref
# + connectionId - the connection ref from `mockSmscAwaitNextBind`
# + shortMessage - the exact short_message bytes to put on the wire
# + dataCoding - the raw data_coding byte value to stamp on the PDU
# + return - an `error` on a negative deliver_sm_resp or send failure
isolated function mockSmscSendDeliverSmRaw(int mockId, int connectionId, byte[] shortMessage,
        int dataCoding) returns error? = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "sendDeliverSmRaw"
} external;

# Sends a deliver_sm flagged as an SMSC delivery receipt, carrying `receiptText` as its
# short_message body — for exercising the connector's receipt-parsing path end to end.
#
# + mockId - the mock's ref
# + connectionId - the connection ref from `mockSmscAwaitNextBind`
# + receiptText - the Appendix-B delivery-receipt body to put in short_message
# + return - an `error` only on misuse (unknown ref) or send failure
isolated function mockSmscSendDeliveryReceipt(int mockId, int connectionId, string receiptText)
        returns error? = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "sendDeliveryReceipt"
} external;

# As above, plus a `receipted_message_id` TLV (0x001E) — the spec's only guaranteed
# correlation key, as against the vendor-specific `id:` in the Appendix-B body. An empty
# `receiptedMessageId` means "no TLV".
#
# + mockId - the mock's ref
# + connectionId - the connection ref
# + receiptText - the Appendix-B receipt body
# + receiptedMessageId - the TLV value, or `""` for no TLV
# + return - an `error` if the receipt cannot be sent
isolated function mockSmscSendDeliveryReceiptWithTlv(int mockId, int connectionId,
        string receiptText, string receiptedMessageId) returns error? = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "sendDeliveryReceiptWithTlv"
} external;

# Sends one data_sm on the given connection (SMSC -> ESME, drives a `Listener`'s onDataSm).
# `messagePayload` empty means "no message_payload TLV at all" (DATA_SM has no
# short_message field, so that exercises the connector's empty-payload fallback).
#
# + mockId - the mock's ref
# + connectionId - the connection ref from `mockSmscAwaitNextBind`
# + messagePayload - message_payload TLV text; empty string = omit the TLV entirely
# + dataCoding - the raw data_coding byte value to stamp on the PDU
# + return - an `error` on a negative data_sm_resp or send failure
isolated function mockSmscSendDataSm(int mockId, int connectionId, string messagePayload,
        int dataCoding) returns error? = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "sendDataSm"
} external;

# Closes the mock's connections, listener, and pools. Safe to call with a ref that
# was already closed.
#
# + mockId - the mock's ref
isolated function mockSmscClose(int mockId) = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "closeMock"
} external;

# Abruptly severs an accepted connection: closes its socket with NO unbind exchange. From
# the connector's side this is indistinguishable from a network failure. The connection
# ref is dead afterwards.
#
# + mockId - the mock's ref
# + connectionId - the connection ref from `mockSmscAwaitNextBind`
# + return - an `error` only on misuse (unknown/already-severed connection ref)
isolated function mockSmscSever(int mockId, int connectionId) returns error? = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "sever"
} external;

# Cleanly unbinds an accepted connection from the mock's (SMSC's) side: sends an unbind
# PDU, blocks until the connector answers unbind_resp, then closes. This returning without
# error is itself an assertion that the unbind exchange happened.
#
# + mockId - the mock's ref
# + connectionId - the connection ref from `mockSmscAwaitNextBind`
# + return - an `error` if the unbind exchange fails or times out
isolated function mockSmscPeerUnbind(int mockId, int connectionId) returns error? = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "peerUnbind"
} external;

# Stops the mock accepting new connections (closes the server socket) while leaving
# already-accepted connections alive — so rebind attempts fail deterministically
# (connection refused). `mockSmscClose` afterwards is still required and still safe.
#
# + mockId - the mock's ref
isolated function mockSmscStopAccepting(int mockId) = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "stopAccepting"
} external;

# When enabled, every subsequent bind on this mock is accepted and the connection is then
# immediately closed (no unbind).
#
# + mockId - the mock's ref
# + enabled - `true` to drop every accepted bind immediately; `false` to restore normal accepts
isolated function mockSmscSetCloseAfterAccept(int mockId, boolean enabled) = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "setCloseAfterAccept"
} external;

# Raises the connection's jsmpp transaction timer (default 2000 ms) so a blocking
# `mockSmscSendDeliverSm` can outwait a deliberately slow SYNC handler.
#
# + mockId - the mock's ref
# + connectionId - the connection ref from `mockSmscAwaitNextBind`
# + timeoutMillis - the new transaction timer
isolated function mockSmscSetTransactionTimer(int mockId, int connectionId, int timeoutMillis) = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "setTransactionTimer"
} external;

# Lowers the connection's enquire_link timer so the mock (as SMSC) probes the connector's
# liveness frequently.
#
# + mockId - the mock's ref
# + connectionId - the connection ref from `mockSmscAwaitNextBind`
# + timeoutMillis - the new enquire_link timer (how often the mock probes when idle)
isolated function mockSmscSetEnquireLinkTimer(int mockId, int connectionId, int timeoutMillis) = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "setEnquireLinkTimer"
} external;

# Opens a TCP "black hole": a server that accepts connections but never answers the bind.
# A connector pointed here completes the TCP connect yet must time out its bind-response
# wait per `bindTimeout` (rather than jsmpp's hardcoded 60s). Returns a ref for cleanup.
#
# + port - the port to listen on
# + return - the black hole's ref, or an `error` if the socket can't be opened
isolated function mockSmscOpenBlackHole(int port) returns int|error = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "openBlackHole"
} external;

# Closes a black-hole server and drops any sockets it is holding.
#
# + blackHoleId - the black hole's ref from `mockSmscOpenBlackHole`
isolated function mockSmscCloseBlackHole(int blackHoleId) = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "closeBlackHole"
} external;

# Opens a TLS-terminating mock SMSC presenting the cert in `serverKeystorePath` (PKCS12).
# The mock verifies nothing about the client (server-auth TLS).
#
# + port - the port to listen on
# + serverKeystorePath - path to the server PKCS12 keystore (cert + private key)
# + serverKeystorePassword - the keystore password
# + return - the mock's ref, or an `error` if the socket/keystore can't be opened
isolated function mockSmscOpenTls(int port, string serverKeystorePath,
        string serverKeystorePassword) returns int|error = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "openMockTls"
} external;

# Opens an mTLS mock: presents `serverKeystorePath`'s cert AND requires the connecting
# client to present a cert trusted by `clientTruststorePath`.
#
# + port - the port to listen on
# + serverKeystorePath - server PKCS12 keystore (cert + key)
# + serverKeystorePassword - server keystore password
# + clientTruststorePath - PKCS12 truststore of client cert(s) the mock will accept
# + clientTruststorePassword - client truststore password
# + return - the mock's ref, or an `error`
isolated function mockSmscOpenMutualTls(int port, string serverKeystorePath,
        string serverKeystorePassword, string clientTruststorePath,
        string clientTruststorePassword) returns int|error = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "openMockMutualTls"
} external;

// ---- submit_sm capture (client-initiated, ESME -> SMSC) ---------------------------------

# Blocks until the next `submit_sm` arrives on this connection, and returns a ref to
# the captured PDU for the field accessors below. FIFO per connection.
#
# + mockId - the mock's ref
# + connectionId - the connection ref from `mockSmscAwaitNextBind`
# + timeoutMillis - how long to wait for a submit
# + return - a ref to the captured `submit_sm`, or an `error` on timeout/unknown ref
isolated function mockSmscAwaitNextSubmit(int mockId, int connectionId, int timeoutMillis)
        returns int|error = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "awaitNextSubmit"
} external;

# How many captured submits are still unread on this connection. Returns `-1` for an
# unknown/severed ref so "no more submits" can never pass vacuously against a dead
# connection.
#
# + mockId - the mock's ref
# + connectionId - the connection ref
# + return - the number of unread captured submits, or `-1` for an unknown ref
isolated function mockSmscPendingSubmitCount(int mockId, int connectionId) returns int = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "pendingSubmitCount"
} external;

# + submitId - the ref from `mockSmscAwaitNextSubmit`
# + return - the decoded `short_message` text
isolated function mockSmscSubmitShortMessage(int submitId) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "submitShortMessage"
} external;

# + submitId - the ref from `mockSmscAwaitNextSubmit`
# + return - the raw, undecoded `short_message` octets
isolated function mockSmscSubmitShortMessageBytes(int submitId) returns byte[] = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "submitShortMessageBytes"
} external;

# + submitId - the ref from `mockSmscAwaitNextSubmit`
# + return - the PDU's `source_addr`
isolated function mockSmscSubmitSourceAddr(int submitId) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "submitSourceAddr"
} external;

# + submitId - the ref from `mockSmscAwaitNextSubmit`
# + return - the PDU's `destination_addr`
isolated function mockSmscSubmitDestAddr(int submitId) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "submitDestAddr"
} external;

# + submitId - the ref from `mockSmscAwaitNextSubmit`
# + return - the PDU's `service_type` (empty string when unset)
isolated function mockSmscSubmitServiceType(int submitId) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "submitServiceType"
} external;

# + submitId - the ref from `mockSmscAwaitNextSubmit`
# + return - the PDU's `validity_period` (empty string means "SMSC default")
isolated function mockSmscSubmitValidityPeriod(int submitId) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "submitValidityPeriod"
} external;

# + submitId - the ref from `mockSmscAwaitNextSubmit`
# + return - the `esm_class` byte as 0-255
isolated function mockSmscSubmitEsmClass(int submitId) returns int = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "submitEsmClass"
} external;

# + submitId - the ref from `mockSmscAwaitNextSubmit`
# + return - the `data_coding` byte as 0-255
isolated function mockSmscSubmitDataCoding(int submitId) returns int = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "submitDataCoding"
} external;

# + submitId - the ref from `mockSmscAwaitNextSubmit`
# + return - the `registered_delivery` byte as 0-255
isolated function mockSmscSubmitRegisteredDelivery(int submitId) returns int = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "submitRegisteredDelivery"
} external;

# + submitId - the ref from `mockSmscAwaitNextSubmit`
# + return - the `source_addr_ton` as 0-255
isolated function mockSmscSubmitSourceAddrTon(int submitId) returns int = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "submitSourceAddrTon"
} external;

# + submitId - the ref from `mockSmscAwaitNextSubmit`
# + return - the `source_addr_npi` as 0-255
isolated function mockSmscSubmitSourceAddrNpi(int submitId) returns int = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "submitSourceAddrNpi"
} external;

# + submitId - the ref from `mockSmscAwaitNextSubmit`
# + return - the `dest_addr_ton` as 0-255
isolated function mockSmscSubmitDestAddrTon(int submitId) returns int = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "submitDestAddrTon"
} external;

# + submitId - the ref from `mockSmscAwaitNextSubmit`
# + return - the `dest_addr_npi` as 0-255
isolated function mockSmscSubmitDestAddrNpi(int submitId) returns int = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "submitDestAddrNpi"
} external;

# The `message_id` the mock returned in the `submit_sm_resp` for this exact PDU, or `""` if
# it sent none (an injected failure, or a response not yet built).
#
# + submitId - the ref from `mockSmscAwaitNextSubmit`
# + return - the `message_id` sent to the client, or `""`
isolated function mockSmscSubmitMessageId(int submitId) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "submitMessageId"
} external;

# + submitId - the ref from `mockSmscAwaitNextSubmit`
# + return - the jsmpp-assigned `sequence_number`
isolated function mockSmscSubmitSequenceNumber(int submitId) returns int = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "submitSequenceNumber"
} external;

# Makes every subsequent `submit_sm` answer with this `command_status` instead of
# succeeding; the client sees it as a negative response. Pass 0 to restore normal
# behaviour.
#
# + mockId - the mock's ref
# + commandStatus - the SMPP `command_status` to answer with, or 0 to disable
isolated function mockSmscSetSubmitFailure(int mockId, int commandStatus) = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "setSubmitFailure"
} external;

# Delays every subsequent `submit_sm_resp`. Deliberately blocks the mock's PDU-processor
# thread — that is what a slow SMSC does, and what `transactionTimeout` has to survive.
#
# + mockId - the mock's ref
# + millis - delay in milliseconds, or 0 to disable
isolated function mockSmscSetSubmitDelay(int mockId, int millis) = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "setSubmitDelay"
} external;

# When enabled, `submit_sm_resp` carries an empty `message_id` — spec-legal, and leaves
# the client with nothing to correlate a later receipt against.
#
# + mockId - the mock's ref
# + enabled - whether to answer with an empty message id
isolated function mockSmscSetSubmitEmptyMessageId(int mockId, boolean enabled) = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "setSubmitEmptyMessageId"
} external;

// ---- submit_multi capture + scripting (client-initiated) --------------------------------

# Blocks until the next `submit_multi` arrives on this connection, and returns a ref to it.
#
# + mockId - the mock's ref
# + connectionId - the connection ref
# + timeoutMillis - how long to wait
# + return - a ref to the captured `submit_multi`, or an `error` on timeout
isolated function mockSmscAwaitNextSubmitMulti(int mockId, int connectionId, int timeoutMillis)
        returns int|error = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "awaitNextSubmitMulti"
} external;

# + ref - the ref from `mockSmscAwaitNextSubmitMulti`
# + return - the batch's destination addresses, in wire order
isolated function mockSmscSubmitMultiDestAddrs(int ref) returns string[] = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "submitMultiDestAddrs"
} external;

# + ref - the ref from `mockSmscAwaitNextSubmitMulti`
# + return - the PDU's `source_addr`
isolated function mockSmscSubmitMultiSourceAddr(int ref) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "submitMultiSourceAddr"
} external;

# + ref - the ref from `mockSmscAwaitNextSubmitMulti`
# + return - the decoded `short_message` text
isolated function mockSmscSubmitMultiShortMessage(int ref) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "submitMultiShortMessage"
} external;

# + ref - the ref from `mockSmscAwaitNextSubmitMulti`
# + return - the PDU's `service_type`
isolated function mockSmscSubmitMultiServiceType(int ref) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "submitMultiServiceType"
} external;

# + ref - the ref from `mockSmscAwaitNextSubmitMulti`
# + return - the `registered_delivery` byte as 0-255
isolated function mockSmscSubmitMultiRegisteredDelivery(int ref) returns int = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "submitMultiRegisteredDelivery"
} external;

# + ref - the ref from `mockSmscAwaitNextSubmitMulti`
# + return - the `message_id` this mock minted for the batch
isolated function mockSmscSubmitMultiMessageId(int ref) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "submitMultiMessageId"
} external;

# Makes every subsequent `submit_multi` answer with this `command_status`. 0 restores
# normal behaviour.
#
# + mockId - the mock's ref
# + commandStatus - the SMPP `command_status` to answer with, or 0 to disable
isolated function mockSmscSetSubmitMultiFailure(int mockId, int commandStatus) = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "setSubmitMultiFailure"
} external;

# Scripts the next `submit_multi_resp`(s). `messageId` empty means auto-generate;
# `unsuccessfulAddresses` lists the destinations to report back as unsuccessful (empty =
# every destination accepted).
#
# + mockId - the mock's ref
# + messageId - the `message_id` to answer with, or `""` to auto-generate
# + unsuccessfulAddresses - destinations to report as unsuccessful
isolated function mockSmscSetSubmitMultiResponse(int mockId, string messageId,
        string[] unsuccessfulAddresses) = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "setSubmitMultiResponse"
} external;

// ---- client-initiated data_sm capture + scripting (ESME -> SMSC) ------------------------
//
// Distinct from mockSmscSendDataSm above (SMSC -> ESME, drives a Listener's onDataSm): this
// is the mock RECEIVING a data_sm from a smpp:Client and answering it.

# Blocks until the next client-originated `data_sm` arrives on this connection.
#
# + mockId - the mock's ref
# + connectionId - the connection ref
# + timeoutMillis - how long to wait
# + return - a ref to the captured `data_sm`, or an `error` on timeout
isolated function mockSmscAwaitNextClientDataSm(int mockId, int connectionId, int timeoutMillis)
        returns int|error = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "awaitNextClientDataSm"
} external;

# + ref - the ref from `mockSmscAwaitNextClientDataSm`
# + return - the PDU's `source_addr`
isolated function mockSmscClientDataSmSourceAddr(int ref) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "clientDataSmSourceAddr"
} external;

# + ref - the ref from `mockSmscAwaitNextClientDataSm`
# + return - the PDU's `destination_addr`
isolated function mockSmscClientDataSmDestAddr(int ref) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "clientDataSmDestAddr"
} external;

# + ref - the ref from `mockSmscAwaitNextClientDataSm`
# + return - the PDU's `service_type`
isolated function mockSmscClientDataSmServiceType(int ref) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "clientDataSmServiceType"
} external;

# + ref - the ref from `mockSmscAwaitNextClientDataSm`
# + return - the `registered_delivery` byte as 0-255
isolated function mockSmscClientDataSmRegisteredDelivery(int ref) returns int = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "clientDataSmRegisteredDelivery"
} external;

# + ref - the ref from `mockSmscAwaitNextClientDataSm`
# + return - the `data_coding` byte as 0-255
isolated function mockSmscClientDataSmDataCoding(int ref) returns int = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "clientDataSmDataCoding"
} external;

# The `message_payload` TLV text, decoded per the PDU's own `data_coding`.
#
# + ref - the ref from `mockSmscAwaitNextClientDataSm`
# + return - the decoded payload text
isolated function mockSmscClientDataSmShortMessage(int ref) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "clientDataSmShortMessage"
} external;

# + ref - the ref from `mockSmscAwaitNextClientDataSm`
# + return - the `message_id` this mock minted for this `data_sm`
isolated function mockSmscClientDataSmMessageId(int ref) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "clientDataSmMessageId"
} external;

# Makes every subsequent client-originated `data_sm` answer with this `command_status`.
#
# + mockId - the mock's ref
# + commandStatus - the SMPP `command_status` to answer with, or 0 to disable
isolated function mockSmscSetClientDataSmFailure(int mockId, int commandStatus) = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "setClientDataSmFailure"
} external;

# Scripts the `message_id` the next `data_sm_resp`(s) carry. Empty means auto-generate.
#
# + mockId - the mock's ref
# + messageId - the `message_id` to answer with, or `""` to auto-generate
isolated function mockSmscSetClientDataSmMessageId(int mockId, string messageId) = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "setClientDataSmMessageId"
} external;

// ---- query_sm capture + scripting (client-initiated) -------------------------------------

# Blocks until the next `query_sm` arrives on this connection.
#
# + mockId - the mock's ref
# + connectionId - the connection ref
# + timeoutMillis - how long to wait
# + return - a ref to the captured `query_sm`, or an `error` on timeout
isolated function mockSmscAwaitNextQuerySm(int mockId, int connectionId, int timeoutMillis)
        returns int|error = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "awaitNextQuerySm"
} external;

# + ref - the ref from `mockSmscAwaitNextQuerySm`
# + return - the PDU's `message_id`
isolated function mockSmscQuerySmMessageId(int ref) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "querySmMessageId"
} external;

# + ref - the ref from `mockSmscAwaitNextQuerySm`
# + return - the PDU's `source_addr`
isolated function mockSmscQuerySmSourceAddr(int ref) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "querySmSourceAddr"
} external;

# Makes every subsequent `query_sm` answer with this `command_status`.
#
# + mockId - the mock's ref
# + commandStatus - the SMPP `command_status` to answer with, or 0 to disable
isolated function mockSmscSetQuerySmFailure(int mockId, int commandStatus) = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "setQuerySmFailure"
} external;

# Scripts the next `query_sm_resp`(s). `messageState` is an `org.jsmpp.bean.MessageState`
# enum name (e.g. `"DELIVERED"`, `"ENROUTE"`, `"UNDELIVERABLE"`); `finalDate` empty means
# "not yet in a final state".
#
# + mockId - the mock's ref
# + messageState - the jsmpp `MessageState` name to answer with
# + finalDate - the `final_date` value, or `""` for absent
# + errorCode - the `error_code` value
isolated function mockSmscSetQuerySmResponse(int mockId, string messageState, string finalDate,
        int errorCode) = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "setQuerySmResponse"
} external;

// ---- cancel_sm capture + scripting (client-initiated) -------------------------------------

# Blocks until the next `cancel_sm` arrives on this connection.
#
# + mockId - the mock's ref
# + connectionId - the connection ref
# + timeoutMillis - how long to wait
# + return - a ref to the captured `cancel_sm`, or an `error` on timeout
isolated function mockSmscAwaitNextCancelSm(int mockId, int connectionId, int timeoutMillis)
        returns int|error = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "awaitNextCancelSm"
} external;

# + ref - the ref from `mockSmscAwaitNextCancelSm`
# + return - the PDU's `message_id`
isolated function mockSmscCancelSmMessageId(int ref) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "cancelSmMessageId"
} external;

# + ref - the ref from `mockSmscAwaitNextCancelSm`
# + return - the PDU's `source_addr`
isolated function mockSmscCancelSmSourceAddr(int ref) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "cancelSmSourceAddr"
} external;

# + ref - the ref from `mockSmscAwaitNextCancelSm`
# + return - the PDU's `destination_addr`
isolated function mockSmscCancelSmDestAddr(int ref) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "cancelSmDestAddr"
} external;

# Makes every subsequent `cancel_sm` answer with this `command_status`.
#
# + mockId - the mock's ref
# + commandStatus - the SMPP `command_status` to answer with, or 0 to disable
isolated function mockSmscSetCancelSmFailure(int mockId, int commandStatus) = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "setCancelSmFailure"
} external;

// ---- replace_sm capture + scripting (client-initiated) -------------------------------------

# Blocks until the next `replace_sm` arrives on this connection.
#
# + mockId - the mock's ref
# + connectionId - the connection ref
# + timeoutMillis - how long to wait
# + return - a ref to the captured `replace_sm`, or an `error` on timeout
isolated function mockSmscAwaitNextReplaceSm(int mockId, int connectionId, int timeoutMillis)
        returns int|error = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "awaitNextReplaceSm"
} external;

# + ref - the ref from `mockSmscAwaitNextReplaceSm`
# + return - the PDU's `message_id`
isolated function mockSmscReplaceSmMessageId(int ref) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "replaceSmMessageId"
} external;

# + ref - the ref from `mockSmscAwaitNextReplaceSm`
# + return - the PDU's `source_addr`
isolated function mockSmscReplaceSmSourceAddr(int ref) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "replaceSmSourceAddr"
} external;

# The replacement `short_message`, decoded as UTF-8 (replace_sm carries no `data_coding` on
# the wire; use ASCII-only text in tests so the decode is unambiguous).
#
# + ref - the ref from `mockSmscAwaitNextReplaceSm`
# + return - the decoded replacement text
isolated function mockSmscReplaceSmShortMessage(int ref) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "replaceSmShortMessage"
} external;

# + ref - the ref from `mockSmscAwaitNextReplaceSm`
# + return - the `registered_delivery` byte as 0-255
isolated function mockSmscReplaceSmRegisteredDelivery(int ref) returns int = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "replaceSmRegisteredDelivery"
} external;

# + ref - the ref from `mockSmscAwaitNextReplaceSm`
# + return - the PDU's `validity_period` (empty string means "SMSC default")
isolated function mockSmscReplaceSmValidityPeriod(int ref) returns string = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "replaceSmValidityPeriod"
} external;

# Makes every subsequent `replace_sm` answer with this `command_status`.
#
# + mockId - the mock's ref
# + commandStatus - the SMPP `command_status` to answer with, or 0 to disable
isolated function mockSmscSetReplaceSmFailure(int mockId, int commandStatus) = @java:Method {
    'class: "io.ballerina.stdlib.smpp.test.MockSmscBridge",
    name: "setReplaceSmFailure"
} external;
