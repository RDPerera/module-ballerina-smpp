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

import org.jsmpp.session.connection.Connection;
import org.jsmpp.session.connection.ConnectionFactory;

import java.io.IOException;
import java.net.Socket;

/**
 * Extends jsmpp's {@link ConnectionFactory} with a variant that returns the pre-TLS raw
 * {@link Socket} alongside the {@link Connection}. The raw socket is the ONLY safe
 * force-close target for a bounded-close watchdog:
 *
 * <ul>
 *   <li>On the TLS path, {@code SocketConnection.close()} closes the {@code SSLSocket},
 *       whose {@code close()} attempts a close_notify WRITE on the very transport the
 *       watchdog assumes is dead — it can block on the SSLSocket's internal write lock
 *       behind the stalled writer it exists to break ({@code SmppSslConnectionFactory}
 *       discards the raw socket at the TLS wrap, and jsmpp's {@code SocketConnection}
 *       offers no accessor).</li>
 *   <li>A raw {@code Socket.close()} takes no jsmpp or JSSE lock and asynchronously
 *       unblocks threads parked in read/write on that socket (JDK {@code NioSocketImpl}
 *       preClose). That behaviour is a <b>JDK coupling</b> the whole design rests on;
 *       {@code ObservedConnectionTest.forceCloseUnblocksAParkedWrite} pins it.</li>
 * </ul>
 *
 * Shared by the {@code client} and {@code listener} native packages: a {@code Client}'s
 * connect and a {@code Listener}'s bind both need identical plain/TLS socket setup and
 * identical strict TLS verification behaviour. Public (unlike the single-package
 * reference this was ported from) because {@code io.ballerina.stdlib.smpp.client} and
 * {@code io.ballerina.stdlib.smpp.listener} are separate subpackages, each needing
 * access from outside {@code io.ballerina.stdlib.smpp}.
 */
public interface RawConnectionFactory extends ConnectionFactory {

    /**
     * A jsmpp {@link Connection} paired with the pre-TLS raw socket beneath it.
     *
     * @param connection the jsmpp connection (plain or TLS-wrapped)
     * @param rawSocket the underlying pre-TLS socket, for the force-close watchdog
     */
    record RawConnection(Connection connection, Socket rawSocket) { }

    RawConnection createRawConnection(String host, int port) throws IOException;
}
