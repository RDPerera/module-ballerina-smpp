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

package io.ballerina.stdlib.smpp;

import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.PredefinedTypes;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import org.jsmpp.bean.AbstractSmCommand;
import org.jsmpp.bean.DeliverSm;
import org.jsmpp.bean.DeliveryReceipt;
import org.jsmpp.bean.OptionalParameter;
import org.jsmpp.util.DefaultDecomposer;
import org.jsmpp.util.InvalidDeliveryReceiptException;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Maps a jsmpp inbound PDU ({@code deliver_sm} / {@code data_sm}) to the Ballerina
 * {@code smpp:Sms} record, factoring out the address/data_coding/short_message-vs-
 * message_payload precedence logic, UDH bit surfacing, and delivery-receipt parsing
 * described in {@code docs/architecture.md}'s "The Sms record" section.
 *
 * <p>Shared by the {@code listener} native package (mapping every inbound
 * {@code deliver_sm}/{@code data_sm}) and available to the {@code client} native package
 * for any future receive-shaped operation that needs to render an {@code Sms} the same
 * way. Pure/static wherever possible so the decode and receipt-parsing logic is
 * JUnit-reachable without a Ballerina runtime or a jsmpp session.
 */
public final class SmsMapper {

    private SmsMapper() {
    }

    /**
     * Resolves the payload bytes for a PDU per the SMPP precedence rule: a PDU's
     * {@code message_payload} optional parameter (TLV 0x0424) takes priority over its
     * {@code short_message} field when both are present. This matters mainly for
     * messages too long to fit in {@code short_message} (which is capped at 254 octets),
     * and for {@code data_sm}, which only ever carries its payload in
     * {@code message_payload} (where {@code fallback} is empty, since DATA_SM has no
     * short_message).
     *
     * @param pdu the PDU to check for a message_payload TLV
     * @param fallback the bytes to use if no message_payload TLV is present
     * @return the resolved payload bytes
     */
    public static byte[] payloadBytes(AbstractSmCommand pdu, byte[] fallback) {
        OptionalParameter.Message_payload payload =
                pdu.getOptionalParameter(OptionalParameter.Message_payload.class);
        return payload != null ? payload.getValue() : fallback;
    }

    /**
     * Builds the Ballerina {@code smpp:Sms} record for one inbound PDU.
     *
     * @param pdu the {@code deliver_sm} or {@code data_sm} PDU
     * @param shortMessage the PDU's {@code short_message} bytes (empty for {@code data_sm})
     * @param deliveryReceipt whether this PDU is an SMSC delivery receipt
     * @param decodeGsm7 whether {@code data_coding 0x00} should be decoded as unpacked
     *     GSM 03.38 instead of the UTF-8 fallback (see {@code ConnectionConfig.decodeGsm7})
     * @return the populated {@code smpp:Sms} record value
     */
    public static BMap<BString, Object> toSms(AbstractSmCommand pdu, byte[] shortMessage,
            boolean deliveryReceipt, boolean decodeGsm7) {
        BMap<BString, Object> sms = ValueCreator.createRecordValue(ModuleUtils.getModule(), "Sms");
        sms.put(StringUtils.fromString("sourceAddress"), StringUtils.fromString(nullSafe(pdu.getSourceAddr())));
        sms.put(StringUtils.fromString("destinationAddress"), StringUtils.fromString(nullSafe(pdu.getDestAddress())));
        byte[] body = payloadBytes(pdu, shortMessage);
        sms.put(StringUtils.fromString("shortMessage"),
                StringUtils.fromString(decodeShortMessage(body, pdu.getDataCoding(), decodeGsm7)));
        // clone(): createArrayValue wraps (doesn't copy) the array, and jsmpp's bean
        // getters return their internal arrays - don't let the user-visible record share
        // a buffer with jsmpp internals, even though no post-dispatch mutator exists today.
        sms.put(StringUtils.fromString("shortMessageBytes"), ValueCreator.createArrayValue(body.clone()));
        sms.put(StringUtils.fromString("deliveryReceipt"), deliveryReceipt);
        // The receipted_message_id TLV (0x001E) - the spec's only guaranteed DLR
        // correlation key (5.3.2.12); the Appendix-B body id: is vendor specific.
        OptionalParameter.Receipted_message_id receiptedId =
                pdu.getOptionalParameter(OptionalParameter.Receipted_message_id.class);
        if (receiptedId != null && receiptedId.getValueAsString() != null) {
            sms.put(StringUtils.fromString("receiptedMessageId"),
                    StringUtils.fromString(receiptedId.getValueAsString()));
        }
        sms.put(StringUtils.fromString("properties"), toProperties(pdu));
        // A delivery receipt's structured fields, when this is one. Only deliver_sm carries the
        // Appendix-B receipt body (data_sm has no short_message), and jsmpp's parser is
        // deliver_sm-typed. Built after the base record so shortMessage/shortMessageBytes (the
        // raw receipt) are always populated regardless of whether the parse succeeds.
        if (deliveryReceipt && pdu instanceof DeliverSm ds) {
            BMap<BString, Object> receipt = buildReceipt(ds);
            if (receipt != null) {
                sms.put(StringUtils.fromString("receipt"), receipt);
            }
        }
        return sms;
    }

