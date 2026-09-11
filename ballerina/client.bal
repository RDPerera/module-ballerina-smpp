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

import ballerina/jballerina.java;

# Configuration used to connect and bind a `Client` to an SMSC. `host`, `systemId`, and
# `password` are not fields here — they are required, positional/named parameters of
# `Client.init` itself (matching how e.g. `rabbitmq:Client` pulls `host`/`port` out as
# `init` parameters ahead of its included config record), so a caller cannot forget them
# the way a record field with no visible required-ness marker in a call site can be missed.
public type ClientConfig record {|
    # SMSC port. Defaults to the common SMPP port `2775`. Must be 1-65535 (validated at
    # `Client` init).
    int port = 2775;
    # The optional `system_type`. Empty by default.
    string systemType = "";
    # The bind mode. Defaults to `TRANSCEIVER`. Unlike a `Listener` (whose
    # `ListenerBindType` excludes `TRANSMITTER`, since a receive-only trigger has no use
    # for a send-only bind), a `Client` accepts the full `BindType`: `TRANSMITTER` and
    # `TRANSCEIVER` can submit; `RECEIVER` cannot — every remote method on a
    # `RECEIVER`-bound `Client` fails fast with an `INVALID_REQUEST` `Error` naming the
    # fix, rather than reaching the SMSC only to be rejected there.
    BindType bindType = TRANSCEIVER;
    # Maximum time the connect-and-bind handshake may take, in seconds. Bounds the TCP
    # connect and the bind-response wait *separately*, so a fully stalled attempt (a
    # black-holed host that also never answers) can take up to ~2x this value. This field
    # is in SECONDS. Must be 1-300 (validated at `Client` init).
    decimal bindTimeout = 60;
    # How long a submit-family operation (`submit`, `submitMulti`, `submitData`,
    # `queryStatus`, `cancel`, `replace`) waits for the SMSC's response, in seconds.
    #
    # Unlike a `Listener` (whose native session subclass splits jsmpp's one internal timer
    # into a short housekeeping bound plus this configured value), a `Client` has no
    # rebind loop whose responsiveness depends on fast housekeeping, so it applies this
    # value to jsmpp's single timer directly: it
    # also governs the `unbind_resp` wait inside `close` and the `enquire_link_resp`
    # wait that detects a silently dead link between calls. `close` is independently
    # bounded by its own force-close watchdog regardless of this value, so a large value
    # here never stretches `close` — it can only slow how quickly this client notices a
    # silently dead SMSC while otherwise idle. This field is in SECONDS (jsmpp's
    # underlying knob is milliseconds). Must be 1-300 (validated at `Client` init).
    decimal transactionTimeout = 30;
    # How often this client sends an `enquire_link` to the SMSC when the session is
    # otherwise idle, in seconds — its own keepalive/liveness probe. This field is in
    # SECONDS (jsmpp's underlying knob is milliseconds). Must be 5-3600 (validated at
    # `Client` init); `0`/disabled is not allowed, since it would also disable dead-link
    # detection.
    decimal enquireLinkInterval = 60;
    # Transport security. Absent (the default) means the SMSC connection is plaintext
    # TCP, exactly as before this field existed. A `SecureSocket` yields a verified TLS
    # connection; an `InsecureSocket` yields a TLS connection with verification disabled
    # (dev/test only — see its docs). Validated at `Client` init the same way as a
    # `Listener`'s (see `validateSecureSocket`).
    (SecureSocket|InsecureSocket)? secureSocket = ();
|};

