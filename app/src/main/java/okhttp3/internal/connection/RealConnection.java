/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.connection;

import java.io.IOException;
import java.lang.ref.Reference;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownServiceException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import okhttp3.Address;
import okhttp3.Call;
import okhttp3.CertificatePinner;
import okhttp3.Connection;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.EventListener;
import okhttp3.Handshake;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;
import okhttp3.internal.Internal;
import okhttp3.internal.Util;
import okhttp3.internal.Version;
import okhttp3.internal.http.HttpCodec;
import okhttp3.internal.http.HttpHeaders;
import okhttp3.internal.http1.Http1Codec;
import okhttp3.internal.http2.ErrorCode;
import okhttp3.internal.http2.Http2Codec;
import okhttp3.internal.http2.Http2Connection;
import okhttp3.internal.http2.Http2Stream;
import okhttp3.internal.platform.Platform;
import okhttp3.internal.tls.OkHostnameVerifier;
import okhttp3.internal.ws.RealWebSocket;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static okhttp3.internal.Util.closeQuietly;

public final class RealConnection extends Http2Connection.Listener implements Connection {
    private static final String NPE_THROW_WITH_NULL = "throw with null exception";
    private static final int MAX_TUNNEL_ATTEMPTS = 21;

    private final ConnectionPool connectionPool;
    private final Route route;

    // The fields below are initialized by connect() and never reassigned.

    /**
     * The low-level TCP socket.
     */
    private Socket rawSocket;

    /**
     * The application layer socket. Either an {@link SSLSocket} layered over {@link #rawSocket}, or
     * {@link #rawSocket} itself if this connection does not use SSL.
     */
    private Socket socket;
    private Handshake handshake;
    private Protocol protocol;
    private Http2Connection http2Connection;
    private BufferedSource source;
    private BufferedSink sink;

    // The fields below track connection state and are guarded by connectionPool.

    /**
     * If true, no new streams can be created on this connection. Once true this is always true.
     */
    public boolean noNewStreams;

    public int successCount;

    /**
     * The maximum number of concurrent streams that can be carried by this connection. If {@code
     * allocations.size() < allocationLimit} then new streams can be created on this connection.
     */
    public int allocationLimit = 1;

    /**
     * Current streams carried by this connection.
     * 由此连接携带的当前流。
     */
    public final List<Reference<StreamAllocation>> allocations = new ArrayList<>();

    /**
     * Nanotime timestamp when {@code allocations.size()} reached zero.
     */
    public long idleAtNanos = Long.MAX_VALUE;

    public RealConnection(ConnectionPool connectionPool, Route route) {
        this.connectionPool = connectionPool;
        this.route = route;
    }

    public static RealConnection testConnection(
            ConnectionPool connectionPool, Route route, Socket socket, long idleAtNanos) {
        RealConnection result = new RealConnection(connectionPool, route);
        result.socket = socket;
        result.idleAtNanos = idleAtNanos;
        return result;
    }

