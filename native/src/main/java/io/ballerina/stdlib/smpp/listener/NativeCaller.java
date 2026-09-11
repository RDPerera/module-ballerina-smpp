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

package io.ballerina.stdlib.smpp.listener;

import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.stdlib.smpp.ModuleUtils;
import io.ballerina.stdlib.smpp.SubmitErrorMapper;
import io.ballerina.stdlib.smpp.SubmitErrorMapper.InvalidRequest;
import io.ballerina.stdlib.smpp.SubmitErrorMapper.MappedFailure;
import org.jsmpp.bean.ESMClass;
import org.jsmpp.bean.NumberingPlanIndicator;
import org.jsmpp.bean.RawDataCoding;
import org.jsmpp.bean.RegisteredDelivery;
import org.jsmpp.bean.TypeOfNumber;
import org.jsmpp.extra.SessionState;
import org.jsmpp.session.SMPPSession;
import org.jsmpp.session.SubmitSmResult;
import org.jsmpp.util.StringParameter;
import org.jsmpp.util.StringType;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implements {@code smpp:Caller.submit}. Design constraints, all load-bearing:
 *
 * <ul>
 *   <li><b>Own native data, handed over once.</b> The three references this class reads
 *       ({@code SESSION_REF}, {@code STATE_REF}, {@code CONFIG}) are set on the Caller
 *       object exactly once, at {@code NativeListener.initListener} (single-threaded).
 *       The listener BObject's native-data map is a plain unsynchronized HashMap whose
 *       post-init writes are a documented data race — so this class never touches the
 *       listener object, only its own.</li>
 *   <li><b>The session is read through the {@code AtomicReference} on every submit.</b>
 *       {@code attemptRebind} swaps the referent; a cached {@code SMPPSession} would
 *       submit on a dead socket after the first rebind.</li>
 *   <li><b>No {@code stateLock} on the submit path.</b> Submits take a lock-free snapshot
 *       and fail fast; holding the listener's state lock across a blocking network call
 *       would stall every stop/rebind transition behind a slow SMSC.</li>
 *   <li><b>Local validation never echoes user data.</b> jsmpp's own validator embeds the
 *       offending value in its exception message — for submit_sm that is the SMS body
 *       and the destination MSISDN, the same leak class {@code validateCredentials}
 *       exists to prevent. Pre-checks here name the field and the length, never the
 *       value; jsmpp's validator becomes a never-fires backstop. The pre-check also
 *       prevents an orphaned {@code pendingResponses} entry: a {@code PDUStringException}
 *       escapes {@code executeSendCommand}'s IOException-only catch and would leak the
 *       registered response slot on every locally-invalid submit.</li>
 *   <li><b>Limits are read from jsmpp, not transcribed.</b> {@code StringParameter}
 *       exposes {@code getMax()}/{@code getType()}; the comparisons are asymmetric
 *       (C-octet strings reject {@code length >= max} because max includes the NUL,
 *       octet strings reject {@code length > max}) — hand-copied numbers reintroduce
 *       the off-by-one this method exists to avoid.</li>
 *   <li><b>Failure classification is shared.</b> Every jsmpp exception (or a locally
 *       refused request) is mapped onto this module's {@code FailureMode} vocabulary by
 *       {@link SubmitErrorMapper}, shared with a future {@code Client} — a caller
 *       branches retry logic on one vocabulary regardless of which side sent it.</li>
 * </ul>
 *
 * The pure static core ({@link #compose(SubmitSpec)}) takes and returns plain Java values
 * so JUnit reaches it without a Ballerina runtime.
 */
public final class NativeCaller {

    static final String SESSION_REF = "smpp.caller.sessionRef";
    static final String STATE_REF = "smpp.caller.stateRef";
    static final String CONFIG = "smpp.caller.config";
    static final String SESSION_USABLE = "smpp.caller.sessionUsable";
    static final String SUBMITS_IN_FLIGHT = "smpp.caller.submitsInFlight";
    static final String REBIND_ABANDONED = "smpp.caller.rebindAbandoned";
    static final String SELF_CLOSED = "smpp.caller.selfClosed";

    private NativeCaller() {}

    // ------------------------------------------------------------------
    // Pure request model (JUnit-reachable, no Ballerina values)
    // ------------------------------------------------------------------

    /** The submit request as extracted from Ballerina values — still unvalidated. */
    static final class SubmitSpec {
        String destinationAddress;
        String destinationAddressTypeOfNumber = "TON_INTERNATIONAL";
        String destinationAddressNumberingPlanIndicator = "NPI_ISDN";
        String sourceAddress = "";
        String sourceAddressTypeOfNumber = "TON_INTERNATIONAL";
        String sourceAddressNumberingPlanIndicator = "NPI_ISDN";
        String shortMessage;          // XOR shortMessageBytes (TextSms vs BinarySms)
        String encoding = "LATIN1";   // ASCII | LATIN1 | UCS2
        byte[] shortMessageBytes;     // escape hatch; requires dataCoding
        Integer dataCoding;           // raw byte value, only with shortMessageBytes
        boolean udhi;                 // esm_class bit 6; only with shortMessageBytes
        String registeredDelivery = "NONE";
        String serviceType = "";
        String validityPeriod;        // null = SMSC default
    }

    /** The composed jsmpp arguments — validated, encoded, ready to send. */
    static final class SubmitRequest {
        String serviceType;
        TypeOfNumber sourceAddressTypeOfNumber;
        NumberingPlanIndicator sourceAddressNumberingPlanIndicator;
        String sourceAddress;
        TypeOfNumber destinationAddressTypeOfNumber;
        NumberingPlanIndicator destinationAddressNumberingPlanIndicator;
        String destinationAddress;
        byte esmClass;
        byte protocolId;
        byte priorityFlag;
        String scheduleDeliveryTime;  // always null: not part of the public surface
        String validityPeriod;
        byte registeredDelivery;
        byte replaceIfPresent;
        byte dataCoding;
        byte smDefaultMsgId;
        byte[] body;
    }

    // ------------------------------------------------------------------
    // Pure core
    // ------------------------------------------------------------------

    /**
     * Validates and encodes a spec into jsmpp arguments. Throws {@link InvalidRequest}
     * (→ {@code failureMode: INVALID_REQUEST}) without echoing user data.
     */
    static SubmitRequest compose(SubmitSpec spec) throws InvalidRequest {
        SubmitRequest req = new SubmitRequest();

        // --- body: exactly one of shortMessage / shortMessageBytes ---
        // The TextSms|BinarySms union makes the wrong combinations unrepresentable from
        // typed Ballerina code; these checks are the native-side backstop, because the
        // BMap crossing interop is untyped.
        boolean hasText = spec.shortMessage != null;
        boolean hasBytes = spec.shortMessageBytes != null;
        if (hasText == hasBytes) {
            throw new InvalidRequest(hasText
                    ? "OutboundSms must set exactly one of shortMessage or shortMessageBytes, not both"
                    : "OutboundSms must set one of shortMessage or shortMessageBytes");
        }
        if (hasBytes) {
            if (spec.dataCoding == null) {
                throw new InvalidRequest("shortMessageBytes requires dataCoding (the raw data_coding "
                        + "byte for the pre-encoded payload)");
            }
            if (spec.dataCoding < 0 || spec.dataCoding > 0xFF) {
                throw new InvalidRequest("dataCoding must be 0-255, got " + spec.dataCoding);
            }
            req.body = spec.shortMessageBytes;
            req.dataCoding = (byte) (int) spec.dataCoding;
        } else {
            if (spec.dataCoding != null) {
                throw new InvalidRequest("dataCoding is only for shortMessageBytes; with shortMessage "
                        + "the encoding field decides the data_coding");
            }
            if (spec.udhi) {
                throw new InvalidRequest("udhi requires shortMessageBytes (a BinarySms): the "
                        + "connector encodes shortMessage itself and produces no UDH, so UDHI "
                        + "would declare a header that does not exist");
            }
            req.body = encode(spec.shortMessage, spec.encoding);
            req.dataCoding = switch (spec.encoding) {
                case "ASCII" -> 0x01;
                case "LATIN1" -> 0x03;
                case "UCS2" -> 0x08;
                default -> throw new InvalidRequest("unknown encoding: " + spec.encoding);
            };
        }
        if (req.body.length == 0) {
            // sm_length = 0 means "the payload is in the message_payload TLV" (section
            // 5.2.21), which this connector never sets - a conforming SMSC answers
            // ESME_RINVMSGLEN for what is really a local input error. Mirrors
            // required()'s stance on empty destinationAddress.
            throw new InvalidRequest((hasBytes ? "shortMessageBytes" : "shortMessage")
                    + " must not be empty");
        }
        int max = maxLength(StringParameter.SHORT_MESSAGE);
        if (req.body.length > max) {
            throw new InvalidRequest("short message is " + req.body.length
                    + " octets encoded; a single submit_sm carries at most " + max);
        }

        // --- addresses ---
        req.destinationAddress = required(spec.destinationAddress, "destinationAddress");
        checkAscii("destinationAddress", req.destinationAddress);
        checkLength("destinationAddress", req.destinationAddress, StringParameter.DESTINATION_ADDR);
        req.destinationAddressTypeOfNumber = typeOfNumber(
                spec.destinationAddressTypeOfNumber, "destinationAddress.typeOfNumber");
        req.destinationAddressNumberingPlanIndicator = numberingPlanIndicator(
                spec.destinationAddressNumberingPlanIndicator, "destinationAddress.numberingPlanIndicator");
        req.sourceAddress = spec.sourceAddress == null ? "" : spec.sourceAddress;
        checkAscii("sourceAddress", req.sourceAddress);
        checkLength("sourceAddress", req.sourceAddress, StringParameter.SOURCE_ADDR);
        if (req.sourceAddress.isEmpty()) {
            // SMPP v3.4 section 4.4.1: a NULL source address and its TON/NPI move
            // together - "if not known, set to NULL (Unknown)". An empty address tagged
            // INTERNATIONAL/ISDN (the Address defaults) is internally inconsistent and a
            // plausible ESME_RINVSRCADR/ESME_RINVSRCTON on strict SMSCs. This is also the
            // path an omitted `OutboundSms.sourceAddress` takes: `ListenerConfig` carries no
            // binding-level default source address, so an omission resolves to this
            // spec-legal "absent" rather than a configured fallback.
            req.sourceAddressTypeOfNumber = TypeOfNumber.UNKNOWN;
            req.sourceAddressNumberingPlanIndicator = NumberingPlanIndicator.UNKNOWN;
        } else {
            req.sourceAddressTypeOfNumber = typeOfNumber(spec.sourceAddressTypeOfNumber, "sourceAddress.typeOfNumber");
            req.sourceAddressNumberingPlanIndicator = numberingPlanIndicator(
                    spec.sourceAddressNumberingPlanIndicator, "sourceAddress.numberingPlanIndicator");
        }

        // --- the rest ---
        req.serviceType = spec.serviceType == null ? "" : spec.serviceType;
        checkAscii("serviceType", req.serviceType);
        checkLength("serviceType", req.serviceType, StringParameter.SERVICE_TYPE);
        req.validityPeriod = spec.validityPeriod;
        if (req.validityPeriod != null) {
            // VALIDITY_PERIOD is the one pre-checked parameter with isRangeMinAndMax ==
            // false: jsmpp (correctly modelling SMPP v3.4 section 7.1.1) accepts only the
            // empty string or EXACTLY 16 characters - a range check here would let a
            // 1-15 char value through to jsmpp's validator, which throws AFTER
            // pendingResponses.put (orphaning the entry) and echoes the raw value.
            // Validate the exact shape locally, naming nothing but the field.
            if (!isValidSmppTime(req.validityPeriod)) {
                throw new InvalidRequest("validityPeriod must be empty or exactly 16 "
                        + "characters in the SMPP time format YYMMDDhhmmsstnnp "
                        + "(section 7.1.1), e.g. absolute 240115143000000+ or relative "
                        + "000000020000000R");
            }
        }
        req.registeredDelivery = switch (spec.registeredDelivery) {
            case "NONE" -> 0x00;
            case "ON_SUCCESS_OR_FAILURE" -> 0x01;
            case "ON_FAILURE_ONLY" -> 0x02;
            default -> throw new InvalidRequest("unknown registeredDelivery: " + spec.registeredDelivery);
        };
        // Locked to plain point-to-point defaults, with ONE user-controlled exception:
        // esm_class bit 6 (UDHI, 0x40) via BinarySms.udhi - section 5.2.12 requires it
        // whenever the payload starts with a User Data Header, and without it the
        // handset renders the header octets as visible garbage. Every other esm_class
        // bit stays 0: the messaging-mode and message-type bits change SMSC routing and
        // billing invisibly, and this connector has no machinery behind them.
        req.esmClass = spec.udhi ? (byte) 0x40 : (byte) 0x00;
        req.protocolId = 0x00;
        req.priorityFlag = 0x00;
        req.scheduleDeliveryTime = null;
        req.replaceIfPresent = 0x00;
        req.smDefaultMsgId = 0x00;
        return req;
    }

    /**
     * Encodes text for the wire, rejecting (never silently substituting) unencodable
     * characters. {@code String.getBytes(ISO_8859_1)} replaces what it cannot encode
     * with {@code ?} — a subscriber-visible corruption — so encodability is checked
     * per UTF-16 code unit and the failure names the index, not the character.
     */
    static byte[] encode(String text, String encoding) throws InvalidRequest {
        switch (encoding) {
            case "ASCII" -> {
                for (int i = 0; i < text.length(); i++) {
                    if (text.charAt(i) > 0x7F) {
                        throw new InvalidRequest("shortMessage contains a character not representable "
                                + "in ASCII at index " + i + "; use LATIN1, UCS2, or shortMessageBytes");
                    }
                }
                return text.getBytes(StandardCharsets.US_ASCII);
            }
            case "LATIN1" -> {
                for (int i = 0; i < text.length(); i++) {
                    if (text.charAt(i) > 0xFF) {
                        throw new InvalidRequest("shortMessage contains a character not representable "
                                + "in Latin-1 at index " + i + "; use UCS2 or shortMessageBytes");
                    }
                }
                return text.getBytes(StandardCharsets.ISO_8859_1);
            }
            case "UCS2" -> {
                // UTF-16BE: two octets per UTF-16 code unit - surrogate pairs (emoji)
                // count as two units, i.e. four octets.
                return text.getBytes(StandardCharsets.UTF_16BE);
            }
            default -> throw new InvalidRequest("unknown encoding: " + encoding);
        }
    }

    // ------------------------------------------------------------------
    // The extern
    // ------------------------------------------------------------------

    /**
     * {@code smpp:Caller.submit}. Lock-free pre-checks (lifecycle, bind type, session
     * liveness), then compose → send → wrap. Never panics: every failure returns a typed
     * {@code smpp:Error} with {@code ErrorDetail} populated.
     */
    public static Object submit(BObject caller, BMap<BString, Object> sms) {
        @SuppressWarnings("unchecked")
        AtomicReference<NativeListener.ListenerState> stateRef =
                (AtomicReference<NativeListener.ListenerState>) caller.getNativeData(STATE_REF);
        @SuppressWarnings("unchecked")
        AtomicReference<SMPPSession> sessionRef =
                (AtomicReference<SMPPSession>) caller.getNativeData(SESSION_REF);
        @SuppressWarnings("unchecked")
        BMap<BString, Object> config = (BMap<BString, Object>) caller.getNativeData(CONFIG);

        // Pre-check 1: lifecycle. STOPPING is deliberately ALLOWED (owner decision):
        // gracefulStop drains in-flight handlers, and rejecting submits from the very
        // handlers being drained would drop every reply-style service's replies on
        // shutdown. Submits stay legal until the session actually unbinds; the drain
        // tracks them (submitsInFlight below).
        NativeListener.ListenerState st = stateRef.get();
        if (st == NativeListener.ListenerState.INIT || st == NativeListener.ListenerState.STARTING) {
            return detailError("cannot submit: the listener has not started yet - call 'start() first",
                    "INVALID_REQUEST", null, false);
        }
        if (st == NativeListener.ListenerState.STOPPED) {
            return detailError("cannot submit: the listener has been stopped", "INVALID_REQUEST",
                    null, false);
        }

        // Pre-check 2: bind type. Deliberately names the config field and the fix, and
        // never says BOUND_RX - jsmpp's own ensureTransmittable would throw a bare
        // IOException here, which is exactly what this guard exists to improve on.
        String bindType = config.getStringValue(StringUtils.fromString("bindType")).getValue();
        if (!"TRANSCEIVER".equals(bindType)) {
            return detailError("cannot submit on a RECEIVER bind: submitting requires "
                    + "bindType: TRANSCEIVER in the ListenerConfig", "INVALID_REQUEST", null, false);
        }

        // Pre-check 3: session liveness. Two signals, both required:
        // - sessionUsable is the connector's OWN drop decision (set false in
        //   onUnexpectedDrop, true at install). It is what makes the wedge visible here:
        //   a wedged session still CLAIMS BOUND_TRX forever, so getSessionState() alone
        //   would accept submits onto a dead socket for the whole rebind window.
        // - getSessionState() covers the inverse sliver (a stop closed the session but
        //   the flag flip has not been observed yet).
        // Classified LINK_DOWN, not INVALID_REQUEST (owner decision): an already-down
        // link and a mid-submit link death are the same operational condition and must
        // land in one FailureMode bucket. The wording promises nothing a disabled/
        // exhausted rebind cannot deliver.
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicBoolean usable =
                (java.util.concurrent.atomic.AtomicBoolean) caller.getNativeData(SESSION_USABLE);
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicInteger submitsInFlight =
                (java.util.concurrent.atomic.AtomicInteger) caller.getNativeData(SUBMITS_IN_FLIGHT);
        // RESERVATION ORDER: increment FIRST, then check liveness. The other order is a
        // TOCTOU: a submit passing the check could still be between check and increment
        // when awaitDrain reads 0 and stop() unbinds under it. With increment-first,
        // either the drain sees us, or we see the usable=false flip that stop() makes
        // before its post-flip sweep - never neither.
        submitsInFlight.incrementAndGet();
        try {
            SMPPSession session = sessionRef.get();
            if (session == null || !usable.get()
                    || session.getSessionState() != SessionState.BOUND_TRX) {
                // LINK_ABANDONED vs LINK_DOWN: same down link, opposite advice. The
                // abandoned flag is latched by scheduleRebind's give-up points and
                // cleared only by a successful install, so this branch is the one place
                // "retrying is futile" becomes program-visible.
                java.util.concurrent.atomic.AtomicBoolean abandoned =
                        (java.util.concurrent.atomic.AtomicBoolean) caller.getNativeData(REBIND_ABANDONED);
                if (abandoned.get()) {
                    return detailError("cannot submit: the SMSC link is down and rebinding was "
                            + "disabled or exhausted - this listener will not recover; create a "
                            + "new Listener", "LINK_ABANDONED", null, false);
                }
                return detailError("cannot submit: the SMSC session is down"
                        + " (rebinding runs per rebindPolicy, if enabled)", "LINK_DOWN", null, false);
            }
            SubmitSpec spec = specFrom(sms);
            SubmitRequest req = compose(spec);
            SubmitSmResult result;
            ConnectorSession.enterSubmitContext();
            try {
                result = session.submitShortMessage(
                        req.serviceType,
                        req.sourceAddressTypeOfNumber, req.sourceAddressNumberingPlanIndicator,
                        req.sourceAddress,
                        req.destinationAddressTypeOfNumber, req.destinationAddressNumberingPlanIndicator,
                        req.destinationAddress,
                        new ESMClass(req.esmClass), req.protocolId, req.priorityFlag,
                        req.scheduleDeliveryTime, req.validityPeriod,
                        new RegisteredDelivery(req.registeredDelivery), req.replaceIfPresent,
                        new RawDataCoding(req.dataCoding), req.smDefaultMsgId,
                        req.body);
            } finally {
                ConnectorSession.exitSubmitContext();
            }
            BMap<BString, Object> out = ValueCreator.createRecordValue(
                    ModuleUtils.getModule(), "SubmitResult");
            String messageId = result == null || result.getMessageId() == null
                    ? "" : result.getMessageId();
            out.put(StringUtils.fromString("messageId"), StringUtils.fromString(messageId));
            return out;
        } catch (Exception e) {
            // Exception, not Throwable: every failure SubmitErrorMapper classifies is an
            // Exception, and a VirtualMachineError must panic, not become a returned
            // smpp:Error. specFrom is inside this net too, so a malformed BMap cannot
            // panic across interop. The selfClosed marker is read AT FAILURE TIME,
            // deliberately: a stop that raced in while this submit was parked is exactly
            // what it must observe.
            java.util.concurrent.atomic.AtomicBoolean selfClosed =
                    (java.util.concurrent.atomic.AtomicBoolean) caller.getNativeData(SELF_CLOSED);
            MappedFailure f = SubmitErrorMapper.mapSubmitFailure(e, selfClosed.get());
            return SubmitErrorMapper.toError(f);
        } finally {
            // The one decrement, on every path incl. throws - a leaked count would make
            // every later gracefulStop burn its full timeout.
            submitsInFlight.decrementAndGet();
        }
    }

    // ------------------------------------------------------------------
    // Ballerina-value plumbing
    // ------------------------------------------------------------------

    private static BError detailError(String message, String failureMode, Integer commandStatus,
            boolean possiblySubmitted) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("failureMode", failureMode);
        if (commandStatus != null) {
            detail.put("commandStatus", (long) (int) commandStatus);
        }
        detail.put("possiblySubmitted", possiblySubmitted);
        return ModuleUtils.createError(message, detail);
    }

    /** Unpacks OutboundSms into a pure SubmitSpec. */
    private static SubmitSpec specFrom(BMap<BString, Object> sms) throws InvalidRequest {
        SubmitSpec spec = new SubmitSpec();

        Object dest = sms.get(StringUtils.fromString("destinationAddress"));
        applyAddress(spec, dest, true);

        // `ListenerConfig` carries no binding-level default source address (unlike a
        // Client/Listener config that maintains one): an omitted `sourceAddress` here
        // resolves directly to "" via applyAddress's null-value branch, which `compose`
        // then treats as the spec-legal "absent" address (TON/NPI Unknown).
        Object src = sms.get(StringUtils.fromString("sourceAddress"));
        applyAddress(spec, src, false);

        BString text = sms.getStringValue(StringUtils.fromString("shortMessage"));
        spec.shortMessage = text == null ? null : text.getValue();
        spec.encoding = str(sms, "encoding", "LATIN1");
        Object bytes = sms.get(StringUtils.fromString("shortMessageBytes"));
        spec.shortMessageBytes = bytes == null ? null : ((BArray) bytes).getBytes();
        Object dc = sms.get(StringUtils.fromString("dataCoding"));
        if (dc != null) {
            long dcLong = (Long) dc;
            // Range-check BEFORE narrowing: (int) 4294967296L is 0, which would silently
            // send data_coding 0x00 instead of erroring.
            if (dcLong < 0 || dcLong > 0xFF) {
                throw new InvalidRequest("dataCoding must be 0-255, got " + dcLong);
            }
            spec.dataCoding = (int) dcLong;
        }
        Object udhi = sms.get(StringUtils.fromString("udhi"));
        spec.udhi = udhi instanceof Boolean b && b;
        spec.registeredDelivery = str(sms, "registeredDelivery", "NONE");
        spec.serviceType = str(sms, "serviceType", "");
        BString validity = sms.getStringValue(StringUtils.fromString("validityPeriod"));
        spec.validityPeriod = validity == null ? null : validity.getValue();
        return spec;
    }

    /** string|Address → the spec's addr/typeOfNumber/numberingPlanIndicator triple. */
    @SuppressWarnings("unchecked")
    private static void applyAddress(SubmitSpec spec, Object value, boolean isDest)
            throws InvalidRequest {
        String addr;
        String tonName = "TON_INTERNATIONAL";
        String npiName = "NPI_ISDN";
        if (value == null) {
            if (isDest) {
                throw new InvalidRequest("destinationAddress is required");
            }
            addr = "";
        } else if (value instanceof BString s) {
            addr = s.getValue();
        } else {
            BMap<BString, Object> rec = (BMap<BString, Object>) value;
            addr = rec.getStringValue(StringUtils.fromString("value")).getValue();
            tonName = rec.getStringValue(StringUtils.fromString("typeOfNumber")).getValue();
            npiName = rec.getStringValue(StringUtils.fromString("numberingPlanIndicator")).getValue();
        }
        if (isDest) {
            spec.destinationAddress = addr;
            spec.destinationAddressTypeOfNumber = tonName;
            spec.destinationAddressNumberingPlanIndicator = npiName;
        } else {
            spec.sourceAddress = addr;
            spec.sourceAddressTypeOfNumber = tonName;
            spec.sourceAddressNumberingPlanIndicator = npiName;
        }
    }

    private static String str(BMap<BString, Object> map, String key, String fallback) {
        BString v = map.getStringValue(StringUtils.fromString(key));
        return v == null ? fallback : v.getValue();
    }

    private static String required(String value, String field) throws InvalidRequest {
        if (value == null || value.isEmpty()) {
            throw new InvalidRequest(field + " is required and must not be empty");
        }
        return value;
    }

    /**
     * SMPP v3.4 section 7.1.1 time format: 15 digits then one of {@code + - R}. jsmpp's
     * StringValidator only checks the length (0 or exactly 16); the shape check here is
     * stricter so a malformed-but-16-char value fails locally (non-echoing) instead of
     * drawing ESME_RINVEXPIRY from the SMSC.
     */
    static boolean isValidSmppTime(String value) {
        if (value.isEmpty()) {
            return true;
        }
        if (value.length() != 16) {
            return false;
        }
        for (int i = 0; i < 15; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        char last = value.charAt(15);
        return last == '+' || last == '-' || last == 'R';
    }

    /**
     * Rejects any character above 0x7F in an address/C-octet field. jsmpp validates
     * these fields in UTF-16 code units ({@code StringValidator} counts
     * {@code value.length()}) but WRITES them with {@code String.getBytes()} in the JVM
     * default charset ({@code PDUByteBuffer.append(String)}) — so a non-ASCII character
     * ships more octets than either validator counted, silently overflowing the field
     * on the wire, and the emitted bytes change with {@code -Dfile.encoding}. Restricting
     * these fields to ASCII makes code-unit count == octet count true by construction on
     * every ASCII-transparent platform charset, which is what makes {@link #checkLength}
     * exact rather than accidental. Runs BEFORE the length check so the diagnostic names
     * the real problem. Names the field and the index, never the value (MSISDNs/sender IDs).
     */
    static void checkAscii(String field, String value) throws InvalidRequest {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 0x7F) {
                throw new InvalidRequest(field + " contains a non-ASCII character at index " + i
                        + "; SMPP address and C-octet fields must be ASCII");
            }
            if (c == 0x00) {
                // A C-octet-string is NUL-terminated on the wire (SMPP v3.4 3.1); an embedded
                // NUL would silently truncate the field there. jsmpp's own StringValidator
                // checks only length, never content, so nothing else catches this.
                throw new InvalidRequest(field + " contains an embedded NUL at index " + i
                        + "; SMPP C-octet-string fields are NUL-terminated on the wire");
            }
        }
    }

    /**
     * jsmpp's limit for the parameter, adjusted for the C-octet asymmetry: a C-octet
     * string's max INCLUDES the terminating NUL (its validator rejects
     * {@code length >= max}), an octet string's does not (rejects {@code > max}).
     * Exact only because {@link #checkAscii} runs first: for ASCII, UTF-16 code units
     * and wire octets coincide.
     */
    static int maxLength(StringParameter p) {
        return p.getType() == StringType.C_OCTET_STRING ? p.getMax() - 1 : p.getMax();
    }

    private static void checkLength(String field, String value, StringParameter p)
            throws InvalidRequest {
        int max = maxLength(p);
        if (value.length() > max) {
            // Names the field and lengths only - the value may be an MSISDN.
            throw new InvalidRequest(field + " is " + value.length()
                    + " characters; the SMPP limit is " + max);
        }
    }

    private static TypeOfNumber typeOfNumber(String name, String field) throws InvalidRequest {
        // The Ballerina TypeOfNumber enum's values equal its member names (TON_INTERNATIONAL), so
        // Config.toml matches the docs and jsmpp's identifier spelling stays OUT of the
        // published contract. Stripping the prefix here is the entire jsmpp mapping.
        try {
            return TypeOfNumber.valueOf(stripPrefix(name, "TON_", field));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequest(field + ": unknown type-of-number '" + name + "'");
        }
    }

    private static NumberingPlanIndicator numberingPlanIndicator(String name, String field) throws InvalidRequest {
        try {
            return NumberingPlanIndicator.valueOf(stripPrefix(name, "NPI_", field));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequest(field + ": unknown numbering-plan indicator '" + name + "'");
        }
    }

    private static String stripPrefix(String name, String prefix, String field) throws InvalidRequest {
        if (!name.startsWith(prefix)) {
            throw new InvalidRequest(field + ": unknown value '" + name + "'");
        }
        return name.substring(prefix.length());
    }
}
