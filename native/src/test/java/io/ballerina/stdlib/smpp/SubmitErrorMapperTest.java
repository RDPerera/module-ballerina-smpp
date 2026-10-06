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

import io.ballerina.stdlib.smpp.SubmitErrorMapper.InvalidRequest;
import io.ballerina.stdlib.smpp.SubmitErrorMapper.MappedFailure;
import org.jsmpp.GenericNackResponseException;
import org.jsmpp.InvalidResponseException;
import org.jsmpp.PDUException;
import org.jsmpp.extra.NegativeResponseException;
import org.jsmpp.extra.ResponseTimeoutException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the pure failure classification in {@link SubmitErrorMapper}: every branch of the
 * jsmpp exception hierarchy lands on the documented {@code FailureMode}, with
 * {@code possiblySubmitted} answering "can a retry duplicate the message?".
 */
class SubmitErrorMapperTest {

    @Test
    void localRefusalIsInvalidRequestAndNeverReachedTheWire() {
        MappedFailure f = SubmitErrorMapper.mapSubmitFailure(new InvalidRequest("destinationAddress is required"));
        assertEquals("INVALID_REQUEST", f.failureMode);
        assertEquals("destinationAddress is required", f.message);
        assertNull(f.commandStatus);
        assertFalse(f.possiblySubmitted);
    }

    @Test
    void negativeResponseIsRejectedWithTheSmscStatus() {
        MappedFailure f = SubmitErrorMapper.mapSubmitFailure(new NegativeResponseException(0x58));
        assertEquals("REJECTED", f.failureMode);
        assertEquals(0x58, f.commandStatus);
        assertFalse(f.possiblySubmitted, "the SMSC definitively refused it - a retry cannot duplicate");
        assertTrue(f.message.startsWith("SMSC rejected the request: "), f.message);
    }

    @Test
    void genericNackIsRejectedAndMatchedBeforeItsInvalidResponseParent() {
        MappedFailure f = SubmitErrorMapper.mapSubmitFailure(new GenericNackResponseException("nack", 0x45));
        assertEquals("REJECTED", f.failureMode);
        assertEquals(0x45, f.commandStatus);
        assertFalse(f.possiblySubmitted);
        assertTrue(f.message.startsWith("SMSC answered generic_nack: "), f.message);
    }

    @Test
    void responseTimeoutIsDeliveryUnknown() {
        MappedFailure f = SubmitErrorMapper.mapSubmitFailure(new ResponseTimeoutException("no response"));
        assertEquals("TIMEOUT_DELIVERY_UNKNOWN", f.failureMode);
        assertNull(f.commandStatus);
        assertTrue(f.possiblySubmitted, "the SMSC may have accepted it - a retry may duplicate");
        assertTrue(f.message.contains("transactionTimeout"), f.message);
    }

    @Test
    void responseTimeoutAfterAConnectorCloseIsLinkDownNamingTheClose() {
        MappedFailure f = SubmitErrorMapper.mapSubmitFailure(new ResponseTimeoutException("no response"), true);
        assertEquals("LINK_DOWN", f.failureMode);
        assertTrue(f.possiblySubmitted);
        assertTrue(f.message.contains("this connector closed the session"), f.message);
    }

    @Test
    void invalidResponseIsProtocolError() {
        MappedFailure f = SubmitErrorMapper.mapSubmitFailure(new InvalidResponseException("garbled"));
        assertEquals("PROTOCOL_ERROR", f.failureMode);
        assertTrue(f.possiblySubmitted, "the request was sent; only the response was unusable");
        assertTrue(f.message.startsWith("invalid response PDU: "), f.message);
    }

    @Test
    void pduCompositionFailureIsInvalidRequest() {
        MappedFailure f = SubmitErrorMapper.mapSubmitFailure(new PDUException("too long"));
        assertEquals("INVALID_REQUEST", f.failureMode);
        assertFalse(f.possiblySubmitted, "thrown while composing, before anything was written");
        assertTrue(f.message.startsWith("jsmpp rejected the request PDU: "), f.message);
    }

    @Test
    void jsmppSessionStateLeakIsRewordedAsLinkDown() {
        MappedFailure f = SubmitErrorMapper.mapSubmitFailure(
                new IOException("Cannot submit_sm while session 1a2b3c in state CLOSED"));
        assertEquals("LINK_DOWN", f.failureMode);
        assertTrue(f.possiblySubmitted);
        assertFalse(f.message.contains("CLOSED"), "jsmpp's internal state names must not leak: " + f.message);
        assertTrue(f.message.contains("went down"), f.message);
    }

    @Test
    void midFlightIoFailureIsLinkDown() {
        MappedFailure f = SubmitErrorMapper.mapSubmitFailure(new IOException("Broken pipe"));
        assertEquals("LINK_DOWN", f.failureMode);
        assertTrue(f.possiblySubmitted, "octets may have been flushed before the failure");
        assertTrue(f.message.startsWith("connection failed mid-request: Broken pipe"), f.message);
    }

