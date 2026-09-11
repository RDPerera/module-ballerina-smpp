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

# Submits messages (`submit_sm`) on the same SMSC session the `Listener` receives on.
#
# Obtained by declaring it as a parameter of a remote method — never constructed:
#
# ```ballerina
# remote function onDeliverSm(smpp:Sms sms, smpp:Caller caller) returns error? {
#     smpp:SubmitResult r = check caller->submit({
#         destinationAddress: sms.sourceAddress,
#         shortMessage: "reply text"
#     });
# }
# ```
#
# Declaring the parameter is opt-in: a service that only declares `smpp:Sms` keeps
# working unchanged. The parameter is matched by TYPE, not position — `(Sms, Caller)` and
# `(Caller, Sms)` are both accepted (unlike `mqtt`, which enforces caller-last; like
# `ftp`, which binds by type — and stricter than both: an optional-typed `smpp:Caller?`
# parameter or a rest parameter is rejected at attach, not silently left `()`).
#
# One `Caller` exists per listener and stays valid across rebinds: every submit
# resolves the listener's *current* session, so a `Caller` captured before a drop
# submits on the fresh session after the rebind. Requires `bindType: TRANSCEIVER` — a
# `RECEIVER` bind cannot transmit, and `submit` on one fails fast with a clear error
# naming the fix.
#
# Not `distinct`, matching the other stdlib Callers, so a future `smpp:Client` can
# satisfy the same shape.
public isolated client class Caller {

    // Module-private on purpose: user code cannot `new smpp:Caller()`. The runtime
    // constructs the one instance per listener at native init and hands it to remote
    // methods that declare the parameter.
    isolated function init() {
    }

    # Submits a message to the SMSC and waits (bounded by
    # `ListenerConfig.transactionTimeout`) for the `submit_sm_resp`.
    #
    # In `SYNC` response mode this blocks one of the `maxConcurrentDispatch` dispatch
    # slots for the round trip — budget slots accordingly when handlers reply inline,
    # or the SMSC sees `ESME_RTHROTTLED` on concurrent inbound traffic.
    #
    # A slow inline reply in `SYNC` also delays the `deliver_sm_resp` past the SMSC's
    # own transaction timer, making it REDELIVER an MO you already answered - a
    # duplicate MT. **Prefer `responseMode: ASYNC` for reply-style services**, and carry
    # your own idempotency key (e.g. dedupe on the inbound source+text+timestamp) where
    # duplicates are costly.
    #
    # Two limits worth knowing before sizing anything against this: the connector applies
    # no rate limiting of its own (carrier throttling policy is yours to respect), and a
    # submit already awaiting its response is **not** woken if the link dies or the
    # listener stops — the underlying library has no fail-pending-on-close, so it
    # completes only at `transactionTimeout`, holding its dispatch slot in `SYNC` mode
    # for that whole time.
    #
    # + sms - the message to send: a `TextSms` (the connector encodes it) or a
    #   `BinarySms` (pre-encoded octets, with `udhi` for UDH-bearing payloads)
    # + return - the SMSC's `message_id` for the accepted message (correlate delivery
    #   receipts against it — see `Sms.receiptedMessageId`), or an `Error` whose detail
    #   carries `failureMode`, `commandStatus`, and `possiblySubmitted` — the last being
    #   the field to branch retry logic on, since `false` means a retry cannot duplicate
    #   the message (see `FailureMode` for the rest of the retry guidance)
    remote isolated function submit(OutboundSms sms) returns SubmitResult|Error {
        return self.externSubmit(sms);
    }

    isolated function externSubmit(OutboundSms sms) returns SubmitResult|Error = @java:Method {
        'class: "io.ballerina.stdlib.smpp.listener.NativeCaller",
        name: "submit"
    } external;
}
