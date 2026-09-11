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

package io.ballerina.stdlib.smpp.client;

import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BDecimal;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.stdlib.smpp.ModuleUtils;
import io.ballerina.stdlib.smpp.ObservedConnection;
import io.ballerina.stdlib.smpp.RawConnectionFactory;
import io.ballerina.stdlib.smpp.SmppPlainConnectionFactory;
import io.ballerina.stdlib.smpp.SmppSslConnectionFactory;
import io.ballerina.stdlib.smpp.SubmitErrorMapper;
import io.ballerina.stdlib.smpp.SubmitErrorMapper.InvalidRequest;
import org.jsmpp.PDUStringException;
import org.jsmpp.bean.Address;
import org.jsmpp.bean.AlertNotification;
import org.jsmpp.bean.BindType;
import org.jsmpp.bean.DataSm;
import org.jsmpp.bean.DeliverSm;
import org.jsmpp.bean.ESMClass;
import org.jsmpp.bean.MessageState;
import org.jsmpp.bean.NumberingPlanIndicator;
import org.jsmpp.bean.OptionalParameter;
import org.jsmpp.bean.OptionalParameters;
import org.jsmpp.bean.RawDataCoding;
import org.jsmpp.bean.RegisteredDelivery;
import org.jsmpp.bean.ReplaceIfPresentFlag;
import org.jsmpp.bean.TypeOfNumber;
import org.jsmpp.bean.UnsuccessDelivery;
import org.jsmpp.extra.SessionState;
import org.jsmpp.session.DataSmResult;
import org.jsmpp.session.MessageReceiverListener;
import org.jsmpp.session.QuerySmResult;
import org.jsmpp.session.SMPPSession;
import org.jsmpp.session.Session;
import org.jsmpp.session.SubmitMultiResult;
import org.jsmpp.session.SubmitSmResult;
import org.jsmpp.util.MessageId;
import org.jsmpp.util.StringParameter;
import org.jsmpp.util.StringType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Backs the Ballerina {@code smpp:Client} — a bound SMPP session used only for the active
 * outbound submit-family operations ({@code submit_sm}, {@code submit_multi},
 * {@code data_sm}, {@code query_sm}, {@code cancel_sm}, {@code replace_sm}). State (the
 * jsmpp session, the config, and the two liveness flags below) is kept as native data on
 * the {@code Client} {@link BObject}, written once at {@link #init} — before any concurrent
 * remote call could possibly observe the object — exactly as {@code NativeListener} does
 * for the same reason (the runtime's native-data map is a plain unsynchronized
 * {@code HashMap}).
 *
 * <p><b>No rebind loop.</b> Unlike a {@code Listener}, a {@code Client} never tries to
 * restore a dropped session (see {@code types.bal}'s {@code BindType}/{@code RebindPolicy}
 * docs). Two flags model the whole lifecycle after a successful {@link #init}:
 * {@code usable} (flipped false the instant a drop is observed — via jsmpp's own
 * {@code SessionStateListener} or this class's {@code onTransportDeath} hook — and never
 * flipped back) and {@code closed} (flipped true, once, by {@link #close}). Once
 * {@code usable} is false, every submit-family call fails fast with {@code LINK_ABANDONED}
 * without touching jsmpp at all — "retrying is futile for this Client's life" is exactly
 * true here, since nothing ever un-abandons it.
 *
 * <p>Exceptions from every submit-family jsmpp call are mapped through the shared
 * {@link SubmitErrorMapper} — this class never re-derives that classification.
 */
public final class NativeClient {

    private static final String NATIVE_SESSION = "smpp.client.session";
    private static final String NATIVE_BIND_TYPE = "smpp.client.bindType";
    private static final String NATIVE_USABLE = "smpp.client.usable";
    private static final String NATIVE_CLOSED = "smpp.client.closed";
    private static final String NATIVE_OBSERVED_CONN = "smpp.client.observedConn";

    // jsmpp's StringValidator rejects systemId/password/systemType at length 16/9/13
    // respectively (StringParameter.SYSTEM_ID/PASSWORD/SYSTEM_TYPE - each C-octet-string
    // max includes the wire NUL terminator), so these are the largest usable lengths. Same
    // constants as NativeListener's; duplicated here rather than shared, since they are
    // this package's own pre-send validation, not shared infra.
    private static final int MAX_SYSTEM_ID_LENGTH = 15;
    private static final int MAX_PASSWORD_LENGTH = 8;
    private static final int MAX_SYSTEM_TYPE_LENGTH = 12;

    // The message_payload TLV (used by submitData, which has no short_message field at
    // all - see doSubmitData) is a 2-byte-length-prefixed octet string, so 65535 is its
    // own wire ceiling. Unlike StringParameter.SHORT_MESSAGE (254 octets), jsmpp exposes
    // no named constant for this - the ceiling comes from the TLV encoding itself.
    private static final int MAX_MESSAGE_PAYLOAD_LENGTH = 65535;

    // Sized generously rather than derived from a per-client concurrency knob (ClientConfig
    // has none, unlike ListenerConfig.maxConcurrentDispatch): every inbound PDU this
    // session ever needs to process is either an enquire_link probe or a response to one of
    // OUR OWN submit-family calls, so a modest fixed pool keeps concurrent calls on this
    // isolated client from serializing behind each other's response wait.
    private static final int PDU_PROCESSOR_DEGREE = 10;

    // Bounds close()'s unbindAndClose() the same way NativeListener's CLOSE_WATCHDOG_MS
    // bounds a Listener stop: a dedicated daemon thread force-closes the raw socket if the
    // whole close choreography runs long, against a black-holed peer. Fixed here rather
    // than derived from a housekeeping-timer split (NativeListener's ConnectorSession
    // trick, which only the Listener needs - see ClientConfig.transactionTimeout's doc):
    // this watchdog is the ONLY thing bounding close() regardless of what value ended up in
    // the session's own transactionTimer.
    private static final long CLOSE_WATCHDOG_MS = 4000;

    private NativeClient() {
    }

    // ------------------------------------------------------------------
    // A Client has no inbound-dispatch surface (see types.bal's BindType doc: a Client
    // never drives a Listener-shaped callback). This listener exists only so jsmpp itself
    // never NPEs on an inbound deliver_sm/data_sm/alert_notification - it acks politely and
    // discards, since there is no user code on this side of a Client to hand a PDU to.
    // ------------------------------------------------------------------
    private static final MessageReceiverListener NOOP_RECEIVER_LISTENER = new MessageReceiverListener() {
        @Override
        public DataSmResult onAcceptDataSm(DataSm dataSm, Session source) {
            try {
                return new DataSmResult(new MessageId(""), OptionalParameters.EMPTY_OPTIONAL_PARAMETERS);
            } catch (PDUStringException e) {
                // Unreachable in practice - an empty MessageId is always valid; the
                // constructor's checked exception still has to be handled.
                throw new IllegalStateException("unexpected failure building an empty MessageId", e);
            }
        }

        @Override
        public void onAcceptDeliverSm(DeliverSm deliverSm) {
            // No callback to invoke; returning normally acks with ESME_ROK.
        }

        @Override
        public void onAcceptAlertNotification(AlertNotification alertNotification) {
            // alert_notification carries no response per the SMPP spec; nothing to do.
        }
    };

    // ------------------------------------------------------------------
    // Pure request-composition helpers (address/body decoding, local validation)
    // ------------------------------------------------------------------

    /** A resolved address plus its TON/NPI, ready for a jsmpp call. */
    private static final class AddrSpec {
        final String addr;
        final TypeOfNumber typeOfNumber;
        final NumberingPlanIndicator numberingPlanIndicator;

        AddrSpec(String addr, TypeOfNumber typeOfNumber, NumberingPlanIndicator numberingPlanIndicator) {
            this.addr = addr;
            this.typeOfNumber = typeOfNumber;
            this.numberingPlanIndicator = numberingPlanIndicator;
        }
    }

    /** A composed, validated, encoded message body. */
    private static final class BodyResult {
        final byte[] bytes;
        final byte dataCoding;
        final boolean udhi;

        BodyResult(byte[] bytes, byte dataCoding, boolean udhi) {
            this.bytes = bytes;
            this.dataCoding = dataCoding;
            this.udhi = udhi;
        }
    }

    /**
     * Resolves an {@code OutboundBase.destinationAddress}/{@code sourceAddress} field — a
     * {@code string|Address} union — into an {@link AddrSpec}. Mirrors the "plain string is
     * shorthand for {@code {value: <the string>}}" convention documented on
     * {@code types.bal}'s {@code Address}: an empty (or omitted, for a non-required field)
     * address gets {@code TON_UNKNOWN}/{@code NPI_UNKNOWN} per SMPP v3.4 4.4.1 (a NULL
     * address and its TON/NPI move together).
     */
    @SuppressWarnings("unchecked")
    private static AddrSpec resolveAddress(Object value, boolean required, String fieldName)
            throws InvalidRequest {
        if (value == null) {
            if (required) {
                throw new InvalidRequest(fieldName + " is required");
            }
            return new AddrSpec("", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN);
        }
        String addr;
        String tonName = "TON_INTERNATIONAL";
        String npiName = "NPI_ISDN";
        if (value instanceof BString s) {
            addr = s.getValue();
        } else {
            BMap<BString, Object> rec = (BMap<BString, Object>) value;
            addr = rec.getStringValue(StringUtils.fromString("value")).getValue();
            tonName = rec.getStringValue(StringUtils.fromString("typeOfNumber")).getValue();
            npiName = rec.getStringValue(StringUtils.fromString("numberingPlanIndicator")).getValue();
        }
        if (addr.isEmpty()) {
            return new AddrSpec("", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN);
        }
        return new AddrSpec(addr,
                typeOfNumber(tonName, fieldName + ".typeOfNumber"),
                numberingPlanIndicator(npiName, fieldName + ".numberingPlanIndicator"));
    }

    /**
     * Resolves a plain {@code string} address parameter (the shape {@code queryStatus},
     * {@code cancel}, and {@code replace} take for their address arguments - no
     * {@code Address} union, since those operations correlate against an
     * already-established binding rather than addressing a fresh message). Same shorthand
     * as {@link #resolveAddress}: non-empty gets {@code TON_INTERNATIONAL}/{@code NPI_ISDN},
     * empty gets the NULL-address {@code UNKNOWN}/{@code UNKNOWN} pair.
     */
    private static AddrSpec resolvePlainAddress(String addr) {
        if (addr == null || addr.isEmpty()) {
            return new AddrSpec("", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN);
        }
        return new AddrSpec(addr, TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN);
    }

    /**
     * Validates and encodes an {@code OutboundSms}'s body (the {@code TextSms}/
     * {@code BinarySms} union, read directly off the untyped {@code BMap}) into wire bytes
     * plus the {@code data_coding} byte to send, without echoing user data in any thrown
     * message. Shared by every operation that carries a message body
     * ({@link #doSubmit}, {@link #doSubmitMulti}, {@link #doSubmitData}, {@link #doReplace}
     * — the last of which sends the bytes but has no {@code data_coding} field to put the
     * result in; see {@link #doReplace}).
     *
     * @param sms the {@code TextSms}/{@code BinarySms} record value
     * @param maxBodyLength the wire limit for this operation's body (254 octets for
     *     {@code submit_sm}/{@code submit_multi}'s {@code short_message}; 65535 for
     *     {@code data_sm}'s {@code message_payload} TLV)
     */
    private static BodyResult composeBody(BMap<BString, Object> sms, int maxBodyLength)
            throws InvalidRequest {
        BString textVal = sms.getStringValue(StringUtils.fromString("shortMessage"));
        String shortMessage = textVal == null ? null : textVal.getValue();
        Object bytesVal = sms.get(StringUtils.fromString("shortMessageBytes"));
        byte[] shortMessageBytes = bytesVal == null ? null : ((BArray) bytesVal).getBytes();
        boolean hasText = shortMessage != null;
        boolean hasBytes = shortMessageBytes != null;
        if (hasText == hasBytes) {
            throw new InvalidRequest(hasText
                    ? "OutboundSms must set exactly one of shortMessage or shortMessageBytes, not both"
                    : "OutboundSms must set one of shortMessage or shortMessageBytes");
        }

        byte[] body;
        byte dataCoding;
        boolean udhi;
        if (hasBytes) {
            Object dcObj = sms.get(StringUtils.fromString("dataCoding"));
            if (dcObj == null) {
                throw new InvalidRequest("shortMessageBytes requires dataCoding (the raw data_coding "
                        + "byte for the pre-encoded payload)");
            }
            long dcLong = (Long) dcObj;
            // Range-check BEFORE narrowing: (byte) a huge value would silently wrap.
            if (dcLong < 0 || dcLong > 0xFF) {
                throw new InvalidRequest("dataCoding must be 0-255, got " + dcLong);
            }
            Object udhiObj = sms.get(StringUtils.fromString("udhi"));
            udhi = udhiObj instanceof Boolean b && b;
            body = shortMessageBytes;
            dataCoding = (byte) (int) dcLong;
        } else {
            Object dcObj = sms.get(StringUtils.fromString("dataCoding"));
            if (dcObj != null) {
                throw new InvalidRequest("dataCoding is only for shortMessageBytes; with shortMessage "
                        + "the encoding field decides the data_coding");
            }
            Object udhiObj = sms.get(StringUtils.fromString("udhi"));
            if (udhiObj instanceof Boolean b && b) {
                throw new InvalidRequest("udhi requires shortMessageBytes (a BinarySms): this client "
                        + "encodes shortMessage itself and produces no UDH, so UDHI would declare a "
                        + "header that does not exist");
            }
            String encoding = str(sms, "encoding", "LATIN1");
            body = encode(shortMessage, encoding);
            dataCoding = switch (encoding) {
                case "ASCII" -> (byte) 0x01;
                case "LATIN1" -> (byte) 0x03;
                case "UCS2" -> (byte) 0x08;
                default -> throw new InvalidRequest("unknown encoding: " + encoding);
            };
            udhi = false;
        }
        if (body.length == 0) {
            // sm_length = 0 means "the payload is in the message_payload TLV" (SMPP v3.4
            // 5.2.21), which this operation either never sets (submit/replace) or IS the
            // TLV already carrying the (then-empty) payload (submitData) - either way an
            // empty body is a local input error, not something worth a round trip.
            throw new InvalidRequest((hasBytes ? "shortMessageBytes" : "shortMessage") + " must not be empty");
        }
        if (body.length > maxBodyLength) {
            throw new InvalidRequest("message body is " + body.length + " octets encoded; this "
                    + "operation carries at most " + maxBodyLength);
        }
        return new BodyResult(body, dataCoding, udhi);
    }

    /**
     * Encodes text for the wire, rejecting (never silently substituting) unencodable
     * characters. {@code String.getBytes(ISO_8859_1)} replaces what it cannot encode with
     * {@code ?} — a subscriber-visible corruption — so encodability is checked per UTF-16
     * code unit and the failure names the index, not the character.
     */
    private static byte[] encode(String text, String encoding) throws InvalidRequest {
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
                // UTF-16BE: two octets per UTF-16 code unit - surrogate pairs (emoji) count
                // as two units, i.e. four octets.
                return text.getBytes(StandardCharsets.UTF_16BE);
            }
            default -> throw new InvalidRequest("unknown encoding: " + encoding);
        }
    }

    private static byte registeredDeliveryByte(String name) throws InvalidRequest {
        return switch (name) {
            case "NONE" -> (byte) 0x00;
            case "ON_SUCCESS_OR_FAILURE" -> (byte) 0x01;
            case "ON_FAILURE_ONLY" -> (byte) 0x02;
            default -> throw new InvalidRequest("unknown registeredDelivery: " + name);
        };
    }

    private static String readServiceType(BMap<BString, Object> sms) throws InvalidRequest {
        String serviceType = str(sms, "serviceType", "");
        checkAscii("serviceType", serviceType);
        checkLength("serviceType", serviceType, StringParameter.SERVICE_TYPE);
        return serviceType;
    }

    private static String readValidityPeriod(BMap<BString, Object> sms) throws InvalidRequest {
        BString v = sms.getStringValue(StringUtils.fromString("validityPeriod"));
        String validityPeriod = v == null ? null : v.getValue();
        if (validityPeriod != null && !isValidSmppTime(validityPeriod)) {
            // VALIDITY_PERIOD is the one pre-checked field with isRangeMinAndMax == false:
            // jsmpp accepts only the empty string or EXACTLY 16 characters (SMPP v3.4
            // 7.1.1) - validate the exact shape locally so jsmpp's own validator (which
            // echoes the raw value) never becomes reachable.
            throw new InvalidRequest("validityPeriod must be empty or exactly 16 characters in the SMPP "
                    + "time format YYMMDDhhmmsstnnp (section 7.1.1), e.g. absolute 240115143000000+ "
                    + "or relative 000000020000000R");
        }
        return validityPeriod;
    }

    /** SMPP v3.4 7.1.1 time format: 15 digits then one of {@code + - R}. */
    private static boolean isValidSmppTime(String value) {
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
     * Rejects any character above 0x7F, and NUL (0x00), in an address/C-octet field. jsmpp
     * validates these fields in UTF-16 code units but writes them with
     * {@code String.getBytes()} in the JVM default charset - a non-ASCII character would
     * ship more octets than either validator counted, silently overflowing the field on the
     * wire. NUL is rejected for a different, wire-framing reason: a C-octet-string is
     * NUL-terminated on the wire (SMPP v3.4 3.1), so an embedded NUL would silently truncate
     * the field at that point - jsmpp's own {@code StringValidator} checks only length, never
     * content, so nothing else catches this. Names the field and the index, never the value
     * (MSISDNs/sender IDs are not for logs/error messages).
     */
    private static void checkAscii(String field, String value) throws InvalidRequest {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 0x7F) {
                throw new InvalidRequest(field + " contains a non-ASCII character at index " + i
                        + "; SMPP address and C-octet fields must be ASCII");
            }
            if (c == 0x00) {
                throw new InvalidRequest(field + " contains an embedded NUL at index " + i
                        + "; SMPP C-octet-string fields are NUL-terminated on the wire");
            }
        }
    }

    /**
     * jsmpp's limit for the parameter, adjusted for the C-octet asymmetry: a C-octet
     * string's max INCLUDES the terminating NUL (rejects {@code length >= max}), an octet
     * string's does not (rejects {@code > max}). Exact only because {@link #checkAscii}
     * runs first: for ASCII, UTF-16 code units and wire octets coincide.
     */
    private static int maxLength(StringParameter p) {
        return p.getType() == StringType.C_OCTET_STRING ? p.getMax() - 1 : p.getMax();
    }

    private static void checkLength(String field, String value, StringParameter p) throws InvalidRequest {
        int max = maxLength(p);
        if (value.length() > max) {
            throw new InvalidRequest(field + " is " + value.length() + " characters; the SMPP limit is "
                    + max);
        }
    }

    private static String required(String value, String field) throws InvalidRequest {
        if (value == null || value.isEmpty()) {
            throw new InvalidRequest(field + " is required and must not be empty");
        }
        return value;
    }

    private static TypeOfNumber typeOfNumber(String name, String field) throws InvalidRequest {
        // The Ballerina TypeOfNumber enum's values equal its member names (TON_INTERNATIONAL), so
        // stripping the prefix here is the entire mapping onto jsmpp's TypeOfNumber names.
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

    /**
     * Renames jsmpp's {@code MessageState} (query_sm_resp's {@code message_state}) onto
     * this module's {@code DeliveryReceiptStatus} — see {@code QueryResult.messageState}'s
     * doc in types.bal for why the two are deliberately the same enum. jsmpp's
     * {@code MessageState} additionally defines {@code SCHEDULED} and {@code SKIPPED},
     * which have no counterpart among {@code DeliveryReceiptStatus}'s eight Appendix-B
     * members; both fold into {@code UNKNOWN} (a deliberate, documented deviation — an
     * SMSC reporting either is telling you the message has not reached a settled state,
     * which is exactly what {@code UNKNOWN} means here).
     */
    private static String mapMessageState(MessageState state) {
        if (state == MessageState.ENROUTE) {
            return "ENROUTE";
        } else if (state == MessageState.DELIVERED) {
            return "DELIVRD";
        } else if (state == MessageState.EXPIRED) {
            return "EXPIRED";
        } else if (state == MessageState.DELETED) {
            return "DELETED";
        } else if (state == MessageState.UNDELIVERABLE) {
            return "UNDELIV";
        } else if (state == MessageState.ACCEPTED) {
            return "ACCEPTD";
        } else if (state == MessageState.REJECTED) {
            return "REJECTD";
        }
        // UNKNOWN, SCHEDULED, SKIPPED, and any future addition all fold here.
        return "UNKNOWN";
    }

    private static String str(BMap<BString, Object> map, String key, String fallback) {
        BString v = map.getStringValue(StringUtils.fromString(key));
        return v == null ? fallback : v.getValue();
    }

    private static double decimalValue(BMap<BString, Object> map, String key) {
        return ((BDecimal) map.get(StringUtils.fromString(key))).floatValue();
    }

    private static BindType toBindType(String mode) {
        return switch (mode) {
            case "TRANSMITTER" -> BindType.BIND_TX;
            case "TRANSCEIVER" -> BindType.BIND_TRX;
            default -> BindType.BIND_RX;
        };
    }

    // ------------------------------------------------------------------
    // Native-data accessors
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static AtomicReference<SMPPSession> session(BObject client) {
        return (AtomicReference<SMPPSession>) client.getNativeData(NATIVE_SESSION);
    }

    private static String boundBindType(BObject client) {
        return (String) client.getNativeData(NATIVE_BIND_TYPE);
    }

    private static AtomicBoolean usable(BObject client) {
        return (AtomicBoolean) client.getNativeData(NATIVE_USABLE);
    }

    private static AtomicBoolean closed(BObject client) {
        return (AtomicBoolean) client.getNativeData(NATIVE_CLOSED);
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<ObservedConnection> observedConn(BObject client) {
        return (AtomicReference<ObservedConnection>) client.getNativeData(NATIVE_OBSERVED_CONN);
    }

    private static BError detailError(String message, String failureMode, Integer commandStatus,
            boolean possiblySubmitted) {
        java.util.Map<String, Object> detail = new java.util.HashMap<>();
        detail.put("failureMode", failureMode);
        if (commandStatus != null) {
            detail.put("commandStatus", (long) (int) commandStatus);
        }
        detail.put("possiblySubmitted", possiblySubmitted);
        return ModuleUtils.createError(message, detail);
    }

    /**
     * The shared pre-flight gate every submit-family extern runs before touching jsmpp:
     * closed, never-bound, wrong bind type, and abandoned-link all fail fast, locally,
     * with no round trip and no ambiguity about whether anything reached the wire.
     *
     * @return the {@code Error} to return immediately, or {@code null} if the call may proceed
     */
    private static BError precheck(BObject client) {
        if (closed(client).get()) {
            return detailError("cannot use this client: it has been closed", "INVALID_REQUEST", null, false);
        }
        SMPPSession session = session(client).get();
        if (session == null) {
            return detailError("cannot use this client: it did not connect/bind successfully "
                    + "(check the Error returned from init())", "INVALID_REQUEST", null, false);
        }
        if ("RECEIVER".equals(boundBindType(client))) {
            return detailError("cannot submit on a RECEIVER-bound client: this operation requires "
                    + "bindType: TRANSMITTER or TRANSCEIVER in the ClientConfig", "INVALID_REQUEST",
                    null, false);
        }
        if (!usable(client).get()) {
            // Latched permanently at the first observed drop (see the class doc) - a
            // Client never recovers this on its own, so LINK_ABANDONED (not LINK_DOWN) is
            // always the honest answer once we get here.
            return detailError("cannot use this client: the SMSC link is down - a Client does not "
                    + "automatically reconnect; close() this one and create a new Client",
                    "LINK_ABANDONED", null, false);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // The externs
    // ------------------------------------------------------------------

    /**
     * {@code Client.init}. Connects and binds synchronously; by the time this returns
     * {@code null}, the session is bound and every other extern in this class is ready to
     * use.
     */
    public static Object init(BObject client, BMap<BString, Object> config, Object tls) {
        String systemId = str(config, "systemId", "");
        String password = str(config, "password", "");
        String systemType = str(config, "systemType", "");
        try {
            validateCredentials(systemId, password, systemType);
        } catch (IllegalArgumentException e) {
            return ModuleUtils.createError(e.getMessage());
        }

        // Captured once, as a plain immutable String, rather than storing the caller's
        // mutable ClientConfig BMap and re-reading bindType from it on every precheck(): a
        // BMap is a live reference, and the Ballerina caller can keep mutating the record
        // they passed to `new Client(...)` after construction. The actual wire-level bind
        // negotiated with the SMSC (below) never changes after connectAndBind() succeeds, so
        // precheck()'s local bindType-based validation must be pinned to what was ACTUALLY
        // bound, not whatever the config record says right now.
        String bindTypeStr = str(config, "bindType", "TRANSCEIVER");

        AtomicBoolean usable = new AtomicBoolean(false);
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicReference<SMPPSession> sessionRef = new AtomicReference<>();
        AtomicReference<ObservedConnection> observedConn = new AtomicReference<>();
        // All native-data writes happen here, once, single-threaded - see the class doc.
        client.addNativeData(NATIVE_BIND_TYPE, bindTypeStr);
        client.addNativeData(NATIVE_USABLE, usable);
        client.addNativeData(NATIVE_CLOSED, closed);
        client.addNativeData(NATIVE_SESSION, sessionRef);
        client.addNativeData(NATIVE_OBSERVED_CONN, observedConn);

        int bindTimeoutMillis = (int) (decimalValue(config, "bindTimeout") * 1000);
        long transactionTimeoutMillis = (long) (decimalValue(config, "transactionTimeout") * 1000);

        AtomicReference<Runnable> onTransportDeath = new AtomicReference<>();
        AtomicReference<ObservedConnection> attemptConn = new AtomicReference<>();
        SMPPSession session;
        try {
            session = newSession(tls, bindTimeoutMillis, onTransportDeath, attemptConn);
        } catch (Exception e) {
            return ModuleUtils.createError("failed to open a connection to the SMSC: " + e.getMessage());
        }
        session.setMessageReceiverListener(NOOP_RECEIVER_LISTENER);
        // Own drop signal, independent of the rebind loop this Client does not have: once
        // flipped false it stays false for this session's whole life (see precheck) - every
        // later submit-family call fails fast with LINK_ABANDONED instead of rediscovering
        // the drop itself on its own jsmpp call.
        onTransportDeath.set(() -> usable.set(false));
        session.addSessionStateListener((newState, oldState, source) -> {
            if (newState == SessionState.CLOSED && !closed.get()) {
                usable.set(false);
            }
        });
        session.setEnquireLinkTimer((int) (decimalValue(config, "enquireLinkInterval") * 1000));
        session.setPduProcessorDegree(PDU_PROCESSOR_DEGREE);
        // See ClientConfig.transactionTimeout's doc: this one jsmpp timer is used directly
        // for both submit-family waits and jsmpp's own housekeeping - close() is bounded
        // independently by its own watchdog regardless of this value.
        session.setTransactionTimer(transactionTimeoutMillis);

        String host = str(config, "host", "");
        int port = (int) ((Long) config.getIntValue(StringUtils.fromString("port"))).longValue();
        BindType bindType = toBindType(bindTypeStr);
        try {
            // addressRange stays null - jsmpp's connectAndBind has one failure branch (a
            // PDUException from an invalid addressRange) that leaves the socket open and
            // the reader thread running; a null addressRange makes that branch
            // unreachable, and validateCredentials above already covers the other fields
            // that branch could otherwise raise on (same rationale as NativeListener.bind).
            session.connectAndBind(host, port, bindType, systemId, password, systemType,
                    TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, null, bindTimeoutMillis);
        } catch (Exception e) {
            return ModuleUtils.createError("failed to connect/bind to SMSC: " + e.getMessage());
        }

        sessionRef.set(session);
        observedConn.set(attemptConn.get());
        usable.set(true);
        return null;
    }

    /**
     * Rejects an oversized {@code systemId}/{@code password}/{@code systemType} before
     * {@link #init} ever calls {@code connectAndBind}. jsmpp's own {@code StringValidator}
     * would catch the same violation, but its exception message embeds the raw (invalid)
     * value verbatim — a credential-leak path via logs/error messages. Mirrors
     * {@code NativeListener.validateCredentials} exactly (duplicated, not shared: this is
     * this package's own pre-send validation).
     */
    private static void validateCredentials(String systemId, String password, String systemType) {
        requireAscii("systemId", systemId);
        requireAscii("password", password);
        requireAscii("systemType", systemType);
        if (systemId.length() > MAX_SYSTEM_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "systemId exceeds the maximum length of " + MAX_SYSTEM_ID_LENGTH + " characters");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "password exceeds the maximum length of " + MAX_PASSWORD_LENGTH + " characters");
        }
        if (systemType.length() > MAX_SYSTEM_TYPE_LENGTH) {
            throw new IllegalArgumentException(
                    "systemType exceeds the maximum length of " + MAX_SYSTEM_TYPE_LENGTH + " characters");
        }
    }

    private static void requireAscii(String field, String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) {
                throw new IllegalArgumentException(field + " contains a non-ASCII character at index "
                        + i + "; SMPP C-octet fields must be ASCII");
            }
        }
    }

    /** {@code Client.submit} — {@code submit_sm}. */
    public static Object submit(BObject client, BMap<BString, Object> sms) {
        BError pre = precheck(client);
        if (pre != null) {
            return pre;
        }
        try {
            return doSubmit(session(client).get(), sms);
        } catch (Exception e) {
            return SubmitErrorMapper.toError(SubmitErrorMapper.mapSubmitFailure(e, closed(client).get()));
        }
    }

    private static Object doSubmit(SMPPSession session, BMap<BString, Object> sms) throws Exception {
        BodyResult body = composeBody(sms, maxLength(StringParameter.SHORT_MESSAGE));
        AddrSpec dest = resolveAddress(
                sms.get(StringUtils.fromString("destinationAddress")), true, "destinationAddress");
        checkAscii("destinationAddress", dest.addr);
        checkLength("destinationAddress", dest.addr, StringParameter.DESTINATION_ADDR);
        AddrSpec src = resolveAddress(sms.get(StringUtils.fromString("sourceAddress")), false, "sourceAddress");
        checkAscii("sourceAddress", src.addr);
        checkLength("sourceAddress", src.addr, StringParameter.SOURCE_ADDR);
        String serviceType = readServiceType(sms);
        String validityPeriod = readValidityPeriod(sms);
        byte registeredDelivery = registeredDeliveryByte(str(sms, "registeredDelivery", "NONE"));
        byte esmClass = body.udhi ? (byte) 0x40 : (byte) 0x00;

        SubmitSmResult result = session.submitShortMessage(
                serviceType, src.typeOfNumber, src.numberingPlanIndicator, src.addr,
                dest.typeOfNumber, dest.numberingPlanIndicator, dest.addr,
                new ESMClass(esmClass), (byte) 0x00, (byte) 0x00, null, validityPeriod,
                new RegisteredDelivery(registeredDelivery), (byte) 0x00,
                new RawDataCoding(body.dataCoding), (byte) 0x00, body.bytes);
        BMap<BString, Object> out = ValueCreator.createRecordValue(ModuleUtils.getModule(), "SubmitResult");
        String messageId = result == null || result.getMessageId() == null ? "" : result.getMessageId();
        out.put(StringUtils.fromString("messageId"), StringUtils.fromString(messageId));
        return out;
    }

    /** {@code Client.submitMulti} — {@code submit_multi}. */
    public static Object submitMulti(BObject client, BMap<BString, Object> sms, BArray destinationAddresses) {
        BError pre = precheck(client);
        if (pre != null) {
            return pre;
        }
        try {
            return doSubmitMulti(session(client).get(), sms, destinationAddresses);
        } catch (Exception e) {
            return SubmitErrorMapper.toError(SubmitErrorMapper.mapSubmitFailure(e, closed(client).get()));
        }
    }

    private static Object doSubmitMulti(SMPPSession session, BMap<BString, Object> sms, BArray destinationAddresses)
            throws Exception {
        String[] addrs = destinationAddresses == null ? new String[0] : destinationAddresses.getStringArray();
        if (addrs.length == 0) {
            throw new InvalidRequest("destinationAddresses must contain at least one address");
        }
        BodyResult body = composeBody(sms, maxLength(StringParameter.SHORT_MESSAGE));
        AddrSpec src = resolveAddress(sms.get(StringUtils.fromString("sourceAddress")), false, "sourceAddress");
        checkAscii("sourceAddress", src.addr);
        checkLength("sourceAddress", src.addr, StringParameter.SOURCE_ADDR);

        Address[] jsmppDest = new Address[addrs.length];
        for (int i = 0; i < addrs.length; i++) {
            String addr = addrs[i];
            checkAscii("destinationAddresses[" + i + "]", addr);
            checkLength("destinationAddresses[" + i + "]", addr, StringParameter.DESTINATION_ADDR);
            if (addr.isEmpty()) {
                throw new InvalidRequest("destinationAddresses[" + i + "] must not be empty");
            }
            // Plain strings, so the TON_INTERNATIONAL/NPI_ISDN shorthand applies uniformly
            // to every destination - see submitMulti's doc in client.bal.
            jsmppDest[i] = new Address(TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, addr);
        }
        String serviceType = readServiceType(sms);
        String validityPeriod = readValidityPeriod(sms);
        byte registeredDelivery = registeredDeliveryByte(str(sms, "registeredDelivery", "NONE"));
        byte esmClass = body.udhi ? (byte) 0x40 : (byte) 0x00;

        SubmitMultiResult result = session.submitMultiple(
                serviceType, src.typeOfNumber, src.numberingPlanIndicator, src.addr, jsmppDest,
                new ESMClass(esmClass), (byte) 0x00, (byte) 0x00, null, validityPeriod,
                new RegisteredDelivery(registeredDelivery), ReplaceIfPresentFlag.DEFAULT,
                new RawDataCoding(body.dataCoding), (byte) 0x00, body.bytes);

        BMap<BString, Object> out = ValueCreator.createRecordValue(ModuleUtils.getModule(), "MultiSubmitResult");
        String messageId = result == null || result.getMessageId() == null ? "" : result.getMessageId();
        out.put(StringUtils.fromString("messageId"), StringUtils.fromString(messageId));
        List<String> unsuccessful = new ArrayList<>();
        UnsuccessDelivery[] unsuccess = result == null ? null : result.getUnsuccessDeliveries();
        if (unsuccess != null) {
            for (UnsuccessDelivery u : unsuccess) {
                Address a = u.getDestinationAddress();
                unsuccessful.add(a == null ? "" : a.getAddress());
            }
        }
        out.put(StringUtils.fromString("unsuccessfulAddresses"),
                StringUtils.fromStringArray(unsuccessful.toArray(new String[0])));
        return out;
    }

    /** {@code Client.submitData} — {@code data_sm}. */
    public static Object submitData(BObject client, BMap<BString, Object> data) {
        BError pre = precheck(client);
        if (pre != null) {
            return pre;
        }
        try {
            return doSubmitData(session(client).get(), data);
        } catch (Exception e) {
            return SubmitErrorMapper.toError(SubmitErrorMapper.mapSubmitFailure(e, closed(client).get()));
        }
    }

    private static Object doSubmitData(SMPPSession session, BMap<BString, Object> sms) throws Exception {
        // data_sm carries its payload only in the message_payload TLV - there is no
        // short_message field on data_sm at all (see AbstractSession.dataShortMessage's
        // signature), so the 254-octet SHORT_MESSAGE cap does not apply here.
        BodyResult body = composeBody(sms, MAX_MESSAGE_PAYLOAD_LENGTH);
        AddrSpec dest = resolveAddress(
                sms.get(StringUtils.fromString("destinationAddress")), true, "destinationAddress");
        checkAscii("destinationAddress", dest.addr);
        checkLength("destinationAddress", dest.addr, StringParameter.DESTINATION_ADDR);
        AddrSpec src = resolveAddress(sms.get(StringUtils.fromString("sourceAddress")), false, "sourceAddress");
        checkAscii("sourceAddress", src.addr);
        checkLength("sourceAddress", src.addr, StringParameter.SOURCE_ADDR);
        String serviceType = readServiceType(sms);
        // data_sm has no validity_period field on the wire - readValidityPeriod still
        // VALIDATES sms.validityPeriod (a malformed value is still a local input error
        // worth rejecting), but the result is intentionally not sent; see client.bal's
        // submitData doc.
        readValidityPeriod(sms);
        byte registeredDelivery = registeredDeliveryByte(str(sms, "registeredDelivery", "NONE"));
        byte esmClass = body.udhi ? (byte) 0x40 : (byte) 0x00;

        DataSmResult result = session.dataShortMessage(
                serviceType, src.typeOfNumber, src.numberingPlanIndicator, src.addr,
                dest.typeOfNumber, dest.numberingPlanIndicator, dest.addr, new ESMClass(esmClass),
                new RegisteredDelivery(registeredDelivery), new RawDataCoding(body.dataCoding),
                new OptionalParameter.Message_payload(body.bytes));

        BMap<BString, Object> out = ValueCreator.createRecordValue(ModuleUtils.getModule(), "SubmitResult");
        String messageId = result == null || result.getMessageId() == null ? "" : result.getMessageId();
        out.put(StringUtils.fromString("messageId"), StringUtils.fromString(messageId));
        return out;
    }

    /** {@code Client.queryStatus} — {@code query_sm}. */
    public static Object queryStatus(BObject client, BString messageId, BString sourceAddress) {
        BError pre = precheck(client);
        if (pre != null) {
            return pre;
        }
        try {
            return doQuery(session(client).get(), messageId, sourceAddress);
        } catch (Exception e) {
            return SubmitErrorMapper.toError(SubmitErrorMapper.mapSubmitFailure(e, closed(client).get()));
        }
    }

    private static Object doQuery(SMPPSession session, BString messageIdVal, BString sourceAddrVal)
            throws Exception {
        String messageId = required(messageIdVal == null ? null : messageIdVal.getValue(), "messageId");
        checkAscii("messageId", messageId);
        checkLength("messageId", messageId, StringParameter.MESSAGE_ID);
        AddrSpec src = resolvePlainAddress(sourceAddrVal == null ? "" : sourceAddrVal.getValue());
        checkAscii("sourceAddress", src.addr);
        checkLength("sourceAddress", src.addr, StringParameter.SOURCE_ADDR);

        QuerySmResult result = session.queryShortMessage(
                messageId, src.typeOfNumber, src.numberingPlanIndicator, src.addr);
        BMap<BString, Object> out = ValueCreator.createRecordValue(ModuleUtils.getModule(), "QueryResult");
        String finalDate = result.getFinalDate();
        if (finalDate != null && !finalDate.isEmpty()) {
            out.put(StringUtils.fromString("finalDate"), StringUtils.fromString(finalDate));
        } // else: leave nil, matching QueryResult.finalDate's default of ()
        out.put(StringUtils.fromString("messageState"),
                StringUtils.fromString(mapMessageState(result.getMessageState())));
        out.put(StringUtils.fromString("errorCode"), (long) (result.getErrorCode() & 0xFF));
        return out;
    }

    /** {@code Client.cancel} — {@code cancel_sm}. */
    public static Object cancel(BObject client, BString messageId, BString sourceAddress, BString destinationAddress) {
        BError pre = precheck(client);
        if (pre != null) {
            return pre;
        }
        try {
            return doCancel(session(client).get(), messageId, sourceAddress, destinationAddress);
        } catch (Exception e) {
            return SubmitErrorMapper.toError(SubmitErrorMapper.mapSubmitFailure(e, closed(client).get()));
        }
    }

    private static Object doCancel(SMPPSession session, BString messageIdVal, BString sourceAddrVal,
            BString destAddrVal) throws Exception {
        String messageId = required(messageIdVal == null ? null : messageIdVal.getValue(), "messageId");
        checkAscii("messageId", messageId);
        checkLength("messageId", messageId, StringParameter.MESSAGE_ID);
        AddrSpec src = resolvePlainAddress(sourceAddrVal == null ? "" : sourceAddrVal.getValue());
        checkAscii("sourceAddress", src.addr);
        checkLength("sourceAddress", src.addr, StringParameter.SOURCE_ADDR);
        String destValue = required(destAddrVal == null ? null : destAddrVal.getValue(), "destinationAddress");
        AddrSpec dst = resolvePlainAddress(destValue);
        checkAscii("destinationAddress", dst.addr);
        checkLength("destinationAddress", dst.addr, StringParameter.DESTINATION_ADDR);

        // cancel_sm's service_type is not exposed on Client.cancel's signature (see
        // client.bal) - "" (the SMSC default) matches every other operation's default.
        session.cancelShortMessage("", messageId,
                src.typeOfNumber, src.numberingPlanIndicator, src.addr,
                dst.typeOfNumber, dst.numberingPlanIndicator, dst.addr);
        return null;
    }

    /** {@code Client.replace} — {@code replace_sm}. */
    public static Object replace(BObject client, BString messageId, BString sourceAddress,
            BMap<BString, Object> sms) {
        BError pre = precheck(client);
        if (pre != null) {
            return pre;
        }
        try {
            return doReplace(session(client).get(), messageId, sourceAddress, sms);
        } catch (Exception e) {
            return SubmitErrorMapper.toError(SubmitErrorMapper.mapSubmitFailure(e, closed(client).get()));
        }
    }

    private static Object doReplace(SMPPSession session, BString messageIdVal, BString sourceAddrVal,
            BMap<BString, Object> sms) throws Exception {
        String messageId = required(messageIdVal == null ? null : messageIdVal.getValue(), "messageId");
        checkAscii("messageId", messageId);
        checkLength("messageId", messageId, StringParameter.MESSAGE_ID);
        AddrSpec src = resolvePlainAddress(sourceAddrVal == null ? "" : sourceAddrVal.getValue());
        checkAscii("sourceAddress", src.addr);
        checkLength("sourceAddress", src.addr, StringParameter.SOURCE_ADDR);
        // replace_sm has no destination_addr/service_type/data_coding/esm_class field on
        // the wire (SMPP v3.4 4.10) - only the body bytes, registeredDelivery, and
        // validityPeriod carry over; sms.destinationAddress/serviceType and BinarySms's
        // dataCoding/udhi are accepted (OutboundSms structurally requires destinationAddress) but
        // have nothing to bind to and are not sent. See client.bal's replace doc.
        BodyResult body = composeBody(sms, maxLength(StringParameter.SHORT_MESSAGE));
        String validityPeriod = readValidityPeriod(sms);
        byte registeredDelivery = registeredDeliveryByte(str(sms, "registeredDelivery", "NONE"));

        session.replaceShortMessage(messageId,
                src.typeOfNumber, src.numberingPlanIndicator, src.addr, null, validityPeriod,
                new RegisteredDelivery(registeredDelivery), (byte) 0x00, body.bytes);
        return null;
    }

    /**
     * {@code Client.close} — unbinds and closes the session, bounded by a force-close
     * watchdog against an unresponsive peer (see {@link #CLOSE_WATCHDOG_MS}). Idempotent.
     */
    public static Object close(BObject client) {
        if (!closed(client).compareAndSet(false, true)) {
            return null; // already closed; matches Listener's stop() idempotence
        }
        usable(client).set(false);
        SMPPSession session = session(client).get();
        if (session == null) {
            return null; // init() never got far enough to have a session to close
        }
        ObservedConnection conn = observedConn(client).get();
        Thread watchdog = null;
        if (conn != null) {
            Thread w = new Thread(() -> {
                try {
                    Thread.sleep(CLOSE_WATCHDOG_MS);
                } catch (InterruptedException e) {
                    return; // unbindAndClose finished in time - nothing to force
                }
                conn.forceClose();
            }, "smpp-client-close-watchdog");
            w.setDaemon(true);
            w.start();
            watchdog = w;
        }
        try {
            session.unbindAndClose();
            return null;
        } catch (Exception e) {
            return ModuleUtils.createError("failed to unbind SMSC session: " + e.getMessage());
        } finally {
            if (watchdog != null) {
                watchdog.interrupt();
            }
        }
    }

    // ------------------------------------------------------------------
    // Connection setup (plain/TLS), mirroring NativeListener.newSession
    // ------------------------------------------------------------------

    /**
     * A fresh session for this {@code Client}'s one and only bind attempt (a {@code Client}
     * never rebinds - see the class doc). Plaintext uses a connect-timeout-bounded factory
     * (jsmpp's stock plaintext connect is unbounded); TLS builds a factory with the same
     * connect bound. Every connection is wrapped in {@link ObservedConnection} - the
     * connector's own transport-death signal, independent of jsmpp's
     * {@code SessionStateListener} (see that class's doc for the wedge it guards against),
     * and the source of the raw socket {@link #close} force-closes against.
     */
    @SuppressWarnings("unchecked")
    private static SMPPSession newSession(Object tls, int connectTimeoutMillis,
            AtomicReference<Runnable> onTransportDeath, AtomicReference<ObservedConnection> attemptConn)
            throws Exception {
        RawConnectionFactory delegate = tls == null
                ? new SmppPlainConnectionFactory(connectTimeoutMillis)
                : buildSslFactory((BMap<BString, Object>) tls, connectTimeoutMillis);
        return new SMPPSession((host, port) -> {
            RawConnectionFactory.RawConnection raw = delegate.createRawConnection(host, port);
            ObservedConnection observed =
                    new ObservedConnection(raw.connection(), raw.rawSocket(), onTransportDeath);
            attemptConn.set(observed);
            return observed;
        });
    }

    /** Field names here mirror {@code types.bal}'s internal {@code ResolvedTls} record exactly. */
    private static SmppSslConnectionFactory buildSslFactory(BMap<BString, Object> tls, int connectTimeoutMillis)
            throws Exception {
        return SmppSslConnectionFactory.create(
                tlsStr(tls, "trustStorePath"),
                tlsStr(tls, "trustStorePassword").toCharArray(),
                tlsStr(tls, "trustCertPath"),
                tlsStr(tls, "keyStorePath"),
                tlsStr(tls, "keyStorePassword").toCharArray(),
                tlsStringArray(tls, "protocolVersions"),
                tlsStringArray(tls, "ciphers"),
                tlsBool(tls, "trustAll"),
                tlsBool(tls, "verifyHostName"),
                connectTimeoutMillis);
    }

    // The TLS readers below are STRICT: ResolvedTls (types.bal) is a closed record with no
    // nilable fields, so a null here means the .bal record and this native reader have
    // drifted out of sync - fail loudly rather than defaulting (a silently-defaulted
    // verifyHostName of false would turn hostname verification off).
    private static Object tlsRequire(BMap<BString, Object> tls, String key) {
        Object v = tls.get(StringUtils.fromString(key));
        if (v == null) {
            throw new IllegalStateException("internal error: TLS field '" + key + "' missing from ResolvedTls");
        }
        return v;
    }

    private static String tlsStr(BMap<BString, Object> tls, String key) {
        return ((BString) tlsRequire(tls, key)).getValue();
    }

    private static boolean tlsBool(BMap<BString, Object> tls, String key) {
        return (Boolean) tlsRequire(tls, key);
    }

    private static String[] tlsStringArray(BMap<BString, Object> tls, String key) {
        BArray arr = (BArray) tlsRequire(tls, key); // present-but-empty is valid (JVM defaults)
        String[] out = new String[(int) arr.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = arr.getBString(i).getValue();
        }
        return out;
    }
}