    public void connect(int connectTimeout, int readTimeout, int writeTimeout,
                        int pingIntervalMillis, boolean connectionRetryEnabled, Call call,
                        EventListener eventListener) {
        if (protocol != null) throw new IllegalStateException("already connected");
        //线路选择
        RouteException routeException = null;
        List<ConnectionSpec> connectionSpecs = route.address().connectionSpecs();
        ConnectionSpecSelector connectionSpecSelector = new ConnectionSpecSelector(connectionSpecs);

        if (route.address().sslSocketFactory() == null) {
            if (!connectionSpecs.contains(ConnectionSpec.CLEARTEXT)) {
                throw new RouteException(new UnknownServiceException(
                        "CLEARTEXT communication not enabled for client"));
            }
            String host = route.address().url().host();
            if (!Platform.get().isCleartextTrafficPermitted(host)) {
                throw new RouteException(new UnknownServiceException(
                        "CLEARTEXT communication to " + host + " not permitted by network security policy"));
            }
        }
        //开始连接
        while (true) {
            try {
                //如果是HTTP代理，且请求建立SSL/TLS加密通道 (http/1.1的https和http2) ，
                // 则需要建立隧道连接。其它情形不需要建立隧道连接。
                //对于需要创建隧道连接的路由，执行buildTunneledConnection()，对于其它情况，则执行buildConnection()。
                if (route.requiresTunnel()) {
                    //建立隧道连接
                    connectTunnel(connectTimeout, readTimeout, writeTimeout, call, eventListener);
                    if (rawSocket == null) {
                        // We were unable to connect the tunnel but properly closed down our resources.
                        //我们无法连接隧道，但正确关闭了我们的资源。
                        break;
                    }
                } else {
                    //建立普通连接
                    connectSocket(connectTimeout, readTimeout, call, eventListener);
                }
                // 建立协议
                //不管是建立隧道连接，还是建立普通连接，都少不了 建立协议 这一步。
                // 这一步是在建立好了TCP连接之后，而在该TCP能被拿来收发数据之前执行的。
                // 它主要为数据的加密传输做一些初始化，比如TLS握手，HTTP/2的协议协商等
                establishProtocol(connectionSpecSelector, pingIntervalMillis, call, eventListener);
                eventListener.connectEnd(call, route.socketAddress(), route.proxy(), protocol);
                break;
                //完成连接
            } catch (IOException e) {
                closeQuietly(socket);
                closeQuietly(rawSocket);
                socket = null;
                rawSocket = null;
                source = null;
                sink = null;
                handshake = null;
                protocol = null;
                http2Connection = null;

                eventListener.connectFailed(call, route.socketAddress(), route.proxy(), null, e);

                if (routeException == null) {
                    routeException = new RouteException(e);
                } else {
                    routeException.addConnectException(e);
                }

                if (!connectionRetryEnabled || !connectionSpecSelector.connectionFailed(e)) {
                    throw routeException;
                }
            }
        }

        if (route.requiresTunnel() && rawSocket == null) {
            ProtocolException exception = new ProtocolException("Too many tunnel connections attempted: "
                    + MAX_TUNNEL_ATTEMPTS);
            throw new RouteException(exception);
        }

        if (http2Connection != null) {
            synchronized (connectionPool) {
                allocationLimit = http2Connection.maxConcurrentStreams();
            }
        }
    }

    /**
     * Does all the work to build an HTTPS connection over a proxy tunnel. The catch here is that a
     * proxy server can issue an auth challenge and then close the connection.
     * 是否通过代理隧道建立HTTPS连接的所有工作。 这里的问题是代理服务器可以发出一个验证质询，然后关闭连接。
     */
    private void connectTunnel(int connectTimeout, int readTimeout, int writeTimeout, Call call,
                               EventListener eventListener) throws IOException {
        //1-构造一个 建立隧道连接 请求。
        Request tunnelRequest = createTunnelRequest();
        HttpUrl url = tunnelRequest.url();
        for (int i = 0; i < MAX_TUNNEL_ATTEMPTS; i++) {
            //2 与HTTP代理服务器建立TCP连接。
            connectSocket(connectTimeout, readTimeout, call, eventListener);
            //3 创建隧道。这主要是将 建立隧道连接 请求发送给HTTP代理服务器，并处理它的响应
            tunnelRequest = createTunnel(readTimeout, writeTimeout, tunnelRequest, url);

            if (tunnelRequest == null) break; // Tunnel successfully created.

            // The proxy decided to close the connection after an auth challenge. We need to create a new
            // connection, but this time with the auth credentials.
            closeQuietly(rawSocket);
            rawSocket = null;
            sink = null;
            source = null;
            eventListener.connectEnd(call, route.socketAddress(), route.proxy(), null);
            //重复上面的第2和第3步，知道建立好了隧道连接。
            // 至于为什么要重复多次，及关于代理认证的内容，可以参考代理协议相关的内容。
        }
    }

