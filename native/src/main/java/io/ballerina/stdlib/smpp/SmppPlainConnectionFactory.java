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
import org.jsmpp.session.connection.socket.SocketConnection;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A plaintext {@link ConnectionFactory} whose only reason to exist is to bound the TCP
 * connect. jsmpp's stock {@code SocketConnectionFactory} does {@code new Socket(host, port)},
 * a blocking connect with the OS-default timeout — which can be minutes against a
 * black-holed host (SYN dropped). Because a bind/connect loop that retries is typically
 * single-threaded (a {@code Listener}'s rebind worker; a {@code Client}'s own reconnect, if
 * any), one such stalled attempt would block every subsequent attempt for that whole OS
 * timeout. This factory instead uses {@code socket.connect(address, connectTimeoutMillis)}
 * so a stalled connect fails fast — mirroring what {@link SmppSslConnectionFactory} already
 * does on the TLS path. The bind-response wait itself is bounded separately (its own
 * timeout argument); this covers only the connect phase that the bind timeout does not.
 *
 * <p>Immutable and therefore safe to reuse, though both the {@code Client} and the
 * {@code Listener} build a fresh instance per bind attempt (like the TLS factory).
 */
public final class SmppPlainConnectionFactory implements ConnectionFactory, RawConnectionFactory {

    private final int connectTimeoutMillis;

    public SmppPlainConnectionFactory(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    @Override
    public Connection createConnection(String host, int port) throws IOException {
        return createRawConnection(host, port).connection();
    }

    @Override
    public RawConnection createRawConnection(String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            // Plaintext: the raw socket IS the connection's socket.
            return new RawConnection(new SocketConnection(socket), socket);
        } catch (IOException e) {
            // Close the half-open socket so a failed attempt leaks no file descriptor, then
            // rethrow so the caller's connect-and-bind surfaces the failure to 'start()/
            // connect() or the rebind loop.
            try {
                socket.close();
            } catch (IOException ignored) {
                // best-effort close; the connect failure is the meaningful error
            }
            throw e;
        }
    }
}
