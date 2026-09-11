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
import ballerina/log;

# Controls the timing of the `deliver_sm_resp`/`data_sm_resp` sent back to the SMSC.
public enum ResponseMode {
    # Waits for the service's remote method to return before responding. A returned
    # error becomes a negative `command_status` — `ESME_RX_T_APPN` (the SMPP v3.4
    # receiver "temporary app error" code) — telling the SMSC the message was not
    # handled; most SMSCs treat this as a signal to redeliver, so a transient failure
    # doesn't lose the message. (SMPP v3.4 does not itself mandate the SMSC's reaction
    # to a negative response.) A handler that fails *permanently* will therefore keep
    # being redelivered until the SMSC's own retry/validity limit — return successfully,
    # or dead-letter such messages yourself, rather than always erroring. Matches jsmpp's
    # documented listener contract, and bounds concurrency to `maxConcurrentDispatch`.
    SYNC,
    # Sends `command_status = ESME_ROK` immediately, without waiting for the service —
    # maximizing throughput at the cost of never reflecting a later service failure back
    # to the SMSC (the positive ack has already gone out); such a failure is instead
    # logged via `ballerina/log` at error level. `maxConcurrentDispatch` still bounds
    # concurrency here: it caps how many service invocations run at once, and PDUs
    # arriving beyond that limit are answered with `ESME_RTHROTTLED` rather than spawning
    # unbounded work.
    ASYNC
}