    /**
     * Does all the work necessary to build a full HTTP or HTTPS connection on a raw socket.
     * 完成在原始套接字上构建完整的HTTP或HTTPS连接所需的所有工作。
     */
    private void connectSocket(int connectTimeout, int readTimeout, Call call,
                               EventListener eventListener) throws IOException {
        Proxy proxy = route.proxy();
        Address address = route.address();
        //根据代理类型的不同处理Socket
        rawSocket = proxy.type() == Proxy.Type.DIRECT || proxy.type() == Proxy.Type.HTTP
                ? address.socketFactory().createSocket()
                : new Socket(proxy);

        eventListener.connectStart(call, route.socketAddress(), proxy);
        rawSocket.setSoTimeout(readTimeout);
        try {
            //建立Socket连接
            Platform.get().connectSocket(rawSocket, route.socketAddress(), connectTimeout);
        } catch (ConnectException e) {
            ConnectException ce = new ConnectException("Failed to connect to " + route.socketAddress());
            ce.initCause(e);
            throw ce;
        }

        // The following try/catch block is a pseudo hacky way to get around a crash on Android 7.0
        // More details:
        // https://github.com/square/okhttp/issues/3245
        // https://android-review.googlesource.com/#/c/271775/
        try {
            //获取输入/输出流
            source = Okio.buffer(Okio.source(rawSocket));
            sink = Okio.buffer(Okio.sink(rawSocket));
        } catch (NullPointerException npe) {
            if (NPE_THROW_WITH_NULL.equals(npe.getMessage())) {
                throw new IOException(npe);
            }
        }
    }

    private void establishProtocol(ConnectionSpecSelector connectionSpecSelector,
                                   int pingIntervalMillis, Call call, EventListener eventListener) throws IOException {
        if (route.address().sslSocketFactory() == null) {
            //对于明文传输，则设置protocol和socket。
            //socket指向直接与应用层，如HTTP或HTTP/2，交互的Socket：
            //对于明文传输没有设置HTTP代理的HTTP请求，它是与HTTP服务器之间的TCP socket；
            //对于明文传输设置了HTTP代理或SOCKS代理的HTTP请求，它是与代理服务器之间的TCP socket；
            if (route.address().protocols().contains(Protocol.H2C)) {
                socket = rawSocket;
                protocol = Protocol.H2C;
                startHttp2(pingIntervalMillis);
                return;
            }

            socket = rawSocket;
            protocol = Protocol.HTTP_1_1;
            return;
        }
        //对于加密传输没有设置HTTP代理服务器的HTTP或HTTP2请求，它是与HTTP服务器之间的SSLScoket；
        //对于加密传输设置了HTTP代理服务器的HTTP或HTTP2请求，它是与HTTP服务器之间经过了代理服务器的SSLSocket，一个隧道连接；
        //对于加密传输设置了SOCKS代理的HTTP或HTTP2请求，它是一条经过了代理服务器的SSLSocket连接。

        eventListener.secureConnectStart(call);
        //为安全请求所做的建立TLS连接的过程;
        connectTls(connectionSpecSelector);
        eventListener.secureConnectEnd(call, handshake);

        //对于HTTP/2，会建立HTTP/2连接，并进一步协商连接参数，如连接上可同时执行的并发请求数等。
        // 而对于非HTTP/2，则将连接上可同时执行的并发请求数设置为1。
        if (protocol == Protocol.HTTP_2) {
            startHttp2(pingIntervalMillis);
        }
    }

    private void startHttp2(int pingIntervalMillis) throws IOException {
        socket.setSoTimeout(0); // HTTP/2 connection timeouts are set per-stream.
        http2Connection = new Http2Connection.Builder(true)
                .socket(socket, route.address().url().host(), source, sink)
                .listener(this)
                .pingIntervalMillis(pingIntervalMillis)
                .build();
        http2Connection.start();
    }

