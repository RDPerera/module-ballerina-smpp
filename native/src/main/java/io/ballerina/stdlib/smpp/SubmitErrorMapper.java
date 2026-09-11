/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.stdlib.smpp;

import io.ballerina.runtime.api.values.BError;
import org.jsmpp.GenericNackResponseException;
import org.jsmpp.InvalidResponseException;
import org.jsmpp.PDUException;
import org.jsmpp.extra.NegativeResponseException;
import org.jsmpp.extra.ResponseTimeoutException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Translates a jsmpp exception (or a local pre-send validation failure) raised by a
 * submit-family operation — {@code submit_sm}, {@code submit_multi}, {@code data_sm},
 * {@code query_sm}, {@code cancel_sm}, {@code replace_sm} — into this module's
 * {@code FailureMode}/{@code ErrorDetail}/{@code Error} (see types.bal). Used identically
 * by the {@code Client}'s submit-family operations and by the {@code Listener}'s
 * {@code Caller.submit}, so a failure is classified the same way — and a caller branches
 * retry logic on one vocabulary — regardless of which side of the connector sent it.
 *
 * <p>Pure/static: takes and returns plain Java values (see {@link MappedFailure}) so JUnit
 * reaches {@link #mapSubmitFailure(Throwable)} without a Ballerina runtime or a jsmpp
 * session — mirroring the reference implementation's {@code SubmitErrorMappingTest}. Only
 * {@link #toError(MappedFailure)} touches Ballerina values, and is the one method actual
 * native call sites use to turn a caught {@link Throwable} into a returned {@code BError}.
 */
public final class SubmitErrorMapper {

    private SubmitErrorMapper() {
    }

    /**
     * A locally-refused request: nothing reached the wire. The message must never echo
     * user-supplied data (an MSISDN, a message body) — see the field-and-length-only
     * wording used at every throw site in the {@code client}/{@code listener} native
     * packages.
     */
    public static final class InvalidRequest extends Exception {
        public InvalidRequest(String message) {
            super(message);
        }
    }

    /** Pure mapping result for a failed submit-family operation. */
    public static final class MappedFailure {
        public final String message;
        public final String failureMode;
        public final Integer commandStatus;
        /**
         * Whether the message may already have reached the SMSC. The semantics are "can a
         * retry duplicate?", NOT "did it reach the wire": a REJECTED submit WAS written,
         * but the SMSC definitively refused it, so a retry cannot duplicate - false.
         */
        public final boolean possiblySubmitted;

        MappedFailure(String message, String failureMode, Integer commandStatus,
                boolean possiblySubmitted) {
            this.message = message;
            this.failureMode = failureMode;
            this.commandStatus = commandStatus;
            this.possiblySubmitted = possiblySubmitted;
        }
    }

    /**
     * As {@link #mapSubmitFailure(Throwable, boolean)} with {@code selfClosed} false - the
     * common case for the {@code Client}, which (today) never closes a session out from
     * under one of its own in-flight submits the way a {@code Listener} rebind/stop can.
     */
    public static MappedFailure mapSubmitFailure(Throwable t) {
        return mapSubmitFailure(t, false);
    }

    /**
     * Maps every failure the submit-family path can produce onto a {@code FailureMode}, in
     * an order that respects the jsmpp exception hierarchy (verified against 3.0.2):
     * {@code GenericNackResponseException extends InvalidResponseException} and carries a
     * real command_status, so it must match first; {@code PDUStringException extends
     * PDUException}. Terminates in a {@code Throwable} branch because two known escapes
     * are unchecked — an internal response-cast can {@code ClassCastException}, and
     * jsmpp's default PDU sender has several unguarded derefs — and
     * {@code QueueException}/{@code QueueMaxException} are unchecked with no checked
     * branch of their own.
     *
     * @param t the failure to classify
     * @param selfClosed whether THIS CONNECTOR closed the session (a stop, or the reclaim
     *     of an abandoned dead link) — consulted ONLY in the response-timeout branch.
     *     jsmpp has no fail-pending-on-close: a submit already parked awaiting its
     *     {@code submit_sm_resp} is not woken when the socket closes under it, runs its
     *     full {@code transactionTimeout}, and would otherwise masquerade as an SMSC
     *     timeout whose docs point the operator at delivery receipts that can never
     *     arrive on a stopped listener. Keyed off an explicit connector-set marker, NEVER
     *     off a generic "session usable" flag: that flag also flips on genuine drops,
     *     where TIMEOUT_DELIVERY_UNKNOWN is the truthful verdict.
     * @return the mapped failure
     */
    public static MappedFailure mapSubmitFailure(Throwable t, boolean selfClosed) {
        String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        if (t instanceof InvalidRequest) {
            // Local refusal: provably never wrote.
            return new MappedFailure(msg, "INVALID_REQUEST", null, false);
        }
        if (t instanceof NegativeResponseException e) {
            // Written AND definitively refused - a retry cannot duplicate.
            return new MappedFailure("SMSC rejected the request: " + msg, "REJECTED",
                    e.getCommandStatus(), false);
        }
        if (t instanceof GenericNackResponseException e) {
            // Before InvalidResponseException: it is a subclass, and unlike its parent it
            // carries the SMSC's actual command_status - a generic_nack IS a rejection.
            return new MappedFailure("SMSC answered generic_nack: " + msg, "REJECTED",
                    e.getCommandStatus(), false);
        }
        if (t instanceof ResponseTimeoutException) {
            if (selfClosed) {
                // Names the connector's own close, not a network fault or SMSC
                // slowness - and not "stop" alone, because the abandoned-dead-link
                // reclaim sets the same marker.
                return new MappedFailure("this connector closed the session (a stop, or the "
                        + "reclaim of a dead link) while the request was awaiting its response: "
                        + msg + " (the SMSC may have accepted it before the close - "
                        + "retrying may duplicate it)", "LINK_DOWN", null, true);
            }
            return new MappedFailure("no response within transactionTimeout: " + msg
                    + " (the SMSC may still have accepted the request - retrying may duplicate it)",
                    "TIMEOUT_DELIVERY_UNKNOWN", null, true);
        }
        if (t instanceof InvalidResponseException) {
            // Sent; the response was unusable - acceptance unknown.
            return new MappedFailure("invalid response PDU: " + msg, "PROTOCOL_ERROR", null, true);
        }
        if (t instanceof PDUException) {
            // Includes PDUStringException - jsmpp's own validator, which each operation's
            // local pre-checks make a never-fires backstop. Thrown while COMPOSING, pre-write.
            return new MappedFailure("jsmpp rejected the request PDU: " + msg, "INVALID_REQUEST",
                    null, false);
        }
        if (t instanceof IOException) {
            // jsmpp's ensureTransmittable throws a bare IOException naming the session id
            // and its INTERNAL state ("Cannot submit_sm while session <id> in state
            // CLOSED"). Lifecycle pre-checks exist precisely to answer before that
            // happens - but they cannot close the sliver between the liveness check and
            // the send, so the same leak reaches the user by the racy path. Substitute
            // the connector's own wording there: jsmpp state names are not part of this
            // connector's published vocabulary, and nothing actionable is lost (the
            // caller already gets LINK_DOWN + possiblySubmitted).
            if (msg.contains(" in state ")) {
                return new MappedFailure("the SMSC session went down while the request was in "
                        + "flight (outcome unknown; the listener is rebinding if the link "
                        + "dropped)", "LINK_DOWN", null, true);
            }
            // Mid-flight death: octets may have been flushed before the failure.
            return new MappedFailure("connection failed mid-request: " + msg
                    + " (outcome unknown; the listener is rebinding if the link dropped)",
                    "LINK_DOWN", null, true);
        }
        return new MappedFailure("unexpected failure in the submit path: "
                + t.getClass().getSimpleName() + ": " + msg, "PROTOCOL_ERROR", null, true);
    }

    /**
     * Wraps a {@link MappedFailure} into the module's {@code smpp:Error}, via
     * {@link ModuleUtils#createError(String, Map)}. The one place a {@link MappedFailure}
     * becomes a Ballerina value — every native submit-family extern function should end
     * its catch block with {@code return SubmitErrorMapper.toError(mapSubmitFailure(e))}
     * (or the {@code selfClosed}-aware overload).
     *
     * @param f the mapped failure
     * @return a populated {@code smpp:Error}
     */
    public static BError toError(MappedFailure f) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("failureMode", f.failureMode);
        if (f.commandStatus != null) {
            detail.put("commandStatus", (long) (int) f.commandStatus);
        }
        detail.put("possiblySubmitted", f.possiblySubmitted);
        return ModuleUtils.createError(f.message, detail);
    }
}