# Configuration used to connect and bind the `Listener` to an SMSC.
public type ListenerConfig record {|
    # SMSC host name or IP address.
    string host;
    # SMSC port. Defaults to the common SMPP port `2775`. Must be 1-65535 (validated
    # at `Listener` init).
    int port = 2775;
    # The `system_id` (username) used to bind.
    string systemId;
    # The password used to bind.
    string password;
    # The optional `system_type`. Empty by default.
    string systemType = "";
    # The bind mode. Defaults to `RECEIVER`. Typed as `ListenerBindType`
    # (`RECEIVER|TRANSCEIVER`) rather than the full `BindType` — a transmitter-bound
    # session structurally cannot receive `DELIVER_SM`/`DATA_SM` (see `ListenerBindType`),
    # so configuring one here is rejected at compile time. `TRANSCEIVER` additionally
    # allows submitting on the same session via the attached service's `Caller`.
    ListenerBindType bindType = RECEIVER;
    # Maximum number of inbound PDUs (`DELIVER_SM`/`DATA_SM`) dispatched to the attached
    # service concurrently — the concurrency limit on your service, in both `SYNC` and
    # `ASYNC` mode. When the SMSC sends PDUs faster than this limit drains, the excess is
    # answered immediately with `ESME_RTHROTTLED` so the SMSC backs off and retains the
    # message (SMPP is at-least-once — a NACK is not a drop). Inbound throughput is bounded
    # by design, not best-effort.
    #
    # A busy service does not provoke the SMSC into dropping the link: the connector keeps
    # a PDU-processor thread in reserve beyond this limit (in `SYNC` mode, where handlers
    # occupy those threads; in `ASYNC` mode handlers run on virtual threads and never
    # occupy them at all), so `enquire_link` is answered while every dispatch slot is busy.
    # Stated precisely rather than as a guarantee: that reserve also carries every
    # `submit_sm_resp`, so on a reply-heavy service keepalive latency and submit completion
    # compete for the same thread. It is sized for that (`maxConcurrentDispatch + 1`), and
    # a stuck outbound WRITE would stall both regardless — no reserve can protect against
    # that. See `docs/architecture.md`.
    #
    # This ceiling is **per listener, not per process**: N listeners at 1024 in `SYNC` mode
    # is a legal configuration that would attempt ~N×1024 platform threads. Must be 1-1024
    # (validated at `Listener` init).
    int maxConcurrentDispatch = 3;
    # Controls when the `deliver_sm_resp`/`data_sm_resp` is sent back to the SMSC
    # relative to the attached service's processing of the PDU. Defaults to `SYNC`.
    ResponseMode responseMode = SYNC;
    # When `true`, a message with `data_coding` `0x00` (the SMSC default alphabet) is decoded
    # as **unpacked** GSM 03.38 (the 7-bit default alphabet plus its extension table) instead
    # of the default UTF-8 fallback. Opt-in and off by default, so it never changes decoding
    # for anyone relying on the existing UTF-8 behavior; enable it only against an SMSC that
    # actually sends the GSM 7-bit alphabet with one septet per octet (packed 7-bit is not
    # handled). Other `data_coding` values (IA5, Latin-1, UCS-2, …) are unaffected. The raw
    # `data_coding` is always available on `Sms.properties` for services that must decode a
    # different scheme themselves.
    boolean decodeGsm7 = false;
    # Maximum time `gracefulStop` waits for in-flight work to finish before unbinding, in
    # seconds. The drain covers dispatches to the attached service AND in-flight
    # `Caller.submit` calls (including from non-handler code holding a `Caller`); submits
    # stay legal for the whole drain window - a reply-style service's in-flight replies
    # complete rather than being dropped on shutdown. After the drain, `gracefulStop`
    # also runs a ≤2s reservation sweep (correctness, not grace: it closes the race with
    # a submit that reserved its slot just before the cutoff) - so `0` here means "no
    # drain wait", with the sweep still applying. `immediateStop` skips both. Either
    # stop's unbind/close is itself bounded (~4s worst case) by a force-close watchdog,
    # even against an unresponsive peer. One caveat for both flavours: a submit already
    # awaiting its `submit_sm_resp` when the close lands is NOT woken (the underlying
    # library has no fail-pending-on-close) - mid-write it fails immediately with
    # `LINK_DOWN`, but once parked it completes only at `transactionTimeout`, with
    # `LINK_DOWN` and `possiblySubmitted: true`, so a submitting strand can outlive the
    # stop by up to that long. Must not be negative (validated at `Listener` init).
    decimal gracefulStopTimeout = 30;
    # Controls automatic rebinding after an unexpected session drop. Defaults to retrying
    # indefinitely with exponential backoff; set `maxRebindAttempts: 0` to disable.
    RebindPolicy rebindPolicy = {};
    # How often the connector sends an `enquire_link` to the SMSC when the session is
    # otherwise idle, in seconds. This is the connector's own keepalive/liveness probe:
    # it keeps NAT/firewall state alive and is how the connector detects a silently dead
    # SMSC (an unanswered probe fails the link and drives `rebindPolicy`). It does NOT
    # control how long the SMSC waits before dropping the connector — that is the SMSC's
    # own policy; see `maxConcurrentDispatch` for why a busy service no longer trips it.
    # This field is in SECONDS (jsmpp's underlying knob is milliseconds). Must be 5-3600
    # (validated at `Listener` init); `0`/disabled is not allowed, since it would also
    # disable dead-link detection.
    decimal enquireLinkInterval = 60;
    # Maximum time the connect-and-bind handshake may take, in seconds — applied to the
    # initial ``'start()`` and to every automatic rebind attempt. It bounds the TCP connect
    # and the bind-response wait *separately*, so a fully stalled attempt (a black-holed
    # host that also never answers) can take up to ~2x this value. The rebind loop is
    # single-threaded, so this also caps how long one stalled attempt (e.g. a half-open
    # SMSC that accepts the TCP connection but never answers the bind) blocks the next
    # attempt. This field is in SECONDS. Must be 1-300 (validated at `Listener` init).
    decimal bindTimeout = 60;
    # How long a `Caller.submit` waits for the SMSC's `submit_sm_resp`, in seconds.
    #
    # This bounds ONLY the requests this connector issues on your behalf. The session's
    # internal housekeeping — the `unbind_resp` wait during `gracefulStop`/`immediateStop`,
    # the `enquire_link_resp` wait that detects a silently dead link, and the reader
    # thread's exit drain — is bounded separately at a short internal timer (~2s, jsmpp's
    # own historical default for exactly those paths), so raising this value does NOT slow
    # stops or dead-link detection. Worst-case `gracefulStop` ≈ `gracefulStopTimeout` +
    # ~2s (sweep) + ~4s (bounded close); `immediateStop` ≈ ~4s; silent-peer detection
    # stays ≈ `enquireLinkInterval` + 2s, regardless of this setting. The one exception: a
    # submit already awaiting its response when a stop closes the session completes only
    # at THIS timeout (with `LINK_DOWN`, `possiblySubmitted: true`) — the single place
    # this value can stretch past a stop.
    #
    # The default is 30s rather than jsmpp's own 2s. Two seconds is too short for a
    # `submit_sm` under load, and a timed-out submit is the worst outcome the send path
    # has: SMPP gives no way to tell "the SMSC never got it" from "the SMSC got it and the
    # response was slow", so retrying may duplicate a message the subscriber already
    # received (`FailureMode.TIMEOUT_DELIVERY_UNKNOWN`). Prefer waiting to guessing.
    #
    # This field is in SECONDS (jsmpp's underlying knob is milliseconds). Must be 1-300
    # (validated at `Listener` init).
    decimal transactionTimeout = 30;
    # Transport security. Absent (the default) means the SMSC connection is plaintext
    # TCP. A `SecureSocket` yields a verified TLS connection; an `InsecureSocket` yields a
    # TLS connection with verification disabled (dev/test only — see its docs). Network-
    # terminated TLS in front of the SMSC remains the recommended production topology
    # where you control that boundary; this field is for the in-band case where you don't.
    SecureSocket|InsecureSocket secureSocket?;
|};