    private void connectTls(ConnectionSpecSelector connectionSpecSelector) throws IOException {
        Address address = route.address();
        SSLSocketFactory sslSocketFactory = address.sslSocketFactory();
        boolean success = false;
        SSLSocket sslSocket = null;
        try {
            // Create the wrapper over the connected socket.
            //1 用SSLSocketFactory基于原始的TCP Socket，创建一个SSLSocket。
            sslSocket = (SSLSocket) sslSocketFactory.createSocket(
                    rawSocket, address.url().host(), address.url().port(), true /* autoClose */);

            // Configure the socket's ciphers, TLS versions, and extensions.
            //配置套接字的密码，TLS版本和扩展。
            //在前面选择的ConnectionSpec支持TLS扩展参数时，配置TLS扩展参数。
            ConnectionSpec connectionSpec = connectionSpecSelector.configureSecureSocket(sslSocket);
            if (connectionSpec.supportsTlsExtensions()) {
                Platform.get().configureTlsExtensions(
                        sslSocket, address.url().host(), address.protocols());
            }

            // Force handshake. This can throw!
            //4 启动TLS握手。
            sslSocket.startHandshake();
            // block for session establishment
            //5 TLS握手完成之后，获取握手信息。
            SSLSession sslSocketSession = sslSocket.getSession();
            if (!isValid(sslSocketSession)) {
                throw new IOException("a valid ssl session was not established");
            }
            Handshake unverifiedHandshake = Handshake.get(sslSocketSession);

            // Verify that the socket's certificates are acceptable for the target host.
            //6 对TLS握手过程中传回来的证书进行验证。
            if (!address.hostnameVerifier().verify(address.url().host(), sslSocketSession)) {
                X509Certificate cert = (X509Certificate) unverifiedHandshake.peerCertificates().get(0);
                throw new SSLPeerUnverifiedException("Hostname " + address.url().host() + " not verified:"
                        + "\n    certificate: " + CertificatePinner.pin(cert)
                        + "\n    DN: " + cert.getSubjectDN().getName()
                        + "\n    subjectAltNames: " + OkHostnameVerifier.allSubjectAltNames(cert));
            }

            // Check that the certificate pinner is satisfied by the certificates presented.
            //7检查证书钉扎。
            address.certificatePinner().check(address.url().host(),
                    unverifiedHandshake.peerCertificates());

            // Success! Save the handshake and the ALPN protocol.
            //在前面选择的ConnectionSpec支持TLS扩展参数时，获取TLS握手过程中顺便完成的协议协商过程所选择的协议。这个过程主要用于HTTP/2的ALPN扩展。
            String maybeProtocol = connectionSpec.supportsTlsExtensions()
                    ? Platform.get().getSelectedProtocol(sslSocket)
                    : null;
            socket = sslSocket;
            source = Okio.buffer(Okio.source(socket));
            sink = Okio.buffer(Okio.sink(socket));
            handshake = unverifiedHandshake;
            protocol = maybeProtocol != null
                    ? Protocol.get(maybeProtocol)
                    : Protocol.HTTP_1_1;
            success = true;
        } catch (AssertionError e) {
            if (Util.isAndroidGetsocknameError(e)) throw new IOException(e);
            throw e;
        } finally {
            if (sslSocket != null) {
                Platform.get().afterHandshake(sslSocket);
            }
            if (!success) {
                closeQuietly(sslSocket);
            }
        }
    }

    private boolean isValid(SSLSession sslSocketSession) {
        // don't use SslSocket.getSession since for failed results it returns SSL_NULL_WITH_NULL_NULL
        return !"NONE".equals(sslSocketSession.getProtocol()) && !"SSL_NULL_WITH_NULL_NULL".equals(
                sslSocketSession.getCipherSuite());
    }

