// Copyright (c) 2026. Thin static facade exposing MockSmsc instances to bal test.
package io.ballerina.stdlib.smpp.test;

import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BString;

import org.jsmpp.bean.CancelSm;
import org.jsmpp.bean.DataSm;
import org.jsmpp.bean.DestinationAddress;
import org.jsmpp.bean.OptionalParameter;
import org.jsmpp.bean.QuerySm;
import org.jsmpp.bean.ReplaceSm;
import org.jsmpp.bean.SubmitMulti;
import org.jsmpp.bean.SubmitSm;
import org.jsmpp.session.connection.ServerConnectionFactory;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The {@code @java:Method} surface consumed by {@code tests/mocksmsc.bal}. Maps opaque
 * {@code long} handles to {@link MockSmsc} instances (and, within each mock, to accepted
 * connections and captured PDUs), so multiple tests — or multiple connections within one
 * test — never collide through shared static session state.
 *
 * <p>The only Ballerina-runtime-typed file in this source set: Ballerina {@code string}
 * interops as {@link BString}, not {@code java.lang.String} (and Ballerina {@code byte[]}
 * doesn't interop-map onto Java's signed {@code byte[]} at all) — payload text crosses
 * the boundary as {@code BString} and is charset-encoded on the Java side per the PDU's
 * {@code data_coding}.
 */
public final class MockSmscBridge {

    private static final AtomicLong NEXT_MOCK_ID = new AtomicLong(1);
    private static final ConcurrentHashMap<Long, MockSmsc> MOCKS = new ConcurrentHashMap<>();

    private MockSmscBridge() {
    }

