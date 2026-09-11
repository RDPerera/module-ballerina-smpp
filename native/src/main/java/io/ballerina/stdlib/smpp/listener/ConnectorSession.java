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

import org.jsmpp.session.SMPPSession;
import org.jsmpp.session.connection.ConnectionFactory;

/**
 * jsmpp keeps ONE {@code transactionTimer} per session, and it bounds two very different
 * things: how long a <em>submit</em> waits for its {@code submit_sm_resp} (where patience
 * is safety — a false timeout means "possibly delivered, retrying may duplicate"), and how
 * long the session's own housekeeping waits — {@code unbind()} at stop, the
 * {@code EnquireLinkSender}'s response wait on a dead link, the reader thread's
 * exit drain — where patience is pure latency. One knob cannot serve both, and jsmpp
 * offers no second knob.
 *
 * <p>This subclass splits them without touching jsmpp, exploiting a bytecode-verified
 * asymmetry in 3.0.2:
 *
 * <ul>
 *   <li>{@code unbind()} reads the <b>field</b> directly — so the field (set once in the
 *       constructor, never via the public setter afterwards) holds the SHORT housekeeping
 *       bound, and stop paths stay snappy;</li>
 *   <li>{@code sendEnquireLink()}, {@code pduExecutor.awaitTermination}, and all five
 *       submit-family operations call the <b>getter</b> — overridden to return the
 *       configured {@code transactionTimeout} only inside a connector-owned
 *       {@code ThreadLocal} submit context (entered by {@code NativeCaller} around
 *       {@code submitShortMessage}); every read outside that context — including all of
 *       jsmpp's own housekeeping threads — gets the SHORT bound.</li>
 * </ul>
 *
 * <p>Net effect with defaults: submits wait up to 30s for their response; a dead-link
 * enquire probe burns 2s instead of 30s; silent-peer detection stays ≈
 * {@code enquireLinkInterval} + 2s. NOTE the timer split alone does NOT bound a stop:
 * jsmpp's {@code unbindAndClose()} still has three untimed segments (monitor
 * acquisition behind a stalled writer, the socket write itself, and {@code close()}'s
 * {@code enquireLinkSender.join()}) — the wall-clock stop bound comes from
 * {@code NativeListener.CLOSE_WATCHDOG_MS}'s raw-socket force-close, giving worst-case
 * {@code gracefulStop} ≈ {@code gracefulStopTimeout} + ≤2s sweep + ≤4s bounded close, and
 * {@code immediateStop} ≈ ≤4s.
 *
 * <p><b>Version coupling, stated plainly:</b> the getter-vs-field split is a jsmpp 3.0.2
 * bytecode fact (unbind() reads the field; the submit family, sendEnquireLink and the
 * reader's awaitTermination call the getter). A jsmpp upgrade must re-run the
 * {@code javap} call-site audit this class depends on. There is deliberately no
 * dependency on jsmpp's thread names.
 */
final class ConnectorSession extends SMPPSession {

    /**
     * The housekeeping bound: jsmpp's own historical {@code transactionTimer} default,
     * which bounded exactly these paths for years before this connector exposed the
     * configurable timeout. Internal on purpose — additive to expose later if a
     * deployment ever needs it.
     */
    static final long HOUSEKEEPING_TIMER_MS = 2000;

    /**
     * Marks the current thread as executing a connector-issued submit-family operation.
     * Routing by OUR OWN ThreadLocal instead of by jsmpp's thread names removes the
     * version coupling on jsmpp's thread-naming entirely and inverts the failure
     * direction: if the context is ever missed, submits get the SHORT bound and time out
     * loudly at 2s instead of dead-link detection silently degrading. jsmpp's housekeeping
     * threads never enter this context, so they always see the short field value.
     */
    private static final ThreadLocal<Boolean> SUBMIT_CONTEXT = new ThreadLocal<>();

    static void enterSubmitContext() {
        SUBMIT_CONTEXT.set(Boolean.TRUE);
    }

    static void exitSubmitContext() {
        SUBMIT_CONTEXT.remove();
    }

    private final long submitTransactionTimerMs;

    ConnectorSession(ConnectionFactory connectionFactory, long submitTransactionTimerMs) {
        super(connectionFactory);
        this.submitTransactionTimerMs = submitTransactionTimerMs;
        // The FIELD carries the short bound: unbind() reads it directly, and
        // super.getTransactionTimer() returns it everywhere outside a submit context.
        // Nothing may call setTransactionTimer() with the submit value after this.
        super.setTransactionTimer(HOUSEKEEPING_TIMER_MS);
    }

    @Override
    public long getTransactionTimer() {
        return Boolean.TRUE.equals(SUBMIT_CONTEXT.get())
                ? submitTransactionTimerMs
                : super.getTransactionTimer();
    }
}