    /**
     * To make an HTTPS connection over an HTTP proxy, send an unencrypted CONNECT request to create
     * the proxy connection. This may need to be retried if the proxy requires authorization.
     * 要通过HTTP代理建立HTTPS连接，请发送未加密的CONNECT请求以创建代理连接。 如果代理需要授权，则可能需要重试。
     */
    private Request createTunnel(int readTimeout, int writeTimeout, Request tunnelRequest,
                                 HttpUrl url) throws IOException {
        // Make an SSL Tunnel on the first message pair of each SSL + proxy connection.
        // 在每个SSL +代理连接的第一个消息对上创建一个SSL隧道。
        String requestLine = "CONNECT " + Util.hostHeader(url, true) + " HTTP/1.1";
        while (true) {
            Http1Codec tunnelConnection = new Http1Codec(null, null, source, sink);
            source.timeout().timeout(readTimeout, MILLISECONDS);
            sink.timeout().timeout(writeTimeout, MILLISECONDS);
            tunnelConnection.writeRequest(tunnelRequest.headers(), requestLine);
            //sink.flush();
            tunnelConnection.finishRequest();
            Response response = tunnelConnection.readResponseHeaders(false)
                    .request(tunnelRequest)
                    .build();
            // The response body from a CONNECT should be empty, but if it is not then we should consume
            // it before proceeding.
            long contentLength = HttpHeaders.contentLength(response);
            if (contentLength == -1L) {
                contentLength = 0L;
            }
            Source body = tunnelConnection.newFixedLengthSource(contentLength);
            Util.skipAll(body, Integer.MAX_VALUE, TimeUnit.MILLISECONDS);
            body.close();

            switch (response.code()) {
                case HTTP_OK:
                    // Assume the server won't send a TLS ServerHello until we send a TLS ClientHello. If
                    // that happens, then we will have buffered bytes that are needed by the SSLSocket!
                    // This check is imperfect: it doesn't tell us whether a handshake will succeed, just
                    // that it will almost certainly fail because the proxy has sent unexpected data.
                    if (!source.buffer().exhausted() || !sink.buffer().exhausted()) {
                        throw new IOException("TLS tunnel buffered too many bytes!");
                    }
                    return null;

                case HTTP_PROXY_AUTH:
                    tunnelRequest = route.address().proxyAuthenticator().authenticate(route, response);
                    if (tunnelRequest == null)
                        throw new IOException("Failed to authenticate with proxy");

                    if ("close".equalsIgnoreCase(response.header("Connection"))) {
                        return tunnelRequest;
                    }
                    break;

                default:
                    throw new IOException(
                            "Unexpected response code for CONNECT: " + response.code());
            }
        }
    }

    /**
     * Returns a request that creates a TLS tunnel via an HTTP proxy. Everything in the tunnel request
     * is sent unencrypted to the proxy server, so tunnels include only the minimum set of headers.
     * This avoids sending potentially sensitive data like HTTP cookies to the proxy unencrypted.
     */
    private Request createTunnelRequest() {
        return new Request.Builder()
                .url(route.address().url())
                .header("Host", Util.hostHeader(route.address().url(), true))
                .header("Proxy-Connection", "Keep-Alive") // For HTTP/1.0 proxies like Squid.
                .header("User-Agent", Version.userAgent())
                .build();
    }

    /**
     * Returns true if this connection can carry a stream allocation to {@code address}. If non-null
     * {@code route} is the resolved route for a connection.
     */
    public boolean isEligible(Address address, @Nullable Route route) {
        // If this connection is not accepting new streams, we're done.
        if (allocations.size() >= allocationLimit || noNewStreams) return false;

        // If the non-host fields of the address don't overlap, we're done.
        if (!Internal.instance.equalsNonHost(this.route.address(), address)) return false;

        // If the host exactly matches, we're done: this connection can carry the address.
        if (address.url().host().equals(this.route().address().url().host())) {
            return true; // This connection is a perfect match.
        }

        // At this point we don't have a hostname match. But we still be able to carry the request if
        // our connection coalescing requirements are met. See also:
        // https://hpbn.co/optimizing-application-delivery/#eliminate-domain-sharding
        // https://daniel.haxx.se/blog/2016/08/18/http2-connection-coalescing/

        // 1. This connection must be HTTP/2.
        if (http2Connection == null) return false;

        // 2. The routes must share an IP address. This requires us to have a DNS address for both
        // hosts, which only happens after route planning. We can't coalesce connections that use a
        // proxy, since proxies don't tell us the origin server's IP address.
        if (route == null) return false;
        if (route.proxy().type() != Proxy.Type.DIRECT) return false;
        if (this.route.proxy().type() != Proxy.Type.DIRECT) return false;
        if (!this.route.socketAddress().equals(route.socketAddress())) return false;

        // 3. This connection's server certificate's must cover the new host.
        if (route.address().hostnameVerifier() != OkHostnameVerifier.INSTANCE) return false;
        if (!supportsUrl(address.url())) return false;

        // 4. Certificate pinning must match the host.
        try {
            address.certificatePinner().check(address.url().host(), handshake().peerCertificates());
        } catch (SSLPeerUnverifiedException e) {
            return false;
        }

        return true; // The caller's address can be carried by this connection.
    }