    /**
     * A plain holder for the mapped delivery-receipt fields, so the parse/mapping logic is a
     * pure function testable by JUnit (which has no Ballerina runtime and cannot build record
     * values). A {@code null} field means "absent from the receipt" - {@link #buildReceipt}
     * omits those, leaving the corresponding optional Ballerina field nil.
     */
    public static final class ReceiptFields {
        public final String id;
        public final Integer submitted;   // null = jsmpp's -1 "absent" sentinel
        public final Integer delivered;
        public final String submitDate;
        public final String doneDate;
        public final String finalStatus;  // jsmpp DeliveryReceiptState name; matches the Ballerina enum
        public final String errorCode;
        public final String text;

        ReceiptFields(String id, Integer submitted, Integer delivered, String submitDate,
                String doneDate, String finalStatus, String errorCode, String text) {
            this.id = id;
            this.submitted = submitted;
            this.delivered = delivered;
            this.submitDate = submitDate;
            this.doneDate = doneDate;
            this.finalStatus = finalStatus;
            this.errorCode = errorCode;
            this.text = text;
        }
    }

    /**
     * Parses an SMSC delivery receipt via jsmpp's own Appendix-B parser and maps its
     * {@link DeliveryReceipt} bean to a plain holder - the connector adds no interpretation.
     *
     * <p>LENIENT / NEVER-THROW: jsmpp's {@code getShortMessageAsDeliveryReceipt()} throws
     * {@link InvalidDeliveryReceiptException} on a non-conforming body, and can throw a bare
     * {@link RuntimeException} (e.g. a {@code NullPointerException} inside jsmpp when the
     * short_message is null) BEFORE that wrapping. This runs on a jsmpp PDU-processor
     * thread inside the dispatch path; a throw escaping here would become a negative
     * deliver_sm_resp and the SMSC would redeliver the malformed receipt forever. So both
     * are caught and mapped to {@code null} (no parsed receipt; the raw body stays on
     * {@code Sms.shortMessage}) - exactly what a jsmpp user gets when they catch the
     * exception.
     *
     * @param ds the delivery-receipt-flagged deliver_sm
     * @return the mapped fields, or {@code null} if jsmpp could not parse the receipt
     */
    public static ReceiptFields parseReceipt(DeliverSm ds) {
        // The whole parse-AND-map runs in the try so this method is STRUCTURALLY never-throw,
        // not merely never-throw-while-invariants-hold: the catch covers jsmpp's parse throws
        // (InvalidDeliveryReceiptException, and the bare NullPointerException it raises on a
        // null short_message before wrapping) AND anything a getter/mapping could throw now or
        // after a future jsmpp change. On any failure: no parsed receipt (the raw body stays on
        // Sms.shortMessage). A throw escaping here would NACK the receipt -> endless redelivery.
        try {
            // A receipt body follows the same message_payload-over-short_message precedence
            // as an ordinary message (SMPP v3.4 5.2.21) - jsmpp's own convenience method,
            // getShortMessageAsDeliveryReceipt(), reads ONLY short_message and misses a
            // receipt an SMSC sent via message_payload instead, so this resolves the same
            // precedence payloadBytes() already applies to Sms.shortMessage before handing
            // the bytes to jsmpp's decomposer directly.
            byte[] body = payloadBytes(ds, ds.getShortMessage());
            DeliveryReceipt dr = DefaultDecomposer.getInstance().deliveryReceipt(body);
            return new ReceiptFields(
                    emptyToNull(dr.getId()),
                    dr.getSubmitted() >= 0 ? dr.getSubmitted() : null,
                    dr.getDelivered() >= 0 ? dr.getDelivered() : null,
                    formatReceiptDate(dr.getSubmitDate()),
                    formatReceiptDate(dr.getDoneDate()),
                    dr.getFinalStatus() != null ? dr.getFinalStatus().name() : null,
                    emptyToNull(dr.getError()),
                    emptyToNull(dr.getText()));
        } catch (InvalidDeliveryReceiptException | RuntimeException e) {
            return null;
        }
    }

