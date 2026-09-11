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

import org.jsmpp.session.connection.Connection;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wraps the {@link Connection} this connector hands to jsmpp so that either the
 * {@code Listener} (for its rebind logic) or the {@code Client} (for its own LINK_DOWN
 * classification) gets its own, first-hand signal the instant the transport dies —
 * independent of jsmpp's {@code SessionStateListener}.
 *
 * <h2>Why this exists</h2>
 *
 * A drop-detection/rebind design that hangs off exactly one signal — jsmpp firing
 * {@code CLOSED} at the session's {@code SessionStateListener} — is fragile. That firing
 * is the last step of {@code AbstractSession.close()}, and jsmpp 3.0.2 has a reproduced,
 * jstack-photographed failure mode where it never happens:
 *
 * <ol>
 *   <li>The peer severs the socket; the {@code PDUReaderWorker} reads EOF and logs
 *       {@code "Reading PDU session ... in state BOUND_TRX: null"}.</li>
 *   <li>The reader thread then <em>dies</em> before completing {@code close()} — before
 *       interrupting the {@code EnquireLinkSender} and before {@code ctx.close()}, the
 *       only line that fires the CLOSED listener. (Observed across 16 consecutive thread
 *       dumps: the reader absent, its EnquireLinkSender orphaned in
 *       {@code AbstractSession.java:531}'s 500ms wait loop for 64+ seconds, session state
 *       still {@code BOUND_TRX} 75s after the EOF. The exact kill site inside that gap is
 *       not pinned; nothing is printed.)</li>
 *   <li>Session state stays BOUND forever, the listener never fires, the connector is
 *       never told, and no rebind is ever scheduled — a permanently deaf listener that
 *       still believes it is bound.</li>
 * </ol>
 *
 * Hit rate in a repeated-sever soak was roughly one cycle in a few hundred — rare enough
 * to look like an unexplained flake, permanent when it hits.
 *
 * <h2>What it does</h2>
 *
 * The {@link #getInputStream()} wrapper reports end-of-stream and read {@code IOException}s
 * (excluding {@link SocketTimeoutException}, which is jsmpp's routine keepalive cadence —
 * the socket SO_TIMEOUT doubles as the enquire_link trigger) to a one-shot callback, at
 * the exact moment jsmpp's reader observes them. The callback is armed after the
 * per-attempt drop guards exist (the connect-and-bind path in {@code NativeListener}/a
 * future {@code NativeClient}); it shares those guards, so whichever of the two signals
 * arrives first — jsmpp's CLOSED listener or this one — reports the drop exactly once,
 * and the other is a no-op.
 *
 * <p>{@link #close()} is a FOURTH signal. It was originally not reported, on the
 * reasoning that "if jsmpp's choreography runs to completion, the CLOSED listener fires
 * normally" — but that reasoning fails exactly where it matters:
 * {@code AbstractSession.close()} invoked <em>by the EnquireLinkSender on itself</em>
 * (the ordinary enquire-link-timeout dead-link path, AbstractSession.java:543-552)
 * structurally skips {@code ctx.close()} via the
 * {@code Thread.currentThread() != enquireLinkSender} guard at :264 — CLOSED never
 * fires from the closing thread. Normally the reader then observes the closed socket
 * from {@code read()} and both remaining signals engage; but under inbound overflow the
 * reader can be parked on {@code monitorenter(os)} (it sends NACKs through
 * {@code SynchronizedPDUSender}, SMPPSession.java:705/:713) behind a stalled writer —
 * it never reaches {@code read()}, and with the stream signal blind and CLOSED skipped,
 * ZERO signals fire while submits keep being accepted onto a dead socket. Reporting
 * {@code close()} — which the self-closing sender has just called first-hand — closes
 * that hole, independent of the reader thread.
 *
 * <p>The conditionality lives entirely in the SHARED guards, never here:
 * {@code fireOnce()} always invokes the handler, and the schedule-time/fire-time guards
 * (owned by the caller — see {@code NativeListener}) suppress at schedule time (a
 * user-initiated stop = user teardown) and re-check at fire time. Classification of
 * every {@code Connection.close()} reacher (a connector-initiated close, a connect-phase
 * failure close, the EnquireLinkSender's self-close, the reader-loop/pduExecutor close,
 * and an abandoned session's finalizer) lives in that shared guard table, not here — do
 * not reorder or duplicate it.
 *
 * <p>{@link #forceClose()} is the bounded-close watchdog primitive: it closes the
 * PRE-TLS raw socket and nothing else. See {@link RawConnectionFactory} for why the
 * raw socket is the only safe target, and note it deliberately does NOT fire the
 * signal — the unblocked reader's own IOException does, and the guards classify it.
 *
 * <p>This is transport observation on a stream this connector already owns (both
 * connection factories are ours) — no jsmpp behaviour is altered, no reflection is used.
 */
public final class ObservedConnection implements Connection {

    private final Connection delegate;
    private final java.net.Socket rawSocket;
    private final AtomicReference<Runnable> onTransportDeath;
    private final AtomicBoolean fired = new AtomicBoolean(false);
    // jsmpp fetches the stream once per session, but idempotence is cheap: always hand
    // back the same wrapper so double-wrapping can never double-fire.
    private volatile InputStream wrappedIn;

    /**
     * @param delegate the real connection from the plain/TLS factory
     * @param rawSocket the pre-TLS raw socket beneath {@code delegate} — the
     *     {@link #forceClose()} target; may be null in unit tests (forceClose no-ops)
     * @param onTransportDeath holder for the death callback; may still be empty when the
     *     connection is created (the callback is armed later in the caller's bind/connect
     *     step, before the connect-and-bind call — which is what creates this connection —
     *     returns). A death observed while the holder is empty is silently dropped:
     *     pre-arm deaths happen only during the connect/bind phase, whose failures the
     *     caller itself surfaces to the caller of 'start()/connect().
     */
    public ObservedConnection(Connection delegate, java.net.Socket rawSocket,
            AtomicReference<Runnable> onTransportDeath) {
        this.delegate = delegate;
        this.rawSocket = rawSocket;
        this.onTransportDeath = onTransportDeath;
    }

    /**
     * Force-closes the pre-TLS raw socket — the bounded-close watchdog primitive.
     * Deliberately NOT {@code delegate.close()}: on TLS that is {@code SSLSocket.close()},
     * which attempts a close_notify write and can block behind the very stall this
     * exists to break. A raw {@code Socket.close()} takes no jsmpp or JSSE lock and
     * asynchronously unblocks threads parked in read/write on this socket (JDK
     * asynchronous close — pinned by {@code forceCloseUnblocksAParkedWrite}). Idempotent;
     * never throws. Does not fire the death signal itself: the unblocked reader's own
     * IOException does, and the schedule/fire-time guards classify it.
     */
    public void forceClose() {
        if (rawSocket == null) {
            return;
        }
        try {
            rawSocket.close();
        } catch (IOException ignored) {
            // best-effort: already closed or already broken - both count as closed here
        }
    }

    private void fireOnce() {
        if (fired.compareAndSet(false, true)) {
            Runnable handler = onTransportDeath.get();
            if (handler != null) {
                try {
                    handler.run();
                } catch (Throwable t) {
                    // Never let the death signal kill jsmpp's reader thread - a throw
                    // propagating out of read() IS the wedge this class exists to survive.
                    // The signal is one-shot and now consumed; log rather than rethrow.
                    java.util.logging.Logger.getLogger(ObservedConnection.class.getName())
                            .warning("transport-death handler threw: " + t);
                }
            }
        }
    }

    @Override
    public synchronized InputStream getInputStream() {
        if (wrappedIn == null) {
            wrappedIn = new FilterInputStream(delegate.getInputStream()) {
                @Override
                public int read() throws IOException {
                    try {
                        int b = super.read();
                        if (b < 0) {
                            fireOnce();
                        }
                        return b;
                    } catch (SocketTimeoutException e) {
                        throw e; // routine keepalive cadence, not a death
                    } catch (IOException e) {
                        fireOnce();
                        throw e;
                    }
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    try {
                        int n = super.read(b, off, len);
                        if (n < 0) {
                            fireOnce();
                        }
                        return n;
                    } catch (SocketTimeoutException e) {
                        throw e;
                    } catch (IOException e) {
                        fireOnce();
                        throw e;
                    }
                }
            };
        }
        return wrappedIn;
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public InetAddress getInetAddress() {
        return delegate.getInetAddress();
    }

    @Override
    public InetAddress getLocalAddress() {
        return delegate.getLocalAddress();
    }

    @Override
    public int getPort() {
        return delegate.getPort();
    }

    @Override
    public int getLocalPort() {
        return delegate.getLocalPort();
    }

    @Override
    public void setSoTimeout(int timeout) throws IOException {
        delegate.setSoTimeout(timeout);
    }

    @Override
    public void close() throws IOException {
        // The FOURTH drop signal - see the class doc. fireOnce() runs BEFORE the delegate
        // close and unconditionally: the conditionality (stop vs bind-phase vs genuine
        // drop) lives entirely in the caller's split-phase guards plus the dropReported
        // CAS, which already classify every caller correctly (a close()-time guard here
        // would re-open the install-sliver wedge).
        fireOnce();
        delegate.close();
    }

    @Override
    public java.io.OutputStream getOutputStream() {
        return delegate.getOutputStream();
    }
}
