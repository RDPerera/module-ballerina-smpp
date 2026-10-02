/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org).
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

package io.ballerina.stdlib.smpp.listener;

import io.ballerina.runtime.api.Module;
import io.ballerina.runtime.api.Runtime;
import io.ballerina.runtime.api.concurrent.StrandMetadata;
import io.ballerina.runtime.api.types.NetworkObjectType;
import io.ballerina.runtime.api.types.ObjectType;
import io.ballerina.runtime.api.types.Parameter;
import io.ballerina.runtime.api.types.RemoteMethodType;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.utils.TypeUtils;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.stdlib.smpp.ModuleUtils;
import io.ballerina.stdlib.smpp.SmsMapper;
import org.jsmpp.PDUStringException;
import org.jsmpp.SMPPConstant;
import org.jsmpp.bean.AbstractSmCommand;
import org.jsmpp.bean.AlertNotification;
import org.jsmpp.bean.DataSm;
import org.jsmpp.bean.DeliverSm;
import org.jsmpp.bean.OptionalParameter;
import org.jsmpp.extra.ProcessRequestException;
import org.jsmpp.session.DataSmResult;
import org.jsmpp.session.MessageReceiverListener;
import org.jsmpp.session.Session;
import org.jsmpp.util.MessageId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implements the jsmpp {@link MessageReceiverListener}. jsmpp invokes these
 * callbacks from one of its own PDU processor threads (sized by
 * {@code ListenerConfig.maxConcurrentDispatch}); each PDU is converted to an
 * {@code smpp:Sms} record (via {@link SmsMapper}) and dispatched to the attached
 * Ballerina service via {@link Runtime#callMethod}. {@code ListenerConfig.responseMode}
 * controls how: in {@code SYNC} mode (the default) the call blocks the jsmpp thread, so
 * the {@code deliver_sm_resp}/{@code data_sm_resp} jsmpp sends back to the SMSC
 * reflects the service's real outcome and jsmpp's own bounded work queue can
 * apply backpressure; in {@code ASYNC} mode the call runs on a virtual thread
 * and the PDU is acknowledged immediately, before the service has run.
 */
public class Dispatcher implements MessageReceiverListener {

    private static final String ON_DELIVER_SM = "onDeliverSm";
    private static final String ON_DATA_SM = "onDataSm";
    private static final String ON_ERROR = "onError";

    /**
     * The {@code message_id} returned with every {@code data_sm_resp}. DATA_SM has no
     * submit queue behind it in this connector, so there is no application-assigned id to
     * report; an empty {@code MessageId} is used instead of {@code null} - jsmpp's own PDU
     * sender unconditionally calls {@code getCommandStatus()}/{@code getMessageId()} on
     * whatever {@link #onAcceptDataSm} returns, so a {@code null} result is a guaranteed NPE.
     */
    private static final MessageId EMPTY_MESSAGE_ID = emptyMessageId();

    private static MessageId emptyMessageId() {
        try {
            return new MessageId("");
        } catch (PDUStringException e) {
            // MessageId validates against StringParameter.MESSAGE_ID, a plain max-length
            // (65) check with no minimum - an empty string can never fail it. Unreachable
            // in practice; only guarded because MessageId's constructor declares a checked
            // exception.
            throw new AssertionError("unreachable: an empty MessageId must always be valid", e);
        }
    }

    /**
     * How a remote method wants its arguments: where the Sms goes, and where the Caller
     * goes ({@code -1} = the method did not declare one). Resolved once, at attach, by
     * TYPE — never re-derived per PDU.
     *
     * @param smsIndex parameter index for the {@code smpp:Sms} argument
     * @param callerIndex parameter index for the {@code smpp:Caller} argument, or
     *     {@code -1} if the method did not declare one
     * @param arity the bound-prefix arity (params after this are runtime-padded, per
     *     {@link #validateAndPlan})
     * @param isolated whether this remote method is isolated
     * @param smsReadonly whether the Sms parameter is declared {@code readonly & smpp:Sms}
     *     and must be frozen before dispatch
     */
    record MethodPlan(int smsIndex, int callerIndex, int arity, boolean isolated,
            boolean smsReadonly) { }

    /**
     * Immutable (service, remote-method-set, per-method plans) triple published through a
     * single volatile reference, so a PDU thread can never observe a torn attach/detach
     * (the new service paired with the old method set or plans, or vice versa).
     *
     * @param service the attached service
     * @param remoteMethods the names of the supported remote methods it implements
     * @param plans the dispatch plan resolved for each of those remote methods
     * @param onErrorIsolated whether the {@code onError} remote method is isolated
     */
    private record ServiceBinding(BObject service, Set<String> remoteMethods,
            Map<String, MethodPlan> plans, boolean onErrorIsolated) { }

    enum AttachResult { ATTACHED, ALREADY_ATTACHED, NO_REMOTE_METHODS, BAD_SIGNATURE }

    /**
     * Attach outcome plus, for {@code BAD_SIGNATURE}, the human-readable reason
     * (which method, which rule) — surfaced verbatim in the attach error.
     *
     * @param result the attach outcome
     * @param detail for {@code BAD_SIGNATURE}, the human-readable reason; {@code null}
     *     for every other outcome
     */
    record AttachOutcome(AttachResult result, String detail) {
        static final AttachOutcome ATTACHED_OK = new AttachOutcome(AttachResult.ATTACHED, null);
    }

    private final Runtime runtime;
    private volatile ServiceBinding binding; // null = no service attached
    private volatile boolean async = false;
    private final AtomicInteger inFlight = new AtomicInteger(0);

    /**
     * Admission gate for service dispatch, sized to {@code maxConcurrentDispatch}. Lives on
     * the Dispatcher, which is created once per listener and reused across every rebind
     * (NativeListener stores it as native data at init and re-reads it in each {@code bind()}),
     * so the concurrency bound is a property of the service/downstream, NOT of any one TCP
     * session: handlers still running from a dropped session keep their permits, and the
     * rebound session's total concurrent execution stays capped. Both SYNC and ASYNC gate on
     * this with a non-blocking {@code tryAcquire}; overflow is answered with
     * {@code ESME_RTHROTTLED} rather than blocking a jsmpp PDU-processor thread — that is what
     * keeps the reserve thread (see NativeListener's degree sizing) free to answer enquire_link.
     *
     * <p>Because the bound is per-listener, a handler still running from a session that has
     * since dropped keeps its permit, so it counts against the rebound session's budget too;
     * a permanently stuck handler therefore throttles the rebound session until it clears (or
     * {@code gracefulStop} times out). That is intended for a bound protecting a shared
     * downstream, and it is the same stuck handler that would already stall the drain.
     */
    private final Semaphore permits;

    /**
     * When true, {@code data_coding 0x00} (SMSC default alphabet) is decoded as unpacked
     * GSM 03.38 rather than the UTF-8 fallback. Opt-in ({@code ListenerConfig.decodeGsm7}),
     * so the default behaviour is unchanged for anyone relying on the UTF-8 fallback today.
     * Passed through to {@link SmsMapper#toSms}.
     */
    private final boolean decodeGsm7;

    /**
     * The one {@code smpp:Caller} object for this listener, created at native init and
     * passed to any remote method that declares a Caller-typed parameter. Holds only
     * stable references (the session {@code AtomicReference}, not a session), so sharing
     * one instance across every dispatch and every rebind is correct.
     */
    private final BObject caller;

    public Dispatcher(Runtime runtime, int maxConcurrentDispatch, boolean decodeGsm7, BObject caller) {
        this.runtime = runtime;
        // Non-fair: tryAcquire never queues, so fairness is irrelevant, and non-fair is faster.
        this.permits = new Semaphore(maxConcurrentDispatch);
        this.decodeGsm7 = decodeGsm7;
        this.caller = caller;
    }

    int inFlightCount() {
        return inFlight.get();
    }

    /**
     * Notifies the attached service's optional {@code onError} remote method, e.g. on an
     * unexpected SMPP session drop. Falls back to {@code ballerina/log} (via {@link #logError})
     * if the service doesn't implement {@code onError}. Runs on its own virtual thread: unlike PDU dispatch,
     * there's no {@code deliver_sm_resp}/{@code data_sm_resp} timing contract to preserve here,
     * so there's no reason to block the caller (typically one of jsmpp's own threads).
     *
     * @param message a description of what went wrong
     */
    void dispatchError(String message) {
        ServiceBinding binding = this.binding;
        BError err = ModuleUtils.createError(message);
        boolean hasOnError = binding != null && binding.remoteMethods().contains(ON_ERROR);
        StrandMetadata meta = new StrandMetadata(binding != null && binding.onErrorIsolated(), null);
        // ALWAYS run on a virtual thread - BOTH the onError callback and the no-handler log
        // fallback. Both cross into the Ballerina runtime (callMethod / callFunction), and this
        // method is called from a jsmpp state-listener thread during a CLOSED transition, with
        // scheduleRebind running immediately after it returns; doing the runtime call inline on
        // that thread derails the rebind. Incremented before the thread starts (same as the
        // ASYNC dispatch path) so a gracefulStop starting right after already covers it.
        inFlight.incrementAndGet();
        boolean spawned = false;
        try {
            Thread.startVirtualThread(() -> {
                try {
                    if (hasOnError) {
                        Object result = runtime.callMethod(binding.service(), ON_ERROR, meta, err);
                        if (result instanceof BError callbackErr) {
                            logError("the service's onError handler itself returned an error", callbackErr);
                        }
                    } else {
                        logError("SMPP session error and no onError handler is attached to the service", err);
                    }
                } finally {
                    inFlight.decrementAndGet();
                }
            });
            spawned = true;
        } finally {
            // If startVirtualThread threw (e.g. OOM), the vthread's finally never runs, so
            // undo the increment here - otherwise gracefulStop's drain waits out its full
            // timeout on a decrement that can never come.
            if (!spawned) {
                inFlight.decrementAndGet();
            }
        }
    }

    // The smpp module-level function (listener.bal) that routes a native-side failure
    // through ballerina/log:printError. Called via Runtime.callFunction so these failures land
    // in the application's configured log output rather than a raw stderr stack trace.
    private static final String LOG_FN = "logDispatchError";

    /**
     * Routes a native-side error through {@code ballerina/log} instead of {@code stderr}.
     * Runs the Ballerina log function synchronously on the calling thread (a log write is
     * cheap); safe from a jsmpp PDU thread or a dispatch virtual thread, same as
     * {@link Runtime#callMethod}.
     *
     * @param context a human description of where/why the error occurred
     * @param err the Ballerina error to log alongside {@code context}
     */
    private void logError(String context, BError err) {
        Module module = ModuleUtils.getModule();
        runtime.callFunction(module, LOG_FN, new StrandMetadata(false, null),
                StringUtils.fromString(context), err);
    }

    /**
     * PDU types already reported as unhandled. Bounded to the number of dispatchable
     * methods (2), so this is a two-slot latch, not a growing set.
     */
    private final java.util.Set<String> missingHandlerLogged =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final java.util.concurrent.atomic.AtomicLong throttledCount =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong nextThrottleLogAt =
            new java.util.concurrent.atomic.AtomicLong(1);

    /**
     * Makes {@code ESME_RTHROTTLED} visible. The connector had no counter and no log for
     * it, yet for a reply-style service at shipped defaults throttling is the EXPECTED
     * steady state, not an anomaly — so users would otherwise meet it on day one with
     * nothing to look at. Logged on a geometric schedule (1st, 2nd, 4th, 8th …) so a
     * sustained overload costs O(log n) lines instead of one per rejected PDU, while the
     * first occurrence is still immediate. Never blocks the reject path: the log runs on
     * a virtual thread, same reason as {@link #warnOnceMissingHandler}.
     */
    private void countThrottle() {
        long n = throttledCount.incrementAndGet();
        long threshold = nextThrottleLogAt.get();
        if (n < threshold || !nextThrottleLogAt.compareAndSet(threshold, threshold * 2)) {
            return;
        }
        BError err = ModuleUtils.createError("inbound dispatch throttled " + n + " time(s)");
        try {
            Thread.startVirtualThread(() -> logError(
                    "inbound PDU rejected with ESME_RTHROTTLED because maxConcurrentDispatch "
                            + "is fully occupied (" + n + " so far). The SMSC retains and "
                            + "redelivers these. Sustained throttling means handlers are slower "
                            + "than the inbound rate - raise maxConcurrentDispatch, or use "
                            + "responseMode: ASYNC if handlers block on outbound calls.", err));
        } catch (Throwable ignored) {
            // A failed log must never turn into a failed NACK.
        }
    }

    /**
     * Logs, ONCE per PDU type per listener, that inbound traffic is being NACKed because
     * the attached service implements no handler for it. Without this the only evidence
     * of a misconfigured service is on the SMSC's side: the connector would send a
     * negative command_status and keep no record of it. Once-per-type on purpose — the
     * condition is permanent, so per-message logging would be pure noise at the SMSC's
     * full redelivery rate.
     */
    private void warnOnceMissingHandler(String method) {
        if (!missingHandlerLogged.add(method)) {
            return;
        }
        BError err = ModuleUtils.createError("no " + method + " handler on the attached service");
        // On a VIRTUAL THREAD, never inline - same reason dispatchError does it (see
        // there): logError crosses into the Ballerina runtime, and this runs on a jsmpp
        // PDU-processor thread that owes the SMSC a deliver_sm_resp. Doing it inline
        // would delay the NACK past the peer's transaction timer and turn an asserted
        // ESME_RX_P_APPN into a response timeout. The reject path must stay at
        // microseconds (that is also why both no-handler checks precede toSms and
        // tryAcquire). Not tracked as in-flight: it is a log write with no service
        // callback, and a stop need not drain it.
        try {
            Thread.startVirtualThread(() -> logError(
                    "inbound " + method + " PDUs are being rejected with ESME_RX_P_APPN: the "
                            + "attached service implements no " + method + " remote method. "
                            + "Implement it (returning successfully is enough to consume the "
                            + "traffic) or expect the SMSC to record permanent delivery failures.",
                    err));
        } catch (Throwable t) {
            // A failed log must never turn into a failed NACK.
            missingHandlerLogged.remove(method);
        }
    }

    void setAsync(boolean async) {
        this.async = async;
    }

    BObject getService() {
        ServiceBinding b = this.binding;
        return b == null ? null : b.service();
    }

    /**
     * Validates before assigning: every rejection path returns with NO state change, so a
     * rejected attach can never disturb a previously attached, valid service. Synchronized
     * so two concurrent attaches can't both pass the already-attached check; the hot read
     * path (dispatch) stays a single volatile read.
     */
    synchronized AttachOutcome attach(BObject service) {
        if (this.binding != null) {
            return new AttachOutcome(AttachResult.ALREADY_ATTACHED, null);
        }
        // getRemoteMethods(), not getMethods(): the latter mixes remote and non-remote
        // methods, and a name-only check against it could validate a method that dispatch
        // (which resolves remote methods by name) would never invoke. ServiceType always
        // implements NetworkObjectType; anything else structurally cannot be a service.
        // getImpliedType for uniformity with the parameter-type checks below - getType(value)
        // already yields the concrete ObjectType here, so this is a no-op in practice, kept
        // identical so no future reader has to work out why one site unwraps differently.
        ObjectType objType = (ObjectType) TypeUtils.getImpliedType(TypeUtils.getType(service));
        if (!(objType instanceof NetworkObjectType networkType)) {
            return new AttachOutcome(AttachResult.NO_REMOTE_METHODS, null);
        }
        Set<String> names = new HashSet<>();
        Map<String, MethodPlan> plans = new HashMap<>();
        for (RemoteMethodType method : networkType.getRemoteMethods()) {
            String name = method.getName();
            if (!name.equals(ON_DELIVER_SM) && !name.equals(ON_DATA_SM) && !name.equals(ON_ERROR)) {
                continue; // unknown remote methods are ignored, as before
            }
            // Per-METHOD isolation: object-level isolation alone is over-permissive - an
            // isolated object can still have a non-isolated method body (e.g. one mutating
            // module state), and dispatching that concurrently would introduce races that
            // serialized dispatch previously prevented.
            boolean methodIsolated = objType.isIsolated() && objType.isIsolated(name);
            String problem = validateAndPlan(name, method, plans, methodIsolated);
            if (problem != null) {
                // Reject the whole attach with NO state change (validate-before-assign) - a
                // bad signature must be loud at attach, not a silent nil or a per-PDU panic
                // at dispatch.
                return new AttachOutcome(AttachResult.BAD_SIGNATURE, problem);
            }
            names.add(name);
        }
        if (names.isEmpty()) {
            return new AttachOutcome(AttachResult.NO_REMOTE_METHODS, null);
        }
        boolean onErrorIsolated = names.contains(ON_ERROR)
                && objType.isIsolated() && objType.isIsolated(ON_ERROR);
        this.binding = new ServiceBinding(service, Set.copyOf(names), Map.copyOf(plans),
                onErrorIsolated);
        return AttachOutcome.ATTACHED_OK;
    }

    /**
     * Validates one remote method's shape and records its dispatch plan. Returns a
     * human-readable problem, or {@code null} if valid.
     *
     * <p>The rule: parameters bind by TYPE, order-agnostic — {@code (Sms, Caller)} and
     * {@code (Caller, Sms)} are both legal, like ftp's type-first binding and unlike
     * mqtt's caller-last. Stricter than both where it matters:
     * <ul>
     *   <li>a Caller-typed parameter is matched BEFORE any defaultable-parameter
     *       leniency, and a defaultable Caller is rejected loudly — {@code isDefault}
     *       skipping is exactly how {@code smpp:Caller? c = ()} would silently strand a
     *       user's reply path;</li>
     *   <li>{@code smpp:Caller?} (a union) is not a Caller-typed parameter and is
     *       rejected as an unbindable parameter rather than skipped;</li>
     *   <li>rest parameters are rejected at attach — they do not appear in
     *       {@code getParameters()}, and the runtime pads the rest slot with a value the
     *       method cannot use, panicking per PDU;</li>
     *   <li>two parameters of the same type (e.g. {@code (Sms, Sms)}) are rejected —
     *       the runtime would accept and pass null into the second, which is a per-PDU
     *       panic deferred to production traffic.</li>
     * </ul>
     */
    private static String validateAndPlan(String name, RemoteMethodType method,
            Map<String, MethodPlan> plans, boolean methodIsolated) {
        if (method.getType().getRestType() != null) {
            return name + " must not declare a rest parameter (the runtime would pad it "
                    + "with a value per dispatch and panic)";
        }
        Parameter[] params = method.getType().getParameters();
        if (name.equals(ON_ERROR)) {
            // onError stays 1-arity by design: it is invoked from drop paths where no
            // request context exists, and handing it a Caller invites submitting from a
            // session that is mid-teardown.
            int required = 0;
            for (Parameter p : params) {
                // involvesCallerType, not isCallerType: the exact-type check misses
                // `smpp:Caller? c = ()`, which is a UNION and therefore not Caller - and
                // being defaulted it also skips the error-type check below, so it would
                // attach silently with c permanently (). Both branches now reject anything
                // that INVOLVES Caller.
                if (involvesCallerType(p.type)) {
                    return "onError must not declare an smpp:Caller parameter "
                            + "(including an optional or union-typed one)";
                }
                if (!p.isDefault) {
                    required++;
                    // getImpliedType: a distinct error subtype behind an alias, or a
                    // `readonly &` intersection, must still resolve to its ErrorType.
                    Type referred = TypeUtils.getImpliedType(p.type);
                    if (!(referred instanceof io.ballerina.runtime.api.types.ErrorType)) {
                        // Unchecked, dispatchError's callMethod would panic per drop and
                        // LOSE the notification.
                        return "onError's parameter '" + p.name + "' must be an error type";
                    }
                }
            }
            if (required != 1) {
                return "onError must take exactly one required parameter (the error); found "
                        + required;
            }
            return null;
        }
        int smsIndex = -1;
        int callerIndex = -1;
        boolean smsReadonly = false;
        for (int i = 0; i < params.length; i++) {
            Parameter param = params[i];
            if (isCallerType(param.type)) {
                if (param.isDefault) {
                    return name + " parameter '" + param.name + "': an smpp:Caller "
                            + "parameter must not be defaultable";
                }
                if (callerIndex >= 0) {
                    return name + " declares more than one smpp:Caller parameter";
                }
                callerIndex = i;
            } else if (isSmsType(param.type)) {
                if (smsIndex >= 0) {
                    return name + " declares more than one smpp:Sms parameter";
                }
                smsIndex = i;
                // `readonly & smpp:Sms` needs the record FROZEN before dispatch: toSms
                // builds a mutable one, and handing a mutable value to a readonly-typed
                // parameter fails the runtime's argument check. Resolved once here, never
                // per PDU.
                smsReadonly = TypeUtils.getImpliedType(param.type).isReadOnly();
            } else if (involvesCallerType(param.type)) {
                // Checked BEFORE the isDefault skip below: a union like `smpp:Caller? c = ()`
                // is defaultable, and skipping it would silently strand the user's reply
                // path with c always nil. Reject loudly instead.
                return name + " parameter '" + param.name + "': smpp:Caller must be a "
                        + "plain, non-defaultable parameter (smpp:Caller? is not accepted)";
            } else if (param.isDefault) {
                // `onDeliverSm(Sms sms, string extra = "x")` is a legal, working program -
                // the runtime pads trailing defaulted params when dispatch passes fewer
                // args. Skipped, not rejected. (Caller-involving types were matched above,
                // so no reply path can be skipped into nil.)
                continue;
            } else {
                return name + " parameter '" + param.name + "' has an unsupported type; "
                        + "expected smpp:Sms or smpp:Caller (note: smpp:Caller? is not "
                        + "accepted - declare it non-optional or not at all)";
            }
        }
        if (smsIndex < 0) {
            return name + " must declare an smpp:Sms parameter";
        }
        // Arity = the bound prefix only (Ballerina forces defaulted params to trail, so
        // everything past the last bound param is defaultable and runtime-padded).
        int arity = Math.max(smsIndex, callerIndex) + 1;
        plans.put(name, new MethodPlan(smsIndex, callerIndex, arity, methodIsolated, smsReadonly));
        return null;
    }

    private static boolean isCallerType(Type type) {
        return isModuleType(type, "Caller");
    }

    /** True if the type IS Caller or is a union with Caller as a member (e.g. Caller?). */
    private static boolean involvesCallerType(Type type) {
        Type referred = TypeUtils.getImpliedType(type);
        if (isCallerType(referred)) {
            return true;
        }
        if (referred instanceof io.ballerina.runtime.api.types.UnionType union) {
            for (Type member : union.getMemberTypes()) {
                // The member goes to isCallerType RAW - never through getImpliedType
                // first (verified empirically): the implied form of `(Caller & readonly)`
                // is a SYNTHESIZED type whose name is not "Caller", so wrapping the member
                // erased exactly the name the check needs, and `(readonly & Caller)? c = ()`
                // would slide through the defaulted skip with c permanently nil.
                // isModuleType already unwraps reference chains AND scans intersection
                // constituents, which is the resolution that keeps the name visible.
                if (isCallerType(member)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSmsType(Type type) {
        return isModuleType(type, "Sms");
    }

    /**
     * Exact-type match against this module's declaration - unions/optionals do not match.
     *
     * <p>{@code getImpliedType}, not {@code getReferredType}: the latter unwraps
     * type-reference chains only, so a {@code readonly & smpp:Sms} parameter - compile-
     * valid and idiomatic - would arrive here as an {@code IntersectionType} whose name is
     * not "Sms" and make the whole attach fail with "unsupported type". {@code getImpliedType}
     * unwraps intersections as well as references (it is the non-deprecated successor), so
     * both shapes resolve. Widening only: nothing previously accepted can now be rejected.
     */
    private static boolean isModuleType(Type type, String name) {
        Type referred = TypeUtils.getReferredType(type);
        if (nameMatches(referred, name)) {
            return true;
        }
        // `readonly & smpp:Sms` arrives as an IntersectionType. Neither getReferredType
        // nor getImpliedType yields something still NAMED "Sms" (the implied form is the
        // synthesized readonly shape), so the match is made against the CONSTITUENTS -
        // where the user's `smpp:Sms` is still itself. Narrow by construction: a
        // constituent that is a union (`readonly & smpp:Caller?`) does not name-match
        // here, so the optional-Caller trap keeps failing exactly as before.
        if (referred instanceof io.ballerina.runtime.api.types.IntersectionType intersection) {
            for (Type constituent : intersection.getConstituentTypes()) {
                if (nameMatches(TypeUtils.getReferredType(constituent), name)) {
                    return true;
                }
            }
        }
        return nameMatches(TypeUtils.getImpliedType(type), name);
    }

    /** Name + declaring-module match against this module's own declaration. */
    private static boolean nameMatches(Type type, String name) {
        if (!name.equals(type.getName())) {
            return false;
        }
        io.ballerina.runtime.api.Module m = type.getPackage();
        io.ballerina.runtime.api.Module ours = ModuleUtils.getModule();
        return m != null && ours != null
                && java.util.Objects.equals(m.getOrg(), ours.getOrg())
                && java.util.Objects.equals(m.getName(), ours.getName());
    }

    /** Clears the binding only if {@code expected} is the currently attached service (identity). */
    synchronized void detachIf(BObject expected) {
        ServiceBinding b = this.binding;
        if (b != null && b.service() == expected) {
            this.binding = null;
        }
    }

    @Override
    public void onAcceptDeliverSm(DeliverSm deliverSm) throws ProcessRequestException {
        dispatch(ON_DELIVER_SM, deliverSm, deliverSm.getShortMessage(), deliverSm.isSmscDeliveryReceipt());
    }

    @Override
    public DataSmResult onAcceptDataSm(DataSm dataSm, Session source) throws ProcessRequestException {
        // DATA_SM has no short_message field at all (unlike DELIVER_SM) - its payload is
        // always carried in the message_payload optional parameter (TLV), if present. The
        // esm_class Message Type bits that mark a delivery receipt (SMPP v3.4 5.2.12) mean
        // the same thing on every PDU, not just DELIVER_SM, and some SMSCs do send receipts
        // via DATA_SM - so this checks those bits via DeliverSm's public static helper
        // (a plain esm_class-byte check, unrelated to which PDU type the byte came from),
        // rather than assuming DATA_SM is never a receipt.
        dispatch(ON_DATA_SM, dataSm, EMPTY_SHORT_MESSAGE,
                DeliverSm.isSmscDeliveryReceipt(dataSm.getEsmClass()));
        return new DataSmResult(EMPTY_MESSAGE_ID, new OptionalParameter[0]);
    }

    private static final byte[] EMPTY_SHORT_MESSAGE = new byte[0];

    @Override
    public void onAcceptAlertNotification(AlertNotification alertNotification) {
        // No corresponding service method exposed yet; ignored. Deliberately NOT NACKed
        // like an unhandled deliver_sm/data_sm: alert_notification is the one inbound PDU
        // with no response PDU defined in SMPP v3.4, so there is nothing to acknowledge
        // either way.
    }

    /**
     * The single admission-gated dispatch path for both PDU types and both response modes.
     *
     * <p>Order matters and is load-bearing: the binding/method check and the semaphore
     * {@code tryAcquire} both happen BEFORE {@link SmsMapper#toSms} builds the record. On
     * the reject path we therefore do essentially no work (no record allocation, no
     * charset decode), so the jsmpp PDU-processor thread frees in microseconds and the
     * reserve thread stays available to answer enquire_link. Overflow is a NACK
     * ({@code ESME_RTHROTTLED}), not a drop: the SMSC never got a positive ack, so it
     * retains the message (at-least-once).
     *
     * <p>The only per-mode difference is the success path: SYNC runs the handler inline on
     * the jsmpp thread (so the {@code deliver_sm_resp}/{@code data_sm_resp} reflects the real
     * outcome) and ASYNC hands off to a virtual thread and lets jsmpp ack {@code ESME_ROK}.
     * Every path releases the permit exactly once - the {@code handOff} flag transfers that
     * responsibility to the virtual thread only once it has actually started.
     */
    private void dispatch(String method, AbstractSmCommand pdu, byte[] fallback, boolean deliveryReceipt)
            throws ProcessRequestException {
        ServiceBinding binding = this.binding;
        if (binding == null) {
            // No service attached AT ALL: a PDU arriving between 'start() and attach, or
            // after detach. Transient by nature - the same service may be attached a
            // millisecond later - so ESME_RX_T_APPN (0x64, "Receiver Temporary App
            // Error"), inviting the SMSC to retain and redeliver. Distinct from the
            // no-such-handler case below, which can never resolve itself.
            throw new ProcessRequestException(
                    "no service is attached to this listener", SMPPConstant.STAT_ESME_RX_T_APPN);
        }
        if (!binding.remoteMethods().contains(method)) {
            // A service IS attached but does not implement this PDU type's handler. This
            // must NOT `return` and let jsmpp answer ESME_ROK - a POSITIVE ack for a
            // message that is then dropped on the floor silently discharges the SMSC's
            // at-least-once guarantee against nothing, contradicting this connector's own
            // published posture ("a NACK is not a drop").
            //
            // ESME_RX_P_APPN (0x65, "Receiver Permanent App Error"), NOT the temporary
            // 0x64 used above and for handler errors: a missing remote method is a
            // permanent property of the deployed code - it cannot appear at runtime, so
            // redelivery could never succeed and 0x64 here would be a guaranteed poison
            // loop for the life of the deployment. Nor RX_R_APPN (0x66, "Reject"), which
            // is a per-MESSAGE verdict for what is really a per-CAPABILITY condition; a
            // permanent application error is what SMSC-side error accounting and DLQ
            // policy are built to consume.
            warnOnceMissingHandler(method);
            throw new ProcessRequestException(
                    "the attached service does not implement " + method,
                    SMPPConstant.STAT_ESME_RX_P_APPN);
        }
        // NOTE (load-bearing order): both checks above run BEFORE tryAcquire. An unhandled
        // PDU type must never consume a dispatch permit - otherwise a service with only
        // onDataSm, under deliver_sm load, would throttle its own onDataSm traffic, and
        // the unhandled PDU could be answered RTHROTTLED: a TRANSIENT status for a
        // PERMANENT condition, i.e. the poison loop again by another route.
        if (!permits.tryAcquire()) {
            // At the maxConcurrentDispatch limit. Reject cheaply (before toSms) with the
            // SMPP throttle status so the SMSC backs off and retains the message.
            countThrottle();
            throw new ProcessRequestException(
                    "dispatch throttled: maxConcurrentDispatch reached", SMPPConstant.STAT_ESME_RTHROTTLED);
        }
        // Permit held from here. It must be released on exactly one path below.
        boolean handOff = false;
        try {
            BObject svc = binding.service();
            // Derive strand isolation from the SERVICE's own isolation (the stdlib
            // pattern), instead of hardcoding false: a non-isolated strand holds the
            // runtime's PROCESS-WIDE lock for the whole handler - including a 30s
            // caller->submit round trip - stalling every non-isolated strand in the
            // program and nullifying maxConcurrentDispatch as a parallelism knob. An
            // isolated service (the norm, and what every template shows) now dispatches
            // concurrently; a non-isolated one keeps the old serialized-but-safe
            // behaviour.
            BMap<BString, Object> sms = SmsMapper.toSms(pdu, fallback, deliveryReceipt, this.decodeGsm7);
            // Position the arguments per the plan resolved at attach: 1-arity services
            // get exactly the Sms; 2-arity services get the shared Caller in whichever
            // position their signature put it.
            MethodPlan plan = binding.plans().get(method);
            if (plan == null) {
                // Unreachable today (dispatch is only called with onDeliverSm/onDataSm,
                // both of which always have plans when present in remoteMethods). Kept as
                // an INTERNAL-INCONSISTENCY guard - "we advertised this method but have no
                // plan for it" - which is what RSYSERR says and why it is distinct from
                // the RX_P_APPN "the service does not implement this" case above.
                //
                // An NPE would NOT escape silently here - SMPPSession.ResponseHandlerImpl
                // .processDeliverSm catches Exception and converts it to
                // ProcessRequestException(RX_T_APPN), so the PDU would still get a
                // (misleading, transient) resp. What genuinely escapes all three catches is
                // a java.lang.Error, one level up - it dies in the pool worker with no
                // resp sent, though permits.release() and the inFlight decrement still run
                // from their finally blocks.
                throw new ProcessRequestException(
                        "no dispatch plan for " + method, SMPPConstant.STAT_ESME_RSYSERR);
            }
            if (plan.smsReadonly()) {
                // The handler declared `readonly & smpp:Sms`. toSms built a mutable
                // record; freeze it in place before it crosses into the handler. Safe
                // to freeze rather than clone: this record was allocated for this one
                // dispatch and is referenced by nothing else.
                sms.freezeDirect();
            }
            StrandMetadata meta = new StrandMetadata(plan.isolated(), null);
            Object[] args = new Object[plan.arity()];
            args[plan.smsIndex()] = sms;
            if (plan.callerIndex() >= 0) {
                args[plan.callerIndex()] = this.caller;
            }
            inFlight.incrementAndGet();
            if (this.async) {
                // ASYNC: jsmpp acks ESME_ROK as soon as this callback returns; a failure in
                // the handler can no longer become a negative command_status. Tracked as
                // in-flight so gracefulStop drains it too.
                try {
                    Thread.startVirtualThread(() -> {
                        try {
                            Object result = runtime.callMethod(svc, method, meta, args);
                            if (result instanceof BError err) {
                                logError("error from " + method
                                        + " (ASYNC mode: not reflected back to the SMSC)", err);
                            }
                        } finally {
                            inFlight.decrementAndGet();
                            permits.release();
                        }
                    });
                    handOff = true; // the vthread now owns the inFlight decrement and permit release
                } finally {
                    if (!handOff) {
                        // startVirtualThread threw (e.g. OOM): the vthread never ran, so undo
                        // the increment here; the permit is released by the outer finally.
                        inFlight.decrementAndGet();
                    }
                }
                return;
            }
            try {
                Object result = runtime.callMethod(svc, method, meta, args);
                if (result instanceof BError err) {
                    // A SYNC handler returning an error becomes the deliver_sm_resp/data_sm_resp
                    // command_status. SMPP v3.4 (Table 5-2) defines a receiver-specific code for
                    // exactly this - the ESME's application failing to process a delivered
                    // message - so we use ESME_RX_T_APPN (0x64, "ESME Receiver Temporary App
                    // Error") rather than the generic ESME_RSYSERR (0x08). The spec does not
                    // mandate the SMSC's reaction, but "temporary" conventionally invites
                    // redelivery, matching this connector's at-least-once intent: a handler error
                    // means the message was not handled and should be retried, not permanently
                    // rejected (which RX_P_APPN/RX_R_APPN would imply, telling the SMSC to drop it).
                    throw new ProcessRequestException(err.getMessage(), SMPPConstant.STAT_ESME_RX_T_APPN, err);
                }
            } finally {
                inFlight.decrementAndGet();
            }
        } finally {
            if (!handOff) {
                permits.release();
            }
        }
    }
}