    /** Builds the Ballerina {@code DeliveryReceipt} record, or {@code null} if the parse failed. */
    private static BMap<BString, Object> buildReceipt(DeliverSm ds) {
        ReceiptFields f = parseReceipt(ds);
        if (f == null) {
            return null;
        }
        // The record build is also guarded: a `finalStatus` value not in the DeliveryReceiptStatus
        // enum (if jsmpp's DeliveryReceiptState ever drifts from the eight names) could make the
        // runtime reject the put with a BError - which, unguarded, would escape to the jsmpp
        // thread and NACK the receipt. Degrade to no parsed receipt instead of ever throwing.
        try {
            BMap<BString, Object> r = ValueCreator.createRecordValue(ModuleUtils.getModule(), "DeliveryReceipt");
            putStringIfPresent(r, "id", f.id);
            if (f.submitted != null) {
                r.put(StringUtils.fromString("submitted"), (long) (int) f.submitted);
            }
            if (f.delivered != null) {
                r.put(StringUtils.fromString("delivered"), (long) (int) f.delivered);
            }
            putStringIfPresent(r, "submitDate", f.submitDate);
            putStringIfPresent(r, "doneDate", f.doneDate);
            putStringIfPresent(r, "finalStatus", f.finalStatus);
            putStringIfPresent(r, "errorCode", f.errorCode);
            putStringIfPresent(r, "text", f.text);
            return r;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void putStringIfPresent(BMap<BString, Object> record, String key, String value) {
        if (value != null) {
            record.put(StringUtils.fromString(key), StringUtils.fromString(value));
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    /**
     * Re-serializes jsmpp's parsed receipt {@code Date} back to the raw wire format
     * ({@code yyMMddHHmm}). jsmpp parsed it in the JVM default timezone; formatting in the same
     * zone round-trips the digits, so this surfaces the wire value without inventing a
     * timezone-anchored instant. A fresh formatter per call keeps this thread-safe (receipts
     * are low volume); {@code SimpleDateFormat} is not shareable across the jsmpp pool threads.
     */
    private static String formatReceiptDate(Date date) {
        return date == null ? null : new SimpleDateFormat("yyMMddHHmm").format(date);
    }

    /**
     * Decodes {@code short_message}/{@code message_payload} bytes according to the PDU's
     * {@code data_coding}. Only the unambiguous single-byte-per-character encodings are
     * decoded precisely; the GSM 7-bit default alphabet (data_coding 0x00) falls back to
     * UTF-8 <em>unless</em> {@code decodeGsm7} is enabled (see the 3-arg overload), because
     * whether an SMSC sends packed 7-bit septets or one byte per character over SMPP varies
     * by vendor. Any other/unknown value falls back to UTF-8. The raw {@code dataCoding} value
     * is always surfaced via {@code Sms.properties} so a service can decode it itself.
     *
     * @param bytes the raw PDU payload bytes
     * @param dataCoding the PDU's {@code data_coding} value
     * @return the decoded text
     */
    // This 2-arg overload keeps the GSM-7-off default behaviour for callers/tests that
    // don't care about the opt-in flag.
    public static String decodeShortMessage(byte[] bytes, byte dataCoding) {
        return decodeShortMessage(bytes, dataCoding, false);
    }

    /**
     * As {@link #decodeShortMessage(byte[], byte)}, but when {@code decodeGsm7} is true a
     * {@code data_coding} of {@code 0x00} is decoded as unpacked GSM 03.38 (the 7-bit default
     * alphabet plus its extension table) instead of the UTF-8 fallback.
     *
     * @param bytes the raw PDU payload bytes
     * @param dataCoding the PDU's {@code data_coding} value
     * @param decodeGsm7 whether data_coding 0x00 should be decoded as unpacked GSM 03.38
     * @return the decoded text
     */
    public static String decodeShortMessage(byte[] bytes, byte dataCoding, boolean decodeGsm7) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        return switch (dataCoding & 0xFF) {
            case 0x00 -> decodeGsm7 ? decodeGsm0338Unpacked(bytes)
                                    : new String(bytes, StandardCharsets.UTF_8);
            case 0x01 -> new String(bytes, StandardCharsets.US_ASCII);   // IA5/ASCII
            case 0x03 -> new String(bytes, StandardCharsets.ISO_8859_1); // Latin-1
            case 0x08 -> new String(bytes, StandardCharsets.UTF_16BE);   // UCS2
            default -> new String(bytes, StandardCharsets.UTF_8);
        };
    }

    // --- GSM 03.38 (3GPP TS 23.038) default alphabet, unpacked (one septet per octet) ---

    /**
     * The 128-entry GSM 03.38 default alphabet as Unicode code points, index = septet value.
     * Position 0x1B (27) is the ESC to the extension table and has no character of its own;
     * it is handled specially by {@link #decodeGsm0338Unpacked} (never read from this array).
     * This source file is UTF-8 ({@code JavaCompile.options.encoding} is set accordingly in
     * {@code native/build.gradle}); the reference implementation this was ported from wrote
     * the same table with explicit Java unicode escape sequences to keep the file pure ASCII
     * instead — either representation is byte-for-byte equivalent once compiled.
     */
    private static final char[] GSM_DEFAULT_ALPHABET = (
            "@£$¥èéùìòÇ\nØø\rÅå"
          + "Δ_ΦΓΛΩΠΨΣΘΞ￿ÆæßÉ"
          + " !\"#¤%&'()*+,-./"
          + "0123456789:;<=>?"
          + "¡ABCDEFGHIJKLMNO"
          + "PQRSTUVWXYZÄÖÑÜ§"
          + "¿abcdefghijklmno"
          + "pqrstuvwxyzäöñüà").toCharArray();

    private static final int GSM_ESCAPE = 0x1B;

    /**
     * Maps a byte following the GSM ESC (0x1B) to its extension-table character, or
     * {@code '\0'} if the pair is not a defined extension. Kept as a switch rather than a map:
     * only ten entries, and this stays allocation-free on the hot decode path.
     */
    private static char gsmExtension(int b) {
        return switch (b) {
            case 0x0a -> '\f';     // form feed
            case 0x14 -> '^';
            case 0x28 -> '{';
            case 0x29 -> '}';
            case 0x2f -> '\\';
            case 0x3c -> '[';
            case 0x3d -> '~';
            case 0x3e -> ']';
            case 0x40 -> '|';
            case 0x65 -> '€'; // euro sign
            default -> '\0';
        };
    }

    /**
     * Decodes unpacked GSM 03.38: each octet carries one 7-bit septet in its low bits. An ESC
     * (0x1B) followed by a defined extension byte yields the extension character (and consumes
     * that byte); an ESC not forming a defined extension is rendered as a space, with the next
     * byte processed normally on the following iteration — per 3GPP TS 23.038. This does NOT
     * handle packed 7-bit (septets bit-packed across octet boundaries), which is a distinct
     * on-wire format the connector does not claim to decode.
     *
     * @param bytes the unpacked GSM-7 payload
     * @return the decoded text
     */
    public static String decodeGsm0338Unpacked(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0x7F;   // low 7 bits; a stray high bit is treated as padding
            if (b == GSM_ESCAPE) {
                char ext = (i + 1 < bytes.length) ? gsmExtension(bytes[i + 1] & 0x7F) : '\0';
                if (ext != '\0') {
                    sb.append(ext);
                    i++;               // consume the extension byte
                } else {
                    sb.append(' ');    // lone/undefined ESC -> space; next byte handled normally
                }
            } else {
                sb.append(GSM_DEFAULT_ALPHABET[b]);
            }
        }
        return sb.toString();
    }

    /**
     * Surfaces PDU metadata that isn't promoted to a typed {@code Sms} field: the raw
     * {@code data_coding}, source/dest TON/NPI, the raw {@code esm_class} byte, and the
     * UDHI (User Data Header Indicator) flag — signals a concatenated/binary short message,
     * which this connector does not reassemble.
     *
     * @param pdu the PDU to read metadata from
     * @return a {@code map<anydata>} suitable for {@code Sms.properties}
     */
    private static BMap<BString, Object> toProperties(AbstractSmCommand pdu) {
        // Typed map<anydata>, NOT the no-arg createMapValue() (that produces a map<any>,
        // whose runtime type is not a subtype of anydata even though Sms.properties is
        // declared map<anydata>). RecordValueImpl.put does not type-check, so a mismatch
        // here would land unnoticed until sms.toJson()/clone()/cloneReadOnly() - i.e. every
        // observability integration and every readonly parameter - inspects the runtime
        // type of that member and fails.
        BMap<BString, Object> properties = ValueCreator.createMapValue(
                TypeCreator.createMapType(PredefinedTypes.TYPE_ANYDATA));
        properties.put(StringUtils.fromString("dataCoding"), (long) (pdu.getDataCoding() & 0xFF));
        properties.put(StringUtils.fromString("sourceAddressTypeOfNumber"), (long) (pdu.getSourceAddrTon() & 0xFF));
        properties.put(StringUtils.fromString("sourceAddressNumberingPlanIndicator"),
                (long) (pdu.getSourceAddrNpi() & 0xFF));
        properties.put(StringUtils.fromString("destinationAddressTypeOfNumber"),
                (long) (pdu.getDestAddrTon() & 0xFF));
        properties.put(StringUtils.fromString("destinationAddressNumberingPlanIndicator"),
                (long) (pdu.getDestAddrNpi() & 0xFF));
        properties.put(StringUtils.fromString("esmClass"), (long) (pdu.getEsmClass() & 0xFF));
        properties.put(StringUtils.fromString("udhi"), pdu.isUdhi());
        return properties;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
