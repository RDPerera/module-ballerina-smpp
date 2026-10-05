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
}