# An SMPP client bound to an SMSC as `TRANSMITTER`, `RECEIVER`, or `TRANSCEIVER`, for
# issuing the submit-family operations (`submit_sm`, `submit_multi`, `data_sm`,
# `query_sm`, `cancel_sm`, `replace_sm`) as an active outbound session.
#
# Unlike a `Listener`, a `Client` never receives inbound PDUs on this session's behalf —
# it exposes no callback for `deliver_sm`/`data_sm`; use a `Listener` for that. It also
# never automatically rebinds after an unexpected session drop: once the underlying
# session goes down, every remote method fails with `LINK_ABANDONED` from then on, and the
# only remedy is to `close` this `Client` and create a new one — see `FailureMode` for
# the retry guidance that applies to each failure it can return.
public isolated client class Client {

    # Connects and binds to the SMSC. This runs synchronously: by the time this returns
    # successfully, the session is bound and every remote method is ready to use.
    #
    # + host - SMSC host name or IP address
    # + systemId - the `system_id` (username) used to bind
    # + password - the password used to bind
    # + config - the rest of the connection/bind configuration
    # + return - an `Error` if `config` fails validation (see the field docs on
    #   `ClientConfig` for the exact bounds checked), or if the connect/bind itself fails
    #   (bad credentials, an oversized `systemId`/`password`/`systemType`, an unreachable
    #   host, or a bind the SMSC rejected)
    public isolated function init(string host, string systemId, string password,
            *ClientConfig config) returns Error? {
        check validateClientConfig(config);
        // Frozen, not the caller's own record: config is plain anydata, so cloneReadOnly()
        // produces a fully independent deep copy. Without this, the native layer would hold
        // the exact BMap the caller passed, and the caller mutating that same record after
        // construction (e.g. config.bindType = ...) would change what later calls observe -
        // even though the actual wire-level bind, negotiated once below, never changes.
        ClientConfig frozenConfig = config.cloneReadOnly();
        return self.externInit(host, systemId, password, frozenConfig, resolveTls(config.secureSocket));
    }

    isolated function externInit(string host, string systemId, string password, ClientConfig config,
            ResolvedTls? tls) returns Error? = @java:Method {
        'class: "io.ballerina.stdlib.smpp.client.NativeClient",
        name: "init"
    } external;

    # Submits a message to the SMSC (`submit_sm`) and waits (bounded by
    # `ClientConfig.transactionTimeout`) for the `submit_sm_resp`. Requires
    # `bindType: TRANSMITTER` or `TRANSCEIVER` (see `BindType`) — fails fast with
    # `INVALID_REQUEST` on a `RECEIVER`-bound `Client`.
    #
    # + sms - the message to send: a `TextSms` (this client encodes it) or a `BinarySms`
    #   (pre-encoded octets, with `udhi` for UDH-bearing payloads). Note `sms.sourceAddress`,
    #   if omitted, sends NO source address (a spec-legal NULL address) — unlike a
    #   `Listener` binding, a `Client` has no configured default source address to fall
    #   back to.
    # + return - the SMSC's `message_id` for the accepted message (correlate delivery
    #   receipts against it), or an `Error` whose detail carries `failureMode`,
    #   `commandStatus`, and `possiblySubmitted` — the last being the field to branch
    #   retry logic on, since `false` means a retry cannot duplicate the message (see
    #   `FailureMode` for the rest of the retry guidance)
    remote isolated function submit(OutboundSms sms) returns SubmitResult|Error = @java:Method {
        'class: "io.ballerina.stdlib.smpp.client.NativeClient",
        name: "submit"
    } external;

    # Submits one message to several destinations in a single `submit_multi` request.
    # Requires `bindType: TRANSMITTER` or `TRANSCEIVER`.
    #
    # + sms - the message to send. `sms.destinationAddress` is NOT used — the message goes to every
    #   address in `destinationAddresses` instead (each entry uses the `TON_INTERNATIONAL`/`NPI_ISDN`
    #   defaults, the same shorthand a plain `string` gets in `OutboundBase.destinationAddress`);
    #   every other field of `sms` (`sourceAddress`, `registeredDelivery`, `serviceType`,
    #   `validityPeriod`, and the payload) applies to the whole batch
    # + destinationAddresses - the recipient addresses for this batch; must contain at least one
    # + return - the SMSC's `message_id` for the batch plus the destinations it could not
    #   accept (`unsuccessfulAddresses`, empty when all were accepted), or an `Error` as
    #   documented on `submit`
    remote isolated function submitMulti(OutboundSms sms, string[] destinationAddresses) returns MultiSubmitResult|Error = @java:Method {
        'class: "io.ballerina.stdlib.smpp.client.NativeClient",
        name: "submitMulti"
    } external;

    # Submits a message via `data_sm` instead of `submit_sm`. Requires
    # `bindType: TRANSMITTER` or `TRANSCEIVER`.
    #
    # + data - the message to send. Note `data_sm` has no `validity_period` field on the
    #   wire — `data.validityPeriod`, if set, is accepted but not sent, since there is
    #   nothing for it to bind to. The payload always travels in the `message_payload` TLV
    #   (not `short_message`, which `data_sm` does not have), so it is capped at 65535
    #   octets rather than `submit`'s 254-octet `short_message` limit.
    # + return - as documented on `submit`
    remote isolated function submitData(OutboundSms data) returns SubmitResult|Error = @java:Method {
        'class: "io.ballerina.stdlib.smpp.client.NativeClient",
        name: "submitData"
    } external;

    # Queries the SMSC's current view of a previously submitted message's delivery state
    # (`query_sm`). Requires `bindType: TRANSMITTER` or `TRANSCEIVER`.
    #
    # + messageId - the SMSC's `message_id` for the message, as returned by `submit`/
    #   `submitMulti`/`submitData`
    # + sourceAddress - the message's original source address; empty is a spec-legal NULL
    #   address (a non-empty value gets the `TON_INTERNATIONAL`/`NPI_ISDN` defaults, the
    #   same shorthand a plain `string` gets in `OutboundBase.sourceAddress`)
    # + return - the SMSC's reported state, or an `Error` as documented on `submit`
    remote isolated function queryStatus(string messageId, string sourceAddress) returns QueryResult|Error = @java:Method {
        'class: "io.ballerina.stdlib.smpp.client.NativeClient",
        name: "queryStatus"
    } external;

    # Cancels a previously submitted, not-yet-delivered message (`cancel_sm`). Requires
    # `bindType: TRANSMITTER` or `TRANSCEIVER`.
    #
    # + messageId - the SMSC's `message_id` for the message to cancel
    # + sourceAddress - the message's original source address; same shorthand as
    #   `queryStatus`'s `sourceAddress`
    # + destinationAddress - the message's original destination address; required (unlike
    #   `sourceAddress`, `cancel_sm` has no NULL-address shorthand for the destination)
    # + return - `()` on success, or an `Error` as documented on `submit`
    remote isolated function cancel(string messageId, string sourceAddress, string destinationAddress) returns Error? = @java:Method {
        'class: "io.ballerina.stdlib.smpp.client.NativeClient",
        name: "cancel"
    } external;

    # Replaces the text/attributes of a previously submitted, not-yet-delivered message
    # (`replace_sm`). Requires `bindType: TRANSMITTER` or `TRANSCEIVER`.
    #
    # + messageId - the SMSC's `message_id` for the message to replace
    # + sourceAddress - the message's original source address; same shorthand as
    #   `queryStatus`'s `sourceAddress`
    # + sms - the replacement content. `replace_sm` carries no `destination_addr`,
    #   `service_type`, `data_coding`, or `esm_class` field on the wire, so `sms.destinationAddress`
    #   (structurally required by `OutboundSms`, but unused here), `sms.serviceType`, and
    #   `BinarySms`'s `dataCoding`/`udhi` are accepted but not sent — only the message body
    #   bytes, `registeredDelivery`, and `validityPeriod` carry over to `replace_sm`
    # + return - `()` on success, or an `Error` as documented on `submit`
    remote isolated function replace(string messageId, string sourceAddress, OutboundSms sms) returns Error? = @java:Method {
        'class: "io.ballerina.stdlib.smpp.client.NativeClient",
        name: "replace"
    } external;

    # Unbinds and closes the SMSC session. Bounded (~4s worst case) by a force-close
    # watchdog against an unresponsive peer, the same defensive pattern a `Listener` uses
    # for its own stop paths — even a black-holed SMSC cannot make this hang indefinitely.
    # There is no drain: an in-flight submit-family call is not woken by the close, and if
    # it is already parked awaiting its response it completes only at
    # `transactionTimeout`, with `LINK_DOWN`/`LINK_ABANDONED` and `possiblySubmitted: true`
    # (the response may have been in flight when the socket closed). Idempotent: closing
    # an already-closed (or never-successfully-initialized) `Client` is a no-op. A closed
    # `Client` cannot be reused — create a new one.
    #
    # + return - an `Error` if the unbind itself fails; `()` otherwise
    public isolated function close() returns Error? = @java:Method {
        'class: "io.ballerina.stdlib.smpp.client.NativeClient",
        name: "close"
    } external;
}