# The SMPP listener. Binds to an SMSC as an SMPP client (`bind_receiver`/
# `bind_transceiver`, per `ListenerConfig.bindType`) and dispatches inbound
# `DELIVER_SM`/`DATA_SM` PDUs — mobile-originated messages and delivery receipts — to an
# attached service.
#
# A user attaches a service with the `service ... on listener { }` syntax and implements
# one or more of:
# - `remote function onDeliverSm(smpp:Sms sms) returns error?`
# - `remote function onDataSm(smpp:Sms sms) returns error?`
# - `remote function onError(error err) returns error?` — notified on an unexpected
#   session drop; see `RebindPolicy`.
#
# The listener enforces an explicit one-way lifecycle
# (`INIT → STARTING → STARTED → STOPPING → STOPPED`): a second ``'start()`` on a running
# listener is rejected, and a stopped listener cannot be restarted — create a new
# `Listener` instead. The one
# exception: a *failed* ``'start()`` (bind rejected, host unreachable) reverts the listener
# to startable, since nothing was installed. Stops are idempotent. One service per
# listener: attaching a second service is rejected (`detach` the first to swap).
public isolated class Listener {

    # Creates a new SMPP listener for the given SMSC connection configuration. No network
    # activity happens at this point — the connect-and-bind handshake runs at `start()`.
    #
    # + config - the connection/bind configuration
    # + return - an `Error` if `config` fails validation (see the field docs on
    #   `ListenerConfig`/`RebindPolicy` for the exact bounds checked), or if the
    #   native listener setup fails
    public isolated function init(ListenerConfig config) returns error? {
        check validateConfig(config);
        // Frozen, not the caller's own record: config is plain anydata, so cloneReadOnly()
        // produces a fully independent deep copy. Without this, the native layer would hold
        // the exact BMap the caller passed - and unlike a Client's one-time bind, a Listener
        // re-reads this config throughout its whole life (every rebind attempt, every
        // dispatch), so the caller mutating that same record after construction (e.g.
        // config.port = ...) would silently change where/how future rebinds connect.
        ListenerConfig frozenConfig = config.cloneReadOnly();
        check self.externInit(frozenConfig, resolveTls(config.secureSocket));
    }

    isolated function externInit(ListenerConfig config, ResolvedTls? tls) returns error? = @java:Method {
        'class: "io.ballerina.stdlib.smpp.listener.NativeListener",
        name: "initListener"
    } external;

    # Attaches a service to this listener. Invoked automatically by the runtime
    # for `service ... on listener { }` declarations. One service per listener:
    # attaching a second service is rejected — `detach` the first one to swap.
    #
    # + s - the service to attach
    # + name - unused; part of the standard listener contract
    # + return - an `Error` if `s` implements none of `onDeliverSm`, `onDataSm`,
    #   `onError`, or if a service is already attached
    public isolated function attach(Service s, string[]|string? name = ()) returns error? = @java:Method {
        'class: "io.ballerina.stdlib.smpp.listener.NativeListener",
        name: "attach"
    } external;

    # Detaches a previously attached service. A no-op if `s` is not the service
    # currently attached to this listener.
    #
    # + s - the service to detach
    # + return - never returns an error today; typed `error?` per the listener contract
    public isolated function detach(Service s) returns error? = @java:Method {
        'class: "io.ballerina.stdlib.smpp.listener.NativeListener",
        name: "detach"
    } external;

    # Connects and binds to the SMSC, and begins receiving PDUs. Calling this on an
    # already-started listener is rejected; so is calling it on a stopped listener
    # (a stopped listener cannot be restarted — create a new one). A *failed* start
    # (e.g. bind rejected, host unreachable) leaves the listener startable again.
    #
    # + return - an `Error` if already started, stopped, or the connect/bind fails
    public isolated function 'start() returns error? = @java:Method {
        'class: "io.ballerina.stdlib.smpp.listener.NativeListener",
        name: "start"
    } external;

    # Cancels any pending rebind attempt, waits up to `ListenerConfig.gracefulStopTimeout`
    # for in-flight dispatches (including `onError` notifications) and submits to finish,
    # runs a ≤2s reservation sweep, then unbinds and closes the SMSC session — the
    # unbind/close bounded at ~4s worst case by a force-close watchdog, even against an
    # unresponsive peer. Whole-call worst case ≈ `gracefulStopTimeout` + ~2s + ~4s.
    # Idempotent: stopping an already-stopped (or never-started) listener is a no-op.
    # A stopped listener cannot be restarted.
    #
    # + return - an `Error` if the unbind/close itself fails; `()` otherwise
    public isolated function gracefulStop() returns error? = @java:Method {
        'class: "io.ballerina.stdlib.smpp.listener.NativeListener",
        name: "gracefulStop"
    } external;

    # Cancels any pending rebind attempt and immediately unbinds and closes the SMSC
    # session, without waiting for in-flight dispatches or submits (no drain, no sweep);
    # the unbind/close is bounded at ~4s worst case by a force-close watchdog. An
    # in-flight submit is NOT woken by the close: mid-write it fails immediately with
    # `LINK_DOWN`; already awaiting its `submit_sm_resp`, it completes only at
    # `transactionTimeout`, with `LINK_DOWN` and `possiblySubmitted: true`. Idempotent:
    # stopping an already-stopped (or never-started) listener is a no-op. A stopped
    # listener cannot be restarted.
    #
    # + return - an `Error` if the unbind/close itself fails; `()` otherwise
    public isolated function immediateStop() returns error? = @java:Method {
        'class: "io.ballerina.stdlib.smpp.listener.NativeListener",
        name: "immediateStop"
    } external;
}