    /** Opens a plaintext listening socket and starts the accept-loop; returns the handle. */
    public static long openMock(int port) throws Exception {
        // Bounded retry for close-reopen races: a previous test's listener socket on the
        // SAME port can linger for a beat after closeMock() returns.
        java.net.BindException last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                return register(new MockSmsc(port));
            } catch (java.net.BindException e) {
                last = e;
                Thread.sleep(250);
            }
        }
        throw last;
    }

    /**
     * Opens a TLS-terminating mock presenting the given server keystore's cert (server-auth
     * TLS; the mock verifies nothing about the client). All other mock operations work
     * identically against the returned handle.
     */
    public static long openMockTls(int port, BString serverKeystorePath,
                                   BString serverKeystorePassword) throws Exception {
        ServerConnectionFactory factory = new TlsServerConnectionFactory(
                serverKeystorePath.getValue(), serverKeystorePassword.getValue().toCharArray(),
                null, null);
        return register(new MockSmsc(port, factory));
    }

    /**
     * Opens an mTLS mock: presents the server cert AND requires the client to present a
     * cert trusted by the given client truststore (via SSLServerSocket setNeedClientAuth).
     */
    public static long openMockMutualTls(int port, BString serverKeystorePath,
            BString serverKeystorePassword, BString clientTruststorePath,
            BString clientTruststorePassword) throws Exception {
        ServerConnectionFactory factory = new TlsServerConnectionFactory(
                serverKeystorePath.getValue(), serverKeystorePassword.getValue().toCharArray(),
                clientTruststorePath.getValue(), clientTruststorePassword.getValue().toCharArray());
        return register(new MockSmsc(port, factory));
    }

    private static long register(MockSmsc mock) {
        mock.start();
        long id = NEXT_MOCK_ID.getAndIncrement();
        MOCKS.put(id, mock);
        return id;
    }

    /**
     * Configures the mock to only accept binds carrying exactly these credentials,
     * rejecting others with the distinguishing SMPP status code (invalid-systemId vs
     * invalid-password). Call before the connector's connect/bind.
     */
    public static void expectCredentials(long mockId, BString systemId, BString password) {
        mock(mockId).expectCredentials(systemId.getValue(), password.getValue());
    }

    /**
     * Blocks until the next bind attempt on this mock resolves. Returns the accepted
     * connection's handle, or throws the rejection/failure (surfaced to Ballerina as an
     * {@code error} per the extern's {@code returns long|error} contract).
     */
    public static long awaitNextBind(long mockId, long timeoutMillis) throws Exception {
        return mock(mockId).awaitNextBind(timeoutMillis);
    }

    // --- SMSC-initiated push (drives a smpp:Listener) ---------------------------------

    public static void sendDeliverSm(long mockId, long connectionId, BString shortMessage,
                                     BString messagePayload, int dataCoding) throws Exception {
        String payload = messagePayload.getValue();
        mock(mockId).sendDeliverSm(connectionId, shortMessage.getValue(),
                payload.isEmpty() ? null : payload, dataCoding);
    }

    public static void sendDataSm(long mockId, long connectionId, BString messagePayload,
                                  int dataCoding) throws Exception {
        String payload = messagePayload.getValue();
        mock(mockId).sendDataSm(connectionId, payload.isEmpty() ? null : payload, dataCoding);
    }

    public static void sendDeliverSmRaw(long mockId, long connectionId, BArray shortMessage,
                                        int dataCoding) throws Exception {
        mock(mockId).sendDeliverSmRaw(connectionId, shortMessage.getBytes(), dataCoding);
    }

    public static void sendDeliveryReceipt(long mockId, long connectionId, BString receiptText)
            throws Exception {
        mock(mockId).sendDeliveryReceipt(connectionId, receiptText.getValue());
    }

    public static void sendDeliveryReceiptWithTlv(long mockId, long connectionId,
                                                  BString receiptText, BString receiptedMessageId)
            throws Exception {
        String tlv = receiptedMessageId.getValue();
        mock(mockId).sendDeliveryReceipt(connectionId, receiptText.getValue(),
                tlv.isEmpty() ? null : tlv);
    }

    // --- submit_sm capture (client-initiated, ESME -> SMSC) ----------------------------

    private static final AtomicLong NEXT_SUBMIT_ID = new AtomicLong(1);
    private static final ConcurrentHashMap<Long, SubmitSm> SUBMITS = new ConcurrentHashMap<>();
    // Which mock minted each handle. The message_id lives on the mock, not on the SubmitSm
    // bean (it is a field of the RESPONSE), so submitMessageId has to get back to the right
    // instance.
    private static final ConcurrentHashMap<Long, Long> SUBMIT_MOCKS = new ConcurrentHashMap<>();

    /**
     * Blocks until the next submit_sm arrives on this connection; returns a handle to it.
     * FIFO per connection, so a test on one link never sees another link's PDU.
     */
    public static long awaitNextSubmit(long mockId, long connectionId, long timeoutMillis)
            throws Exception {
        SubmitSm submitSm = mock(mockId).awaitNextSubmit(connectionId, timeoutMillis);
        long handle = NEXT_SUBMIT_ID.getAndIncrement();
        SUBMITS.put(handle, submitSm);
        SUBMIT_MOCKS.put(handle, mockId);
        return handle;
    }

    /**
     * The {@code message_id} the mock returned for this submit, or {@code ""} if it sent none.
     */
    public static BString submitMessageId(long submitId) {
        SubmitSm submitSm = submit(submitId);
        Long mockId = SUBMIT_MOCKS.get(submitId);
        String id = mockId == null ? null : mock(mockId).messageIdFor(submitSm);
        return StringUtils.fromString(id == null ? "" : id);
    }

    public static int submitSequenceNumber(long submitId) {
        return submit(submitId).getSequenceNumber();
    }

    /** Captured submits not yet read on this connection — for asserting "and no more". */
    public static int pendingSubmitCount(long mockId, long connectionId) {
        return mock(mockId).pendingSubmitCount(connectionId);
    }

    public static BString submitShortMessage(long submitId) {
        SubmitSm submitSm = submit(submitId);
        byte[] body = submitSm.getShortMessage();
        return StringUtils.fromString(decode(body, submitSm.getDataCoding()));
    }

    public static BArray submitShortMessageBytes(long submitId) {
        byte[] body = submit(submitId).getShortMessage();
        return ValueCreator.createArrayValue(body == null ? new byte[0] : body);
    }

    public static BString submitSourceAddr(long submitId) {
        return StringUtils.fromString(nullToEmpty(submit(submitId).getSourceAddr()));
    }

    public static BString submitDestAddr(long submitId) {
        return StringUtils.fromString(nullToEmpty(submit(submitId).getDestAddress()));
    }

    public static BString submitServiceType(long submitId) {
        return StringUtils.fromString(nullToEmpty(submit(submitId).getServiceType()));
    }

    public static BString submitValidityPeriod(long submitId) {
        return StringUtils.fromString(nullToEmpty(submit(submitId).getValidityPeriod()));
    }

    public static int submitEsmClass(long submitId) {
        return submit(submitId).getEsmClass() & 0xFF;
    }

    public static int submitDataCoding(long submitId) {
        return submit(submitId).getDataCoding() & 0xFF;
    }

    public static int submitRegisteredDelivery(long submitId) {
        return submit(submitId).getRegisteredDelivery() & 0xFF;
    }

    public static int submitSourceAddrTon(long submitId) {
        return submit(submitId).getSourceAddrTon() & 0xFF;
    }

    public static int submitSourceAddrNpi(long submitId) {
        return submit(submitId).getSourceAddrNpi() & 0xFF;
    }

    public static int submitDestAddrTon(long submitId) {
        return submit(submitId).getDestAddrTon() & 0xFF;
    }

    public static int submitDestAddrNpi(long submitId) {
        return submit(submitId).getDestAddrNpi() & 0xFF;
    }

    public static void setSubmitFailure(long mockId, int commandStatus) {
        mock(mockId).setSubmitFailure(commandStatus);
    }

    public static void setSubmitDelay(long mockId, long millis) {
        mock(mockId).setSubmitDelay(millis);
    }

    public static void setSubmitEmptyMessageId(long mockId, boolean enabled) {
        mock(mockId).setSubmitEmptyMessageId(enabled);
    }

    private static SubmitSm submit(long submitId) {
        SubmitSm submitSm = SUBMITS.get(submitId);
        if (submitSm == null) {
            throw new IllegalArgumentException("no such submit handle: " + submitId);
        }
        return submitSm;
    }

    // --- submit_multi capture + scripting (client-initiated) ---------------------------

    private static final AtomicLong NEXT_SUBMIT_MULTI_ID = new AtomicLong(1);
    private static final ConcurrentHashMap<Long, SubmitMulti> SUBMIT_MULTIS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> SUBMIT_MULTI_MOCKS = new ConcurrentHashMap<>();

    public static long awaitNextSubmitMulti(long mockId, long connectionId, long timeoutMillis)
            throws Exception {
        SubmitMulti pdu = mock(mockId).awaitNextSubmitMulti(connectionId, timeoutMillis);
        long handle = NEXT_SUBMIT_MULTI_ID.getAndIncrement();
        SUBMIT_MULTIS.put(handle, pdu);
        SUBMIT_MULTI_MOCKS.put(handle, mockId);
        return handle;
    }

    public static BArray submitMultiDestAddrs(long handle) {
        DestinationAddress[] addrs = submitMulti(handle).getDestAddresses();
        String[] out = new String[addrs == null ? 0 : addrs.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = addrs[i] instanceof org.jsmpp.bean.Address a ? nullToEmpty(a.getAddress()) : "";
        }
        return StringUtils.fromStringArray(out);
    }

    public static BString submitMultiSourceAddr(long handle) {
        return StringUtils.fromString(nullToEmpty(submitMulti(handle).getSourceAddr()));
    }

    public static BString submitMultiShortMessage(long handle) {
        SubmitMulti pdu = submitMulti(handle);
        return StringUtils.fromString(decode(pdu.getShortMessage(), pdu.getDataCoding()));
    }

    public static BString submitMultiServiceType(long handle) {
        return StringUtils.fromString(nullToEmpty(submitMulti(handle).getServiceType()));
    }

    public static int submitMultiRegisteredDelivery(long handle) {
        return submitMulti(handle).getRegisteredDelivery() & 0xFF;
    }

    public static BString submitMultiMessageId(long handle) {
        Long mockId = SUBMIT_MULTI_MOCKS.get(handle);
        String id = mockId == null ? null : mock(mockId).messageIdFor(submitMulti(handle));
        return StringUtils.fromString(id == null ? "" : id);
    }

    public static void setSubmitMultiFailure(long mockId, int commandStatus) {
        mock(mockId).setSubmitMultiFailure(commandStatus);
    }

    /**
     * Scripts the next {@code submit_multi_resp}(s). {@code messageId} empty means
     * auto-generate; {@code unsuccessfulAddresses} lists the destinations to report back as
     * unsuccessful (empty = every destination accepted).
     */
    public static void setSubmitMultiResponse(long mockId, BString messageId,
            BArray unsuccessfulAddresses) {
        String id = messageId.getValue();
        String[] addrs = unsuccessfulAddresses == null
                ? new String[0] : unsuccessfulAddresses.getStringArray();
        mock(mockId).setSubmitMultiResponse(id.isEmpty() ? null : id, addrs);
    }

    private static SubmitMulti submitMulti(long handle) {
        SubmitMulti pdu = SUBMIT_MULTIS.get(handle);
        if (pdu == null) {
            throw new IllegalArgumentException("no such submit_multi handle: " + handle);
        }
        return pdu;
    }

    // --- client-initiated data_sm capture + scripting (ESME -> SMSC) --------------------
    //
    // Distinct from sendDataSm above (SMSC -> ESME, drives a Listener's onDataSm): this is
    // the mock RECEIVING a data_sm from a smpp:Client and answering it.

    private static final AtomicLong NEXT_CLIENT_DATA_SM_ID = new AtomicLong(1);
    private static final ConcurrentHashMap<Long, DataSm> CLIENT_DATA_SMS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> CLIENT_DATA_SM_MOCKS = new ConcurrentHashMap<>();

    public static long awaitNextClientDataSm(long mockId, long connectionId, long timeoutMillis)
            throws Exception {
        DataSm pdu = mock(mockId).awaitNextClientDataSm(connectionId, timeoutMillis);
        long handle = NEXT_CLIENT_DATA_SM_ID.getAndIncrement();
        CLIENT_DATA_SMS.put(handle, pdu);
        CLIENT_DATA_SM_MOCKS.put(handle, mockId);
        return handle;
    }

    public static BString clientDataSmSourceAddr(long handle) {
        return StringUtils.fromString(nullToEmpty(clientDataSm(handle).getSourceAddr()));
    }

    public static BString clientDataSmDestAddr(long handle) {
        return StringUtils.fromString(nullToEmpty(clientDataSm(handle).getDestAddress()));
    }

    public static BString clientDataSmServiceType(long handle) {
        return StringUtils.fromString(nullToEmpty(clientDataSm(handle).getServiceType()));
    }

    public static int clientDataSmRegisteredDelivery(long handle) {
        return clientDataSm(handle).getRegisteredDelivery() & 0xFF;
    }

    public static int clientDataSmDataCoding(long handle) {
        return clientDataSm(handle).getDataCoding() & 0xFF;
    }

    /** The {@code message_payload} TLV text, decoded per the PDU's own {@code data_coding}. */
    public static BString clientDataSmShortMessage(long handle) {
        DataSm pdu = clientDataSm(handle);
        OptionalParameter.Message_payload payload =
                pdu.getOptionalParameter(OptionalParameter.Message_payload.class);
        byte[] body = payload == null ? new byte[0] : payload.getValue();
        return StringUtils.fromString(decode(body, pdu.getDataCoding()));
    }

    public static BString clientDataSmMessageId(long handle) {
        Long mockId = CLIENT_DATA_SM_MOCKS.get(handle);
        String id = mockId == null ? null : mock(mockId).messageIdFor(clientDataSm(handle));
        return StringUtils.fromString(id == null ? "" : id);
    }

    public static void setClientDataSmFailure(long mockId, int commandStatus) {
        mock(mockId).setClientDataSmFailure(commandStatus);
    }

    /** Empty means auto-generate. */
    public static void setClientDataSmMessageId(long mockId, BString messageId) {
        String id = messageId.getValue();
        mock(mockId).setClientDataSmMessageId(id.isEmpty() ? null : id);
    }

    private static DataSm clientDataSm(long handle) {
        DataSm pdu = CLIENT_DATA_SMS.get(handle);
        if (pdu == null) {
            throw new IllegalArgumentException("no such data_sm handle: " + handle);
        }
        return pdu;
    }

    // --- query_sm capture + scripting (client-initiated) --------------------------------

    private static final AtomicLong NEXT_QUERY_SM_ID = new AtomicLong(1);
    private static final ConcurrentHashMap<Long, QuerySm> QUERY_SMS = new ConcurrentHashMap<>();

    public static long awaitNextQuerySm(long mockId, long connectionId, long timeoutMillis)
            throws Exception {
        QuerySm pdu = mock(mockId).awaitNextQuerySm(connectionId, timeoutMillis);
        long handle = NEXT_QUERY_SM_ID.getAndIncrement();
        QUERY_SMS.put(handle, pdu);
        return handle;
    }

    public static BString querySmMessageId(long handle) {
        return StringUtils.fromString(nullToEmpty(querySm(handle).getMessageId()));
    }

    public static BString querySmSourceAddr(long handle) {
        return StringUtils.fromString(nullToEmpty(querySm(handle).getSourceAddr()));
    }

    public static void setQuerySmFailure(long mockId, int commandStatus) {
        mock(mockId).setQuerySmFailure(commandStatus);
    }

    /** {@code messageState} is an {@code org.jsmpp.bean.MessageState} enum name, e.g. "DELIVERED". */
    public static void setQuerySmResponse(long mockId, BString messageState, BString finalDate,
            int errorCode) {
        mock(mockId).setQuerySmResponse(messageState.getValue(), finalDate.getValue(), errorCode);
    }

    private static QuerySm querySm(long handle) {
        QuerySm pdu = QUERY_SMS.get(handle);
        if (pdu == null) {
            throw new IllegalArgumentException("no such query_sm handle: " + handle);
        }
        return pdu;
    }

    // --- cancel_sm capture + scripting (client-initiated) --------------------------------

    private static final AtomicLong NEXT_CANCEL_SM_ID = new AtomicLong(1);
    private static final ConcurrentHashMap<Long, CancelSm> CANCEL_SMS = new ConcurrentHashMap<>();

    public static long awaitNextCancelSm(long mockId, long connectionId, long timeoutMillis)
            throws Exception {
        CancelSm pdu = mock(mockId).awaitNextCancelSm(connectionId, timeoutMillis);
        long handle = NEXT_CANCEL_SM_ID.getAndIncrement();
        CANCEL_SMS.put(handle, pdu);
        return handle;
    }

    public static BString cancelSmMessageId(long handle) {
        return StringUtils.fromString(nullToEmpty(cancelSm(handle).getMessageId()));
    }

    public static BString cancelSmSourceAddr(long handle) {
        return StringUtils.fromString(nullToEmpty(cancelSm(handle).getSourceAddr()));
    }

    public static BString cancelSmDestAddr(long handle) {
        return StringUtils.fromString(nullToEmpty(cancelSm(handle).getDestinationAddress()));
    }

    public static void setCancelSmFailure(long mockId, int commandStatus) {
        mock(mockId).setCancelSmFailure(commandStatus);
    }

    private static CancelSm cancelSm(long handle) {
        CancelSm pdu = CANCEL_SMS.get(handle);
        if (pdu == null) {
            throw new IllegalArgumentException("no such cancel_sm handle: " + handle);
        }
        return pdu;
    }

    // --- replace_sm capture + scripting (client-initiated) -------------------------------

    private static final AtomicLong NEXT_REPLACE_SM_ID = new AtomicLong(1);
    private static final ConcurrentHashMap<Long, ReplaceSm> REPLACE_SMS = new ConcurrentHashMap<>();

    public static long awaitNextReplaceSm(long mockId, long connectionId, long timeoutMillis)
            throws Exception {
        ReplaceSm pdu = mock(mockId).awaitNextReplaceSm(connectionId, timeoutMillis);
        long handle = NEXT_REPLACE_SM_ID.getAndIncrement();
        REPLACE_SMS.put(handle, pdu);
        return handle;
    }

    public static BString replaceSmMessageId(long handle) {
        return StringUtils.fromString(nullToEmpty(replaceSm(handle).getMessageId()));
    }

    public static BString replaceSmSourceAddr(long handle) {
        return StringUtils.fromString(nullToEmpty(replaceSm(handle).getSourceAddr()));
    }

    /** replace_sm's short_message carries no data_coding on the wire; decoded as UTF-8. */
    public static BString replaceSmShortMessage(long handle) {
        byte[] body = replaceSm(handle).getShortMessage();
        return StringUtils.fromString(body == null ? "" : new String(body, StandardCharsets.UTF_8));
    }

    public static int replaceSmRegisteredDelivery(long handle) {
        return replaceSm(handle).getRegisteredDelivery() & 0xFF;
    }

    public static BString replaceSmValidityPeriod(long handle) {
        return StringUtils.fromString(nullToEmpty(replaceSm(handle).getValidityPeriod()));
    }

    public static void setReplaceSmFailure(long mockId, int commandStatus) {
        mock(mockId).setReplaceSmFailure(commandStatus);
    }

    private static ReplaceSm replaceSm(long handle) {
        ReplaceSm pdu = REPLACE_SMS.get(handle);
        if (pdu == null) {
            throw new IllegalArgumentException("no such replace_sm handle: " + handle);
        }
        return pdu;
    }

    // --- shared helpers ------------------------------------------------------------------

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Decodes octets with the charset matching the given raw {@code data_coding} value. */
    private static String decode(byte[] body, byte dataCoding) {
        if (body == null) {
            return "";
        }
        Charset charset = switch (dataCoding & 0xFF) {
            case 0x01 -> StandardCharsets.US_ASCII;
            case 0x03 -> StandardCharsets.ISO_8859_1;
            case 0x08 -> StandardCharsets.UTF_16BE;
            default -> StandardCharsets.UTF_8;
        };
        return new String(body, charset);
    }

    /** Abruptly severs the connection: socket close, no unbind exchange. */
    public static void sever(long mockId, long connectionId) throws Exception {
        mock(mockId).sever(connectionId);
    }

    /** Clean peer-initiated unbind: unbind PDU + awaited unbind_resp, then close. */
    public static void peerUnbind(long mockId, long connectionId) throws Exception {
        mock(mockId).peerUnbind(connectionId);
    }

    /** Stops accepting new connections; already-accepted connections stay alive. */
    public static void stopAccepting(long mockId) {
        mock(mockId).stopAccepting();
    }

    /** When enabled, every subsequent bind is accepted and then immediately closed. */
    public static void setCloseAfterAccept(long mockId, boolean enabled) {
        mock(mockId).setCloseAfterAccept(enabled);
    }

    public static void setTransactionTimer(long mockId, long connectionId, long millis) {
        mock(mockId).setTransactionTimer(connectionId, millis);
    }

    public static void setEnquireLinkTimer(long mockId, long connectionId, int millis) {
        mock(mockId).setEnquireLinkTimer(connectionId, millis);
    }

    /** Closes the mock's connections, listener, and pools. Safe to call twice. */
    public static void closeMock(long mockId) {
        MockSmsc mock = MOCKS.remove(mockId);
        if (mock != null) {
            mock.close();
        }
    }

    private static MockSmsc mock(long mockId) {
        MockSmsc mock = MOCKS.get(mockId);
        if (mock == null) {
            throw new IllegalArgumentException("no such mock handle: " + mockId);
        }
        return mock;
    }

    // ---- black-hole server: accepts TCP connections and never answers the bind ----

    private static final ConcurrentHashMap<Long, BlackHole> BLACK_HOLES = new ConcurrentHashMap<>();

    /**
     * Opens a plain TCP server that accepts connections and then does nothing - it never
     * reads the bind PDU and never responds. A connector pointed here completes the TCP
     * connect but its bind-response wait must time out, exercising the configurable
     * bindTimeout (vs jsmpp's hardcoded 60s default). Returns a handle for cleanup.
     */
    public static long openBlackHole(int port) throws Exception {
        BlackHole hole = new BlackHole(port);
        hole.start();
        long id = NEXT_MOCK_ID.getAndIncrement();
        BLACK_HOLES.put(id, hole);
        return id;
    }

    /** Closes a black-hole server and drops any sockets it is holding. */
    public static void closeBlackHole(long handle) {
        BlackHole hole = BLACK_HOLES.remove(handle);
        if (hole != null) {
            hole.close();
        }
    }
}
