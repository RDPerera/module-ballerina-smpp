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

import io.ballerina.stdlib.smpp.SmsMapper.ReceiptFields;
import org.jsmpp.bean.DeliverSm;
import org.jsmpp.bean.OptionalParameter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the pure decode and delivery-receipt logic in {@link SmsMapper}: the data_coding
 * switch, the unpacked GSM 03.38 alphabet including its extension table, the
 * message_payload-over-short_message precedence, and jsmpp's Appendix-B receipt parse.
 */
class SmsMapperTest {

    private static final String RECEIPT = "id:ABC123 sub:001 dlvrd:001 submit date:2401151430 "
            + "done date:2401151431 stat:DELIVRD err:000 text:hello there";

    @Test
    void emptyOrNullPayloadDecodesToEmptyString() {
        assertEquals("", SmsMapper.decodeShortMessage(null, (byte) 0x00));
        assertEquals("", SmsMapper.decodeShortMessage(new byte[0], (byte) 0x08));
    }

    @Test
    void defaultAlphabetFallsBackToUtf8UnlessGsm7DecodingIsEnabled() {
        byte[] utf8 = "héllo".getBytes(StandardCharsets.UTF_8);
        assertEquals("héllo", SmsMapper.decodeShortMessage(utf8, (byte) 0x00));
        // The same bytes read as unpacked GSM-7 septets are something else entirely.
        byte[] gsm = {0x48, 0x65, 0x6C, 0x6C, 0x6F};
        assertEquals("Hello", SmsMapper.decodeShortMessage(gsm, (byte) 0x00, true));
    }

    @Test
    void singleByteEncodingsDecodePrecisely() {
        assertEquals("Hi", SmsMapper.decodeShortMessage(new byte[]{0x48, 0x69}, (byte) 0x01));
        assertEquals("é", SmsMapper.decodeShortMessage(new byte[]{(byte) 0xE9}, (byte) 0x03));
        assertEquals("Hi", SmsMapper.decodeShortMessage("Hi".getBytes(StandardCharsets.UTF_16BE), (byte) 0x08));
    }

    @Test
    void unknownDataCodingFallsBackToUtf8() {
        byte[] utf8 = "ok".getBytes(StandardCharsets.UTF_8);
        assertEquals("ok", SmsMapper.decodeShortMessage(utf8, (byte) 0x0F));
    }

    @Test
    void gsm7DefaultAlphabetMapsSeptetsAndMasksTheHighBit() {
        assertEquals("@A", SmsMapper.decodeGsm0338Unpacked(new byte[]{0x00, 0x41}));
        assertEquals("A", SmsMapper.decodeGsm0338Unpacked(new byte[]{(byte) 0xC1}), "a stray high bit is padding");
        assertEquals("", SmsMapper.decodeGsm0338Unpacked(new byte[0]));
    }

    @Test
    void gsm7ExtensionTableIsDecoded() {
        byte[] all = {
            0x1B, 0x0A, 0x1B, 0x14, 0x1B, 0x28, 0x1B, 0x29, 0x1B, 0x2F,
            0x1B, 0x3C, 0x1B, 0x3D, 0x1B, 0x3E, 0x1B, 0x40, 0x1B, 0x65
        };
        assertEquals("\f^{}\\[~]|€", SmsMapper.decodeGsm0338Unpacked(all));
    }

    @Test
    void gsm7UndefinedOrTrailingEscapeBecomesASpace() {
        assertEquals(" A", SmsMapper.decodeGsm0338Unpacked(new byte[]{0x1B, 0x41}),
                "an ESC not forming an extension is a space; the next byte decodes normally");
        assertEquals("A ", SmsMapper.decodeGsm0338Unpacked(new byte[]{0x41, 0x1B}), "a trailing ESC is a space");
    }

    @Test
    void messagePayloadTlvTakesPrecedenceOverShortMessage() {
        DeliverSm pdu = new DeliverSm();
        byte[] fallback = "short".getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals(fallback, SmsMapper.payloadBytes(pdu, fallback));

        byte[] payload = "payload".getBytes(StandardCharsets.US_ASCII);
        pdu.setOptionalParameters(new OptionalParameter.Message_payload(payload));
        assertArrayEquals(payload, SmsMapper.payloadBytes(pdu, fallback));
    }

    @Test
    void wellFormedReceiptIsParsedFieldByField() {
        DeliverSm pdu = new DeliverSm();
        pdu.setShortMessage(RECEIPT.getBytes(StandardCharsets.US_ASCII));
        ReceiptFields f = SmsMapper.parseReceipt(pdu);
        assertEquals("ABC123", f.id);
        assertEquals(1, f.submitted);
        assertEquals(1, f.delivered);
        assertEquals("2401151430", f.submitDate);
        assertEquals("2401151431", f.doneDate);
        assertEquals("DELIVRD", f.finalStatus);
        assertEquals("000", f.errorCode);
        assertEquals("hello there", f.text);
    }

    @Test
    void receiptSentViaMessagePayloadIsParsedFromTheTlv() {
        DeliverSm pdu = new DeliverSm();
        pdu.setShortMessage("ignored".getBytes(StandardCharsets.US_ASCII));
        pdu.setOptionalParameters(
                new OptionalParameter.Message_payload(RECEIPT.getBytes(StandardCharsets.US_ASCII)));
        ReceiptFields f = SmsMapper.parseReceipt(pdu);
        assertEquals("ABC123", f.id);
        assertEquals("DELIVRD", f.finalStatus);
    }

    @Test
    void malformedReceiptYieldsNoParsedFieldsRatherThanThrowing() {
        DeliverSm pdu = new DeliverSm();
        pdu.setShortMessage("this is not a receipt".getBytes(StandardCharsets.US_ASCII));
        assertNull(SmsMapper.parseReceipt(pdu));
    }

    @Test
    void nullShortMessageYieldsNoParsedFieldsRatherThanThrowing() {
        assertNull(SmsMapper.parseReceipt(new DeliverSm()));
    }
}