    public boolean supportsUrl(HttpUrl url) {
        if (url.port() != route.address().url().port()) {
            return false; // Port mismatch.
        }

        if (!url.host().equals(route.address().url().host())) {
            // We have a host mismatch. But if the certificate matches, we're still good.
            return handshake != null && OkHostnameVerifier.INSTANCE.verify(
                    url.host(), (X509Certificate) handshake.peerCertificates().get(0));
        }

        return true; // Success. The URL is supported.
    }

    public HttpCodec newCodec(OkHttpClient client, Interceptor.Chain chain,
                              StreamAllocation streamAllocation) throws SocketException {
        if (http2Connection != null) {
            return new Http2Codec(client, chain, streamAllocation, http2Connection);
        } else {
            socket.setSoTimeout(chain.readTimeoutMillis());
            source.timeout().timeout(chain.readTimeoutMillis(), MILLISECONDS);
            sink.timeout().timeout(chain.writeTimeoutMillis(), MILLISECONDS);
            return new Http1Codec(client, streamAllocation, source, sink);
        }
    }

    public RealWebSocket.Streams newWebSocketStreams(final StreamAllocation streamAllocation) {
        return new RealWebSocket.Streams(true, source, sink) {
            @Override
            public void close() throws IOException {
                streamAllocation.streamFinished(true, streamAllocation.codec(), -1L, null);
            }
        };
    }

    @Override
    public Route route() {
        return route;
    }

    public void cancel() {
        // Close the raw socket so we don't end up doing synchronous I/O.
        closeQuietly(rawSocket);
    }

    @Override
    public Socket socket() {
        return socket;
    }

    /**
     * Returns true if this connection is ready to host new streams.
     */
    public boolean isHealthy(boolean doExtensiveChecks) {
        if (socket.isClosed() || socket.isInputShutdown() || socket.isOutputShutdown()) {
            return false;
        }

        if (http2Connection != null) {
            return !http2Connection.isShutdown();
        }

        if (doExtensiveChecks) {
            try {
                int readTimeout = socket.getSoTimeout();
                try {
                    socket.setSoTimeout(1);
                    if (source.exhausted()) {
                        return false; // Stream is exhausted; socket is closed.
                    }
                    return true;
                } finally {
                    socket.setSoTimeout(readTimeout);
                }
            } catch (SocketTimeoutException ignored) {
                // Read timed out; socket is good.
            } catch (IOException e) {
                return false; // Couldn't read; socket is closed.
            }
        }

        return true;
    }

    /**
     * Refuse incoming streams.
     */
    @Override
    public void onStream(Http2Stream stream) throws IOException {
        stream.close(ErrorCode.REFUSED_STREAM);
    }

    /**
     * When settings are received, adjust the allocation limit.
     */
    @Override
    public void onSettings(Http2Connection connection) {
        synchronized (connectionPool) {
            allocationLimit = connection.maxConcurrentStreams();
        }
    }

    @Override
    public Handshake handshake() {
        return handshake;
    }

    /**
     * Returns true if this is an HTTP/2 connection. Such connections can be used in multiple HTTP
     * requests simultaneously.
     */
    public boolean isMultiplexed() {
        return http2Connection != null;
    }

    @Override
    public Protocol protocol() {
        return protocol;
    }

    @Override
    public String toString() {
        return "Connection{"
                + route.address().url().host() + ":" + route.address().url().port()
                + ", proxy="
                + route.proxy()
                + " hostAddress="
                + route.socketAddress()
                + " cipherSuite="
                + (handshake != null ? handshake.cipherSuite() : "none")
                + " protocol="
                + protocol
                + '}';
    }
}