# The SMPP service contract. Implemented by the user with at least one of the supported
# remote methods (`onDeliverSm`, `onDataSm`, `onError`). Enforcement of the available
# methods is delegated to the native dispatcher at runtime.
public type Service distinct service object {
};

# Validates `config` against the bounds documented on `ListenerConfig`/`RebindPolicy`,
# before any native listener setup happens.
#
# + config - the configuration to validate
# + return - an `Error` describing the first violated constraint, or `()` if valid
isolated function validateConfig(ListenerConfig config) returns error? {
    if config.port < 1 || config.port > 65535 {
        return error Error(string `port must be between 1 and 65535, got ${config.port}`);
    }
    if config.maxConcurrentDispatch < 1 {
        return error Error(string `maxConcurrentDispatch must be at least 1, got ${config.maxConcurrentDispatch}`);
    }
    if config.maxConcurrentDispatch > 1024 {
        // In SYNC each unit is one OS worker thread (plus the keepalive reserve), so an
        // accidental huge value would exhaust threads; cap it at a generous ceiling.
        return error Error(string `maxConcurrentDispatch must not exceed 1024, got ${config.maxConcurrentDispatch}`);
    }
    if config.enquireLinkInterval < 5d {
        // 0/negative would disable dead-link detection (jsmpp maps it to an infinite
        // socket read timeout); sub-5s risks colliding with the internal response window
        // and floods the SMSC with keepalives.
        return error Error(string `enquireLinkInterval must be at least 5 seconds, got ${config.enquireLinkInterval}`);
    }
    if config.enquireLinkInterval > 3600d {
        // Upper bound doubles as a unit-confusion guard: this field is SECONDS, while
        // jsmpp's knob is milliseconds - someone typing 60000 would otherwise get ~16h.
        return error Error(string `enquireLinkInterval must not exceed 3600 seconds - note this field is in SECONDS, not milliseconds, got ${config.enquireLinkInterval}`);
    }
    if config.bindTimeout < 1d {
        // A sub-second bind timeout would time out nearly every real TLS+bind handshake.
        return error Error(string `bindTimeout must be at least 1 second, got ${config.bindTimeout}`);
    }
    if config.bindTimeout > 300d {
        // Same seconds-vs-milliseconds unit-confusion guard as enquireLinkInterval.
        return error Error(string `bindTimeout must not exceed 300 seconds - note this field is in SECONDS, not milliseconds, got ${config.bindTimeout}`);
    }
    if config.transactionTimeout < 1d {
        // Sub-second would time out responses the SMSC is merely slow to send. This same
        // value also bounds enquire_link, so a tiny value would flap an otherwise healthy
        // link as well as breaking submits.
        return error Error(string `transactionTimeout must be at least 1 second, got ${config.transactionTimeout}`);
    }
    if config.transactionTimeout > 300d {
        // Same seconds-vs-milliseconds unit-confusion guard as enquireLinkInterval.
        return error Error(string `transactionTimeout must not exceed 300 seconds - note this field is in SECONDS, not milliseconds, got ${config.transactionTimeout}`);
    }
    if config.gracefulStopTimeout < 0d {
        return error Error(string `gracefulStopTimeout must not be negative, got ${config.gracefulStopTimeout}`);
    }
    check validateRebindPolicy(config.rebindPolicy);
    SecureSocket|InsecureSocket? secureSocket = config.secureSocket;
    if secureSocket is SecureSocket {
        check validateSecureSocket(secureSocket);
    }
    // An InsecureSocket needs no validation (its one field admits only `true`); the loud
    // warning for it is logged at resolve time, once per listener init.
}