    @Test
    void anythingElseIsProtocolErrorNamingTheExceptionClass() {
        MappedFailure f = SubmitErrorMapper.mapSubmitFailure(new IllegalStateException("boom"));
        assertEquals("PROTOCOL_ERROR", f.failureMode);
        assertTrue(f.possiblySubmitted);
        assertTrue(f.message.contains("IllegalStateException: boom"), f.message);
    }

    @Test
    void aMessagelessExceptionFallsBackToItsClassName() {
        MappedFailure f = SubmitErrorMapper.mapSubmitFailure(new IllegalStateException());
        assertTrue(f.message.contains("IllegalStateException"), f.message);
    }

    // --- mapBindFailure: connect/bind failures from Client.init / Listener.start ---

    @Test
    void bindRefusedBySmscIsRejectedWithTheBindStatusEvenWhenJsmppWrapsIt() {
        // jsmpp's connectAndBind rethrows a negative bind_resp as IOException(cause = NegativeResponseException)
        IOException wrapped = new IOException("Receive negative bind response: Negative response 0000000e",
                new NegativeResponseException(0x0E));
        MappedFailure f = SubmitErrorMapper.mapBindFailure(wrapped, "failed to connect/bind to SMSC");
        assertEquals("REJECTED", f.failureMode);
        assertEquals(0x0E, f.commandStatus);
        assertFalse(f.possiblySubmitted);
        assertTrue(f.message.startsWith("failed to connect/bind to SMSC: Receive negative bind response"), f.message);
    }

    @Test
    void bindResponseTimeoutIsLinkDown() {
        IOException wrapped = new IOException("Time out waiting for bind response",
                new ResponseTimeoutException("No response after waiting for 60000 millis"));
        MappedFailure f = SubmitErrorMapper.mapBindFailure(wrapped, "failed to connect/bind to SMSC");
        assertEquals("LINK_DOWN", f.failureMode);
        assertNull(f.commandStatus);
        assertFalse(f.possiblySubmitted);
    }

    @Test
    void connectionRefusedUnknownHostAndTlsFailuresAreLinkDown() {
        for (IOException e : new IOException[] {
                new java.net.ConnectException("Connection refused"),
                new java.net.UnknownHostException("no-such-host.invalid"),
                new java.net.SocketTimeoutException("Connect timed out"),
                new javax.net.ssl.SSLHandshakeException("PKIX path building failed")}) {
            MappedFailure f = SubmitErrorMapper.mapBindFailure(e, "failed to connect/bind to SMSC");
            assertEquals("LINK_DOWN", f.failureMode, e.getClass().getSimpleName());
            assertNull(f.commandStatus);
            assertFalse(f.possiblySubmitted);
        }
    }

    @Test
    void invalidBindResponseIsProtocolError() {
        IOException wrapped = new IOException("Receive invalid response of bind",
                new InvalidResponseException("unexpected command id"));
        MappedFailure f = SubmitErrorMapper.mapBindFailure(wrapped, "failed to connect/bind to SMSC");
        assertEquals("PROTOCOL_ERROR", f.failureMode);
    }

    @Test
    void oversizedCredentialsAreInvalidRequest() {
        MappedFailure f = SubmitErrorMapper.mapBindFailure(
                new IllegalArgumentException("password exceeds the maximum length of 8 characters"),
                "invalid credentials");
        assertEquals("INVALID_REQUEST", f.failureMode);
        assertEquals("invalid credentials: password exceeds the maximum length of 8 characters", f.message);
        assertFalse(f.possiblySubmitted);
    }

    @Test
    void genericNackToBindIsRejectedWithItsStatus() {
        MappedFailure f = SubmitErrorMapper.mapBindFailure(
                new GenericNackResponseException("generic_nack", 0x03), "failed to connect/bind to SMSC");
        assertEquals("REJECTED", f.failureMode);
        assertEquals(0x03, f.commandStatus);
        assertFalse(f.possiblySubmitted);
    }

    @Test
    void malformedBindPduIsProtocolError() {
        MappedFailure f = SubmitErrorMapper.mapBindFailure(new PDUException("bad PDU"),
                "failed to connect/bind to SMSC");
        assertEquals("PROTOCOL_ERROR", f.failureMode);
        assertNull(f.commandStatus);
    }

    @Test
    void unexpectedBindFailureIsProtocolErrorNamingTheClass() {
        MappedFailure f = SubmitErrorMapper.mapBindFailure(new IllegalStateException(),
                "failed to connect/bind to SMSC");
        assertEquals("PROTOCOL_ERROR", f.failureMode);
        assertTrue(f.message.contains("IllegalStateException"), f.message);
    }
}
