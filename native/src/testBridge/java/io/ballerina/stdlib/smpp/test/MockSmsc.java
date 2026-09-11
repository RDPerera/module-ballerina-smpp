// Copyright (c) 2026. Test-only mock SMSC: accept-loop, bind validation, PDU senders, and
// scriptable/configurable responses for the CLIENT-initiated submit-family operations
// (submit_sm, submit_multi, data_sm, query_sm, cancel_sm, replace_sm).
package io.ballerina.stdlib.smpp.test;

import org.jsmpp.SMPPConstant;
import org.jsmpp.extra.NegativeResponseException;
import org.jsmpp.extra.ProcessRequestException;
import org.jsmpp.bean.Address;
import org.jsmpp.bean.BroadcastSm;
import org.jsmpp.bean.CancelBroadcastSm;
import org.jsmpp.bean.CancelSm;
import org.jsmpp.bean.DataSm;
import org.jsmpp.bean.DeliverSm;
import org.jsmpp.bean.ESMClass;
import org.jsmpp.bean.MessageState;
import org.jsmpp.bean.NumberingPlanIndicator;
import org.jsmpp.bean.OptionalParameter;
import org.jsmpp.bean.QueryBroadcastSm;
import org.jsmpp.bean.QuerySm;
import org.jsmpp.bean.RawDataCoding;
import org.jsmpp.bean.RegisteredDelivery;
import org.jsmpp.bean.ReplaceSm;
import org.jsmpp.bean.SubmitMulti;
import org.jsmpp.bean.SubmitSm;
import org.jsmpp.bean.TypeOfNumber;
import org.jsmpp.bean.UnsuccessDelivery;
import org.jsmpp.session.BindRequest;
import org.jsmpp.session.BroadcastSmResult;
import org.jsmpp.session.DataSmResult;
import org.jsmpp.session.QueryBroadcastSmResult;
import org.jsmpp.session.QuerySmResult;
import org.jsmpp.session.ServerMessageReceiverListener;
import org.jsmpp.session.Session;
import org.jsmpp.session.SMPPServerSession;
import org.jsmpp.session.SMPPServerSessionListener;
import org.jsmpp.session.SubmitMultiResult;
import org.jsmpp.session.SubmitSmResult;
import org.jsmpp.session.connection.ServerConnectionFactory;
import org.jsmpp.util.MessageId;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One instance per test-mock: owns the listening socket, an accept-loop (per jsmpp's own
 * {@code StressServer.run()} blueprint - each accepted connection's blocking
 * {@code waitForBind} is offloaded to a pool so {@code accept()} is never blocked by a
 * slow or absent bind), an optional bind-credential validator (accept-everything by
 * default), and a registry of accepted connections keyed by handle so tests can address a
 * specific session.
 *
 * <p>Two distinct roles are exercised through the same accepted connection:
 * <ul>
 *   <li><b>SMSC-initiated push</b> (this mock playing the SMSC, driving a {@code smpp:Listener}):
 *       {@link #sendDeliverSm}, {@link #sendDataSm}, {@link #sendDeliveryReceipt} etc. call
 *       out on the accepted {@link SMPPServerSession} and block for the connector's response.
 *   <li><b>Client-initiated request</b> (this mock playing the SMSC, answering a
 *       {@code smpp:Client}'s {@code submit_sm}/{@code submit_multi}/{@code data_sm}/
 *       {@code query_sm}/{@code cancel_sm}/{@code replace_sm}): {@link CapturingReceiverListener}
 *       captures the inbound PDU and answers it per the scripted/configurable response set via
 *       the {@code set*}/{@code awaitNext*} methods below - one capture queue and one set of
 *       fault-injection knobs per operation, mirroring the existing {@code submit_sm} pattern.
 * </ul>
 *
 * <p>Plain Java only - no Ballerina types here. {@link MockSmscBridge} is the only file
 * in this source set that touches the Ballerina runtime API.
 */
final class MockSmsc {

    private final SMPPServerSessionListener listener;
    private final ExecutorService acceptLoop = Executors.newSingleThreadExecutor();
    // Churn-sized: a client whose bind timed out leaves a pre-bind session holding a pool
    // thread until waitForBind gives up - 8 threads plus the shorter waitForBind below keep
    // the pool recycling under that load.
    private final ExecutorService waitBindPool = Executors.newFixedThreadPool(8);
    private final ConcurrentHashMap<Long, SMPPServerSession> connections = new ConcurrentHashMap<>();
    // Reverse of `connections`: the capturing listener is handed a session, not a handle,
    // so this is the only way an inbound PDU can be attributed to a connection. Identity-keyed
    // on purpose - SMPPServerSession does not override equals/hashCode.
    private final Map<SMPPServerSession, Long> connectionIds = Collections.synchronizedMap(new IdentityHashMap<>());
    // Per-connection FIFO captures, so concurrent client calls on different connections
    // cannot interleave into one queue and tests can await a specific link.
    private final ConcurrentHashMap<Long, BlockingQueue<SubmitSm>> submitCaptures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, BlockingQueue<SubmitMulti>> submitMultiCaptures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, BlockingQueue<DataSm>> dataSmCaptures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, BlockingQueue<QuerySm>> querySmCaptures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, BlockingQueue<CancelSm>> cancelSmCaptures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, BlockingQueue<ReplaceSm>> replaceSmCaptures = new ConcurrentHashMap<>();
    private final AtomicLong nextConnectionId = new AtomicLong(1);
    // Monotonic so a test can assert which submit produced which id, and so the value is
    // stable across runs (jsmpp's own generators are random).
    private final AtomicLong nextMessageId = new AtomicLong(1000);
    // Identity-keyed record of which message_id this mock minted for which submit_sm, so a
    // test can assert that the id the connector RETURNED is the id this mock actually sent -
    // rather than inferring it from the monotonic counter, which stops being predictable the
    // moment a test submits more than once or two tests share a mock.
    private final Map<SubmitSm, String> submitMessageIds = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<DataSm, String> dataSmMessageIds = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<SubmitMulti, String> submitMultiMessageIds = Collections.synchronizedMap(new IdentityHashMap<>());

    // --- submit_sm fault injection, off by default. 0 = respond normally. ---
    private volatile int submitFailureStatus = 0;
    private volatile long submitDelayMillis = 0;
    // When true, submit_sm_resp carries an empty message_id - a spec-legal response that
    // leaves the client with nothing to correlate a later receipt against.
    private volatile boolean submitEmptyMessageId = false;

    // --- submit_multi scripting ---
    private volatile int submitMultiFailureStatus = 0;
    private volatile String submitMultiMessageIdOverride = null; // null = auto-generate
    private volatile String[] submitMultiUnsuccessfulAddresses = new String[0];

    // --- client-initiated data_sm scripting (ESME -> SMSC; distinct from sendDataSm, the
    // SMSC -> ESME push used to drive a Listener's onDataSm) ---
    private volatile int dataSmFailureStatus = 0;
    private volatile String dataSmMessageIdOverride = null;

    // --- query_sm scripting ---
    private volatile int querySmFailureStatus = 0;
    private volatile String queryMessageState = "DELIVERED"; // an org.jsmpp.bean.MessageState name
    private volatile String queryFinalDate = ""; // empty = absent
    private volatile int queryErrorCode = 0;

    // --- cancel_sm / replace_sm scripting: success unless a failure status is set ---
    private volatile int cancelSmFailureStatus = 0;
    private volatile int replaceSmFailureStatus = 0;

    // Each entry is either a Long (connection handle, bind accepted) or a Throwable
    // (bind rejected / listener error), so awaitNextBind can surface both outcomes.
    private final BlockingQueue<Object> bindOutcomes = new LinkedBlockingQueue<>();
    private volatile String expectedSystemId; // null = accept any (default)
    private volatile String expectedPassword;
    private volatile boolean running = true;
    private volatile boolean closeAfterAccept = false;

    MockSmsc(int port) throws IOException {
        this(new SMPPServerSessionListener(port));   // plain socket
    }

    /** TLS variant: the listener terminates TLS via the supplied server-side factory. */
    MockSmsc(int port, ServerConnectionFactory factory) throws IOException {
        this(new SMPPServerSessionListener(port, factory));
    }

    private MockSmsc(SMPPServerSessionListener listener) {
        this.listener = listener;
        // Generous - the mock must never be its own bottleneck.
        listener.setPduProcessorDegree(50);
        listener.setQueueCapacity(1000);
        // Set ONCE, here: SMPPServerSessionListener.accept() copies this reference into
        // every session it returns, so there is no accept-then-set window in which an
        // early client-initiated PDU could arrive at a session with no listener.
        listener.setMessageReceiverListener(new CapturingReceiverListener());
    }

    /** Starts the accept-loop in the background; returns immediately. */
    void start() {
        acceptLoop.execute(this::runAcceptLoop);
    }

    private void runAcceptLoop() {
        while (running) {
            try {
                SMPPServerSession session = listener.accept();
                waitBindPool.execute(() -> waitForBindAndValidate(session));
            } catch (IOException e) {
                if (running) {
                    bindOutcomes.offer(e);
                }
                break; // listener closed
            }
        }
    }

    private void waitForBindAndValidate(SMPPServerSession session) {
        try {
            // 3s: generous for a healthy bind (arrives within ms of accept), short enough
            // that a pre-bind-dead session (client bind timeout under churn) releases its
            // pool thread quickly.
            BindRequest request = session.waitForBind(3_000);
            String sysId = expectedSystemId;
            String pass = expectedPassword;
            if (sysId != null && !sysId.equals(request.getSystemId())) {
                request.reject(SMPPConstant.STAT_ESME_RINVSYSID);
                bindOutcomes.offer(new IllegalStateException("bind rejected: invalid systemId"));
                return;
            }
            if (pass != null && !pass.equals(request.getPassword())) {
                request.reject(SMPPConstant.STAT_ESME_RINVPASWD);
                bindOutcomes.offer(new IllegalStateException("bind rejected: invalid password"));
                return;
            }
            // Mint the handle and register the session BEFORE accepting the bind. The
            // client may submit the instant bind_resp lands, and the capturing listener
            // attributes an inbound PDU by looking the session up in `connectionIds` - so
            // if registration happened after accept(), a submit arriving on the heels of
            // bind_resp would be unattributable.
            long id = nextConnectionId.getAndIncrement();
            connections.put(id, session);
            connectionIds.put(session, id);
            submitCaptures.put(id, new LinkedBlockingQueue<>());
            submitMultiCaptures.put(id, new LinkedBlockingQueue<>());
            dataSmCaptures.put(id, new LinkedBlockingQueue<>());
            querySmCaptures.put(id, new LinkedBlockingQueue<>());
            cancelSmCaptures.put(id, new LinkedBlockingQueue<>());
            replaceSmCaptures.put(id, new LinkedBlockingQueue<>());
            try {
                request.accept("mock-smsc");
            } catch (Exception e) {
                // The registration above is speculative (it must precede accept() so an
                // instant client PDU is attributable); if accept() itself throws (e.g. the
                // peer vanished between bind and bind_resp) the session is already
                // BOUND jsmpp-side, and leaving it registered would make close()'s
                // unbindAndClose() hang on a dead session. Undo and rethrow.
                forget(id, session);
                throw e;
            }
            if (closeAfterAccept) {
                // Accepted-then-instantly-dropped, for the bound-race soak's cycle driver.
                // Close BEFORE offering the outcome so the drop has already happened by the
                // time the test observes the bind. Undo the registration above - the
                // session is already dead.
                forget(id, session);
                session.close();
                bindOutcomes.offer(id);
                return;
            }
            bindOutcomes.offer(id);
        } catch (Exception e) {
            bindOutcomes.offer(e);
        }
    }

    /**
     * Blocks until the next bind attempt resolves (accepted or rejected).
     *
     * @return the new connection's handle if the bind was accepted
     * @throws Exception the rejection/failure if it wasn't, or a timeout
     */
    long awaitNextBind(long timeoutMillis) throws Exception {
        Object outcome = bindOutcomes.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        if (outcome == null) {
            throw new TimeoutException("no bind observed within " + timeoutMillis + "ms");
        }
        if (outcome instanceof Long id) {
            return id;
        }
        throw (Exception) outcome;
    }

    void expectCredentials(String systemId, String password) {
        this.expectedSystemId = systemId;
        this.expectedPassword = password;
    }

    private SMPPServerSession connection(long connectionId) {
        SMPPServerSession session = connections.get(connectionId);
        if (session == null) {
            throw new IllegalArgumentException("no such connection handle: " + connectionId);
        }
        return session;
    }

    // ---------------------------------------------------------------------------------
    // SMSC-initiated push (drives a smpp:Listener)
    // ---------------------------------------------------------------------------------

    /**
     * Sends a {@code deliver_sm} on the given connection, blocking until the
     * {@code deliver_sm_resp} arrives (or throwing on a negative/timed-out response).
     * {@code messagePayload} being non-null adds a {@code message_payload} TLV alongside
     * (or instead of) the {@code short_message} field, for precedence testing.
     */
    void sendDeliverSm(long connectionId, String shortMessage, String messagePayload, int dataCoding)
            throws Exception {
        OptionalParameter[] params = messagePayload == null
                ? new OptionalParameter[0]
                : new OptionalParameter[] {
                        new OptionalParameter.Message_payload(encode(messagePayload, dataCoding)) };
        try {
            connection(connectionId).deliverShortMessage(
                    "", TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, "12345",
                    TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, "99999",
                    new ESMClass(), (byte) 0, (byte) 0,
                    new RegisteredDelivery(0),
                    new RawDataCoding((byte) dataCoding),
                    encode(shortMessage, dataCoding),
                    params);
        } catch (NegativeResponseException e) {
            throw classify(e);
        }
    }

    /** Maps a negative resp's decoded command_status to its distinctly-named test type. */
    private static Exception classify(NegativeResponseException e) {
        return switch (e.getCommandStatus()) {
            case SMPPConstant.STAT_ESME_RTHROTTLED -> new ThrottledException(e.getCommandStatus());
            case SMPPConstant.STAT_ESME_RX_P_APPN -> new PermanentAppErrorException(e.getCommandStatus());
            case SMPPConstant.STAT_ESME_RX_T_APPN -> new TemporaryAppErrorException(e.getCommandStatus());
            default -> e;
        };
    }

    /**
     * Sends a {@code deliver_sm} carrying exactly the given raw {@code short_message} bytes
     * (no charset encoding on the mock side), so a test can put a precise on-wire byte
     * sequence — e.g. unpacked GSM 03.38 — in front of the connector's decoder.
     */
    void sendDeliverSmRaw(long connectionId, byte[] shortMessage, int dataCoding) throws Exception {
        connection(connectionId).deliverShortMessage(
                "", TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, "12345",
                TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, "99999",
                new ESMClass(), (byte) 0, (byte) 0,
                new RegisteredDelivery(0),
                new RawDataCoding((byte) dataCoding),
                shortMessage,
                new OptionalParameter[0]);
    }

    /**
     * Sends a {@code deliver_sm} flagged as an SMSC delivery receipt (the SMSC-delivery-receipt
     * esm_class message-type bit) carrying {@code receiptText} as its short_message body — for
     * exercising the connector's receipt-parsing path end to end.
     */
    void sendDeliveryReceipt(long connectionId, String receiptText) throws Exception {
        sendDeliveryReceipt(connectionId, receiptText, null);
    }

    /**
     * As above, but additionally carrying the {@code receipted_message_id} TLV (0x001E)
     * when {@code receiptedMessageId} is non-null - the spec's only GUARANTEED correlation
     * key between a delivery receipt and the {@code SubmitResult.messageId} a submit
     * returned.
     */
    void sendDeliveryReceipt(long connectionId, String receiptText, String receiptedMessageId)
            throws Exception {
        OptionalParameter[] params = receiptedMessageId == null
                ? new OptionalParameter[0]
                : new OptionalParameter[] {
                        new OptionalParameter.Receipted_message_id(receiptedMessageId) };
        connection(connectionId).deliverShortMessage(
                "", TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, "12345",
                TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, "99999",
                new ESMClass(DeliverSm.composeSmscDeliveryReceipt((byte) 0)), (byte) 0, (byte) 0,
                new RegisteredDelivery(0), new RawDataCoding((byte) 0),
                receiptText.getBytes(StandardCharsets.US_ASCII), params);
    }

    /**
     * Sends a {@code data_sm} on the given connection (SMSC -> ESME, drives a
     * {@code smpp:Listener}'s {@code onDataSm}). {@code messagePayload} being null sends no
     * {@code message_payload} TLV at all (DATA_SM has no short_message field, so that
     * exercises the connector's empty-fallback path).
     */
    void sendDataSm(long connectionId, String messagePayload, int dataCoding) throws Exception {
        OptionalParameter[] params = messagePayload == null
                ? new OptionalParameter[0]
                : new OptionalParameter[] {
                        new OptionalParameter.Message_payload(encode(messagePayload, dataCoding)) };
        try {
            connection(connectionId).dataShortMessage(
                    "", TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, "12345",
                    TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, "99999",
                    new ESMClass(), new RegisteredDelivery(0),
                    new RawDataCoding((byte) dataCoding),
                    params);
        } catch (NegativeResponseException e) {
            throw classify(e);
        }
    }

    // ---------------------------------------------------------------------------------
    // Client-initiated capture (this mock plays the SMSC answering a smpp:Client)
    // ---------------------------------------------------------------------------------

    /**
     * Receives client-originated PDUs. Only the submit-family operations this connector's
     * {@code Client} issues are implemented; broadcast-family operations answer the way
     * jsmpp's own {@code SMPPServerSimulator} does for unsupported operations, rather than
     * returning {@code null} (a latent NPE inside jsmpp's response writer, not a benign
     * no-op).
     *
     * <p>One instance is shared by every session this mock accepts, so it holds no
     * per-connection state: attribution is by session identity via {@code connectionIds}.
     *
     * <p><b>Invariant - nothing in this listener may block the enquire_link path.</b>
     * On the server side jsmpp runs {@code onAcceptEnquireLink} BEFORE sending
     * {@code enquire_link_resp}, and this class deliberately does not override that default
     * no-op. An override that blocks (a delay knob, a capture) would delay the keepalive
     * answer and can make the connector's session time out.
     */
    private final class CapturingReceiverListener implements ServerMessageReceiverListener {

        @Override
        public SubmitSmResult onAcceptSubmitSm(SubmitSm submitSm, SMPPServerSession source)
                throws ProcessRequestException {
            capture(source, submitCaptures, submitSm);

            long delay = submitDelayMillis;
            if (delay > 0) {
                // Deliberately blocks the jsmpp PDU-processor thread, which is exactly
                // what a slow SMSC does - that is the condition a configured
                // transactionTimeout has to survive.
                sleepOrThrow(delay);
            }

            int failure = submitFailureStatus;
            if (failure != 0) {
                throw new ProcessRequestException("injected submit failure", failure);
            }

            String messageId = submitEmptyMessageId
                    ? ""
                    : Long.toString(nextMessageId.getAndIncrement());
            submitMessageIds.put(submitSm, messageId);
            try {
                // The String ctors of SubmitSmResult are package-private; MessageId is the
                // only public route. It declares PDUStringException but accepts both ""
                // and ordinary decimal ids, so neither branch above can trip it.
                return new SubmitSmResult(new MessageId(messageId), new OptionalParameter[0]);
            } catch (Exception e) {
                throw new ProcessRequestException("could not build submit_sm_resp: " + e.getMessage(),
                        SMPPConstant.STAT_ESME_RSYSERR, e);
            }
        }

        @Override
        public SubmitMultiResult onAcceptSubmitMulti(SubmitMulti submitMulti, SMPPServerSession source)
                throws ProcessRequestException {
            capture(source, submitMultiCaptures, submitMulti);

            int failure = submitMultiFailureStatus;
            if (failure != 0) {
                throw new ProcessRequestException("injected submit_multi failure", failure);
            }
            String messageId = submitMultiMessageIdOverride != null
                    ? submitMultiMessageIdOverride
                    : Long.toString(nextMessageId.getAndIncrement());
            submitMultiMessageIds.put(submitMulti, messageId);
            String[] unsuccessful = submitMultiUnsuccessfulAddresses;
            UnsuccessDelivery[] unsuccess = new UnsuccessDelivery[unsuccessful.length];
            for (int i = 0; i < unsuccessful.length; i++) {
                unsuccess[i] = new UnsuccessDelivery(
                        new Address(TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, unsuccessful[i]),
                        SMPPConstant.STAT_ESME_RINVDSTADR);
            }
            return new SubmitMultiResult(messageId, unsuccess, new OptionalParameter[0]);
        }

        @Override
        public QuerySmResult onAcceptQuerySm(QuerySm querySm, SMPPServerSession source)
                throws ProcessRequestException {
            capture(source, querySmCaptures, querySm);

            int failure = querySmFailureStatus;
            if (failure != 0) {
                throw new ProcessRequestException("injected query_sm failure", failure);
            }
            MessageState state;
            try {
                state = MessageState.valueOf(queryMessageState);
            } catch (IllegalArgumentException e) {
                state = MessageState.UNKNOWN;
            }
            String finalDate = queryFinalDate == null ? "" : queryFinalDate;
            return new QuerySmResult(finalDate, state, (byte) queryErrorCode);
        }

        @Override
        public void onAcceptReplaceSm(ReplaceSm replaceSm, SMPPServerSession source)
                throws ProcessRequestException {
            capture(source, replaceSmCaptures, replaceSm);
            int failure = replaceSmFailureStatus;
            if (failure != 0) {
                throw new ProcessRequestException("injected replace_sm failure", failure);
            }
        }

        @Override
        public void onAcceptCancelSm(CancelSm cancelSm, SMPPServerSession source)
                throws ProcessRequestException {
            capture(source, cancelSmCaptures, cancelSm);
            int failure = cancelSmFailureStatus;
            if (failure != 0) {
                throw new ProcessRequestException("injected cancel_sm failure", failure);
            }
        }

        @Override
        public BroadcastSmResult onAcceptBroadcastSm(BroadcastSm broadcastSm, SMPPServerSession source)
                throws ProcessRequestException {
            throw new ProcessRequestException("broadcast_sm not supported by this mock",
                    SMPPConstant.STAT_ESME_RINVCMDID);
        }

        @Override
        public void onAcceptCancelBroadcastSm(CancelBroadcastSm cancelBroadcastSm, SMPPServerSession source)
                throws ProcessRequestException {
            throw new ProcessRequestException("cancel_broadcast_sm not supported by this mock",
                    SMPPConstant.STAT_ESME_RINVCMDID);
        }

        @Override
        public QueryBroadcastSmResult onAcceptQueryBroadcastSm(QueryBroadcastSm queryBroadcastSm,
                                                               SMPPServerSession source)
                throws ProcessRequestException {
            throw new ProcessRequestException("query_broadcast_sm not supported by this mock",
                    SMPPConstant.STAT_ESME_RINVCMDID);
        }

        @Override
        public DataSmResult onAcceptDataSm(DataSm dataSm, Session source) throws ProcessRequestException {
            // source is typed Session here (the interface method's signature), but the only
            // implementation jsmpp hands us server-side is the SMPPServerSession this mock
            // itself accepted - safe to attribute via connectionIds, keyed by that same
            // instance, exactly like every other onAccept* callback above.
            if (source instanceof SMPPServerSession serverSession) {
                capture(serverSession, dataSmCaptures, dataSm);
                Long id = connectionIds.get(serverSession);
                if (id != null) {
                    int failure = dataSmFailureStatus;
                    if (failure != 0) {
                        throw new ProcessRequestException("injected data_sm failure", failure);
                    }
                    String messageId = dataSmMessageIdOverride != null
                            ? dataSmMessageIdOverride
                            : Long.toString(nextMessageId.getAndIncrement());
                    dataSmMessageIds.put(dataSm, messageId);
                    try {
                        return new DataSmResult(new MessageId(messageId),
                                new OptionalParameter[0]);
                    } catch (Exception e) {
                        throw new ProcessRequestException("could not build data_sm_resp: " + e.getMessage(),
                                SMPPConstant.STAT_ESME_RSYSERR, e);
                    }
                }
            }
            throw new ProcessRequestException("data_sm from an unattributable session",
                    SMPPConstant.STAT_ESME_RSYSERR);
        }
    }

    /** Records a captured PDU into this connection's queue, if the connection is still known. */
    private <T> void capture(SMPPServerSession source, ConcurrentHashMap<Long, BlockingQueue<T>> captures,
            T pdu) {
        Long id = connectionIds.get(source);
        if (id != null) {
            // Registered before accept(), so this cannot miss a PDU that races bind_resp. A
            // null id means the session was already severed/forgotten - capture nothing
            // rather than resurrect a dead connection's queue.
            BlockingQueue<T> queue = captures.get(id);
            if (queue != null) {
                queue.offer(pdu);
            }
        }
    }

    private static void sleepOrThrow(long millis) throws ProcessRequestException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessRequestException("interrupted while delaying response",
                    SMPPConstant.STAT_ESME_RSYSERR);
        }
    }

    /**
     * Blocks until the next {@code submit_sm} arrives on this connection, and returns it.
     * FIFO per connection, so a test on one link is never handed another link's PDU.
     */
    SubmitSm awaitNextSubmit(long connectionId, long timeoutMillis) throws Exception {
        return poll(submitCaptures, connectionId, timeoutMillis, "submit_sm");
    }

    SubmitMulti awaitNextSubmitMulti(long connectionId, long timeoutMillis) throws Exception {
        return poll(submitMultiCaptures, connectionId, timeoutMillis, "submit_multi");
    }

    DataSm awaitNextClientDataSm(long connectionId, long timeoutMillis) throws Exception {
        return poll(dataSmCaptures, connectionId, timeoutMillis, "data_sm");
    }

    QuerySm awaitNextQuerySm(long connectionId, long timeoutMillis) throws Exception {
        return poll(querySmCaptures, connectionId, timeoutMillis, "query_sm");
    }

    CancelSm awaitNextCancelSm(long connectionId, long timeoutMillis) throws Exception {
        return poll(cancelSmCaptures, connectionId, timeoutMillis, "cancel_sm");
    }

    ReplaceSm awaitNextReplaceSm(long connectionId, long timeoutMillis) throws Exception {
        return poll(replaceSmCaptures, connectionId, timeoutMillis, "replace_sm");
    }

    private static <T> T poll(ConcurrentHashMap<Long, BlockingQueue<T>> captures, long connectionId,
            long timeoutMillis, String pduName) throws Exception {
        BlockingQueue<T> queue = captures.get(connectionId);
        if (queue == null) {
            throw new IllegalArgumentException("no such connection handle: " + connectionId);
        }
        T pdu = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        if (pdu == null) {
            throw new TimeoutException("no " + pduName + " observed on connection " + connectionId
                    + " within " + timeoutMillis + "ms");
        }
        return pdu;
    }

    /**
     * The {@code message_id} this mock returned in the {@code submit_sm_resp} for that exact
     * PDU, or {@code null} if it never sent one (injected failure, or the response has not
     * been built yet).
     */
    String messageIdFor(SubmitSm submitSm) {
        return submitMessageIds.get(submitSm);
    }

    String messageIdFor(DataSm dataSm) {
        return dataSmMessageIds.get(dataSm);
    }

    String messageIdFor(SubmitMulti submitMulti) {
        return submitMultiMessageIds.get(submitMulti);
    }

    /**
     * How many captured submits are still unread on this connection. Throws on an
     * unknown/severed handle rather than returning 0: "and no more submits arrived" must
     * never pass vacuously against a dead handle.
     */
    int pendingSubmitCount(long connectionId) {
        BlockingQueue<SubmitSm> queue = submitCaptures.get(connectionId);
        return queue == null ? -1 : queue.size();
    }

    // --- fault-injection / scripting setters -----------------------------------------

    void setSubmitFailure(int commandStatus) {
        this.submitFailureStatus = commandStatus;
    }

    void setSubmitDelay(long millis) {
        this.submitDelayMillis = millis;
    }

    void setSubmitEmptyMessageId(boolean enabled) {
        this.submitEmptyMessageId = enabled;
    }

    void setSubmitMultiFailure(int commandStatus) {
        this.submitMultiFailureStatus = commandStatus;
    }

    /** {@code messageId} null restores auto-generation; {@code unsuccessfulAddresses} may be empty. */
    void setSubmitMultiResponse(String messageId, String[] unsuccessfulAddresses) {
        this.submitMultiMessageIdOverride = messageId;
        this.submitMultiUnsuccessfulAddresses = unsuccessfulAddresses == null
                ? new String[0] : unsuccessfulAddresses;
    }

    void setClientDataSmFailure(int commandStatus) {
        this.dataSmFailureStatus = commandStatus;
    }

    void setClientDataSmMessageId(String messageId) {
        this.dataSmMessageIdOverride = messageId;
    }

    void setQuerySmFailure(int commandStatus) {
        this.querySmFailureStatus = commandStatus;
    }

    /** {@code messageState} is an {@code org.jsmpp.bean.MessageState} enum name, e.g. "DELIVERED". */
    void setQuerySmResponse(String messageState, String finalDate, int errorCode) {
        this.queryMessageState = messageState;
        this.queryFinalDate = finalDate;
        this.queryErrorCode = errorCode;
    }

    void setCancelSmFailure(int commandStatus) {
        this.cancelSmFailureStatus = commandStatus;
    }

    void setReplaceSmFailure(int commandStatus) {
        this.replaceSmFailureStatus = commandStatus;
    }

    /** Drops every registration for a connection. Idempotent. */
    private void forget(long connectionId, SMPPServerSession session) {
        connections.remove(connectionId);
        submitCaptures.remove(connectionId);
        submitMultiCaptures.remove(connectionId);
        dataSmCaptures.remove(connectionId);
        querySmCaptures.remove(connectionId);
        cancelSmCaptures.remove(connectionId);
        replaceSmCaptures.remove(connectionId);
        if (session != null) {
            connectionIds.remove(session);
        }
    }

    /**
     * Encodes text with the charset matching the given {@code data_coding} value —
     * deliberately mirroring the connector's own decoder switch, so what this mock puts on
     * the wire is what that decoder expects to find for each value.
     */
    private static byte[] encode(String text, int dataCoding) {
        Charset charset = switch (dataCoding & 0xFF) {
            case 0x01 -> StandardCharsets.US_ASCII;
            case 0x03 -> StandardCharsets.ISO_8859_1;
            case 0x08 -> StandardCharsets.UTF_16BE;
            default -> StandardCharsets.UTF_8;
        };
        return text.getBytes(charset);
    }

    /**
     * Abrupt severance: closes the connection's socket directly, with NO unbind exchange
     * (jsmpp {@code AbstractSession.close()} sends nothing). From the connector's side this
     * is indistinguishable from a network failure / crashed SMSC. Removed from the
     * registry: the handle is dead afterwards.
     */
    void sever(long connectionId) {
        SMPPServerSession session = connection(connectionId);
        forget(connectionId, session);
        session.close();
    }

    /**
     * Clean, peer-initiated unbind: sends an unbind PDU and blocks awaiting unbind_resp,
     * then closes ({@code unbindAndClose() == unbind() + close()}). This method returning
     * normally proves the connector answered {@code unbind_resp}.
     */
    void peerUnbind(long connectionId) throws Exception {
        SMPPServerSession session = connection(connectionId);
        forget(connectionId, session);
        session.unbindAndClose();
    }

    /**
     * Stops accepting new connections (closes the server socket) while leaving already
     * accepted connections alive - so an exhaustion test can make every rebind attempt
     * fail deterministically (connection refused), with no race against severing the
     * live connection.
     */
    void stopAccepting() {
        running = false;
        try {
            listener.close();
        } catch (Exception ignored) {
            // best-effort; the accept-loop exits on the resulting IOException
        }
    }

    /** When enabled, every subsequent bind is accepted and then immediately closed. */
    void setCloseAfterAccept(boolean enabled) {
        this.closeAfterAccept = enabled;
    }

    /**
     * Raises this connection's transaction timer (jsmpp default: 2000 ms) so a blocking
     * mock-side send can outwait a deliberately slow SYNC handler without a
     * ResponseTimeoutException.
     */
    void setTransactionTimer(long connectionId, long millis) {
        connection(connectionId).setTransactionTimer(millis);
    }

    /**
     * Lowers this connection's enquire_link timer (jsmpp default: 60000 ms) so the mock,
     * acting as the SMSC, probes the connector's liveness frequently. Combined with a short
     * transaction timer, an unanswered enquire_link makes the mock close the session.
     */
    void setEnquireLinkTimer(long connectionId, int millis) {
        connection(connectionId).setEnquireLinkTimer(millis);
    }

    void close() {
        running = false;
        connections.values().forEach(SMPPServerSession::unbindAndClose);
        connections.clear();
        connectionIds.clear();
        submitCaptures.clear();
        submitMultiCaptures.clear();
        dataSmCaptures.clear();
        querySmCaptures.clear();
        cancelSmCaptures.clear();
        replaceSmCaptures.clear();
        submitMessageIds.clear();
        dataSmMessageIds.clear();
        submitMultiMessageIds.clear();
        try {
            listener.close();
        } catch (Exception ignored) {
            // best-effort cleanup
        }
        acceptLoop.shutdownNow();
        waitBindPool.shutdownNow();
    }
}