# Validates a `RebindPolicy` against its documented bounds.
#
# + policy - the policy to validate
# + return - an `Error` describing the first violated constraint, or `()` if valid
isolated function validateRebindPolicy(RebindPolicy policy) returns error? {
    if policy.initialRebindDelay < 0d {
        return error Error(string `rebindPolicy.initialRebindDelay must not be negative, got ${policy.initialRebindDelay}`);
    }
    if policy.maxRebindDelay < policy.initialRebindDelay {
        return error Error(string `rebindPolicy.maxRebindDelay (${policy.maxRebindDelay}) must be >= initialRebindDelay (${policy.initialRebindDelay})`);
    }
    if policy.backOffMultiplier < 1d {
        return error Error(string `rebindPolicy.backOffMultiplier must be at least 1, got ${policy.backOffMultiplier}`);
    }
    if policy.maxRebindAttempts < -1 {
        // Only -1 means "infinite"; other negatives are almost certainly typos (e.g. -3
        // intending 3) and would otherwise silently behave as infinite too.
        return error Error(string `rebindPolicy.maxRebindAttempts must be -1 (infinite), 0 (disabled), or positive, got ${policy.maxRebindAttempts}`);
    }
}

# Routes a native-layer dispatch error through `ballerina/log`. Invoked from the Java
# `Dispatcher` via `Runtime.callFunction` as the fallback when a service has no `onError`
# method, when an `onError` handler itself errors, for an ASYNC handler failure that can't
# be reflected back to the SMSC, and for the connector's own operational warnings
# (throttling, PDU types with no handler) — so these land in the application's log rather
# than on stderr.
#
# + message - a description of where/why the error occurred
# + err - the error to log alongside `message`
isolated function logDispatchError(string message, error err) {
    log:printError(message, 'error = err);
}