# Validates `config` against the bounds documented on `ClientConfig`, before any native
# connect/bind is attempted.
#
# + config - the configuration to validate
# + return - an `Error` describing the first violated constraint, or `()` if valid
isolated function validateClientConfig(ClientConfig config) returns Error? {
    if config.port < 1 || config.port > 65535 {
        return error Error(string `port must be between 1 and 65535, got ${config.port}`);
    }
    if config.bindTimeout < 1d {
        // A sub-second bind timeout would time out nearly every real TLS+bind handshake.
        return error Error(string `bindTimeout must be at least 1 second, got ${config.bindTimeout}`);
    }
    if config.bindTimeout > 300d {
        // Guards against a seconds-vs-milliseconds unit mixup (this field is SECONDS).
        return error Error(string `bindTimeout must not exceed 300 seconds - note this field is in SECONDS, not milliseconds, got ${config.bindTimeout}`);
    }
    if config.transactionTimeout < 1d {
        // Sub-second would time out responses the SMSC is merely slow to send. This same
        // value also bounds enquire_link housekeeping (see the field doc), so a tiny
        // value would flap an otherwise healthy link too.
        return error Error(string `transactionTimeout must be at least 1 second, got ${config.transactionTimeout}`);
    }
    if config.transactionTimeout > 300d {
        return error Error(string `transactionTimeout must not exceed 300 seconds - note this field is in SECONDS, not milliseconds, got ${config.transactionTimeout}`);
    }
    if config.enquireLinkInterval < 5d {
        // 0/negative would disable dead-link detection; sub-5s risks colliding with the
        // internal response window and floods the SMSC with keepalives.
        return error Error(string `enquireLinkInterval must be at least 5 seconds, got ${config.enquireLinkInterval}`);
    }
    if config.enquireLinkInterval > 3600d {
        return error Error(string `enquireLinkInterval must not exceed 3600 seconds - note this field is in SECONDS, not milliseconds, got ${config.enquireLinkInterval}`);
    }
    SecureSocket|InsecureSocket? secureSocket = config.secureSocket;
    if secureSocket is SecureSocket {
        // `validateSecureSocket` (types.bal) is declared `returns error?`, generic - wrap
        // its failure into this module's `Error` rather than propagating it directly, so
        // `Client.init`'s `Error?` contract holds even though the shared helper's own
        // signature is widened. (No `cause =` here: `Error`'s detail type `ErrorDetail`
        // declares individual fields, and a described detail type does not accept the
        // generic `cause` argument alongside them.)
        error? err = validateSecureSocket(secureSocket);
        if err is error {
            return error Error(err.message());
        }
    }
    // An InsecureSocket needs no validation (its one field admits only `true`); the loud
    // warning for it is logged at resolve time, once per Client init.
}
