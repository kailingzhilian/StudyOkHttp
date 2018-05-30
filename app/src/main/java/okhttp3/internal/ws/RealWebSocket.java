/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.ws;

import java.io.Closeable;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.internal.Internal;
import okhttp3.internal.Util;
import okhttp3.internal.connection.StreamAllocation;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static okhttp3.internal.Util.closeQuietly;
import static okhttp3.internal.ws.WebSocketProtocol.CLOSE_CLIENT_GOING_AWAY;
import static okhttp3.internal.ws.WebSocketProtocol.CLOSE_MESSAGE_MAX;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_BINARY;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_TEXT;
import static okhttp3.internal.ws.WebSocketProtocol.validateCloseCode;

public final class RealWebSocket implements WebSocket, WebSocketReader.FrameCallback {
    private static final List<Protocol> ONLY_HTTP1 = Collections.singletonList(Protocol.HTTP_1_1);

    /**
     * The maximum number of bytes to enqueue. Rather than enqueueing beyond this limit we tear down
     * the web socket! It's possible that we're writing faster than the peer can read.
     */
    private static final long MAX_QUEUE_SIZE = 16 * 1024 * 1024; // 16 MiB.

    /**
     * The maximum amount of time after the client calls {@link #close} to wait for a graceful
     * shutdown. If the server doesn't respond the websocket will be canceled.
     */
    private static final long CANCEL_AFTER_CLOSE_MILLIS = 60 * 1000;

    /**
     * The application's original request unadulterated by web socket headers.
     */
    private final Request originalRequest;

    final WebSocketListener listener;
    private final Random random;
    private final long pingIntervalMillis;
    private final String key;

    /**
     * Non-null for client web sockets. These can be canceled.
     */
    private Call call;

    /**
     * This runnable processes the outgoing queues. Call {@link #runWriter()} to after enqueueing.
     */
    private final Runnable writerRunnable;

    /**
     * Null until this web socket is connected. Only accessed by the reader thread.
     */
    private WebSocketReader reader;

    // All mutable web socket state is guarded by this.

    /**
     * Null until this web socket is connected. Note that messages may be enqueued before that.
     */
    private WebSocketWriter writer;

    /**
     * Null until this web socket is connected. Used for writes, pings, and close timeouts.
     */
    private ScheduledExecutorService executor;

    /**
     * The streams held by this web socket. This is non-null until all incoming messages have been
     * read and all outgoing messages have been written. It is closed when both reader and writer are
     * exhausted, or if there is any failure.
     */
    private Streams streams;

    /**
     * Outgoing pongs in the order they should be written.
     */
    private final ArrayDeque<ByteString> pongQueue = new ArrayDeque<>();

    /**
     * Outgoing messages and close frames in the order they should be written.
     */
    private final ArrayDeque<Object> messageAndCloseQueue = new ArrayDeque<>();

    /**
     * The total size in bytes of enqueued but not yet transmitted messages.
     */
    private long queueSize;

    /**
     * True if we've enqueued a close frame. No further message frames will be enqueued.
     */
    private boolean enqueuedClose;

    /**
     * When executed this will cancel this websocket. This future itself should be canceled if that is
     * unnecessary because the web socket is already closed or canceled.
     */
    private ScheduledFuture<?> cancelFuture;

    /**
     * The close code from the peer, or -1 if this web socket has not yet read a close frame.
     * 来自同位体的关闭代码，如果此Web套接字尚未读取关闭帧，则为-1。
     */
    private int receivedCloseCode = -1;

    /**
     * The close reason from the peer, or null if this web socket has not yet read a close frame.
     */
    private String receivedCloseReason;

    /**
     * True if this web socket failed and the listener has been notified.
     */
    private boolean failed;

    /**
     * Total number of pings sent by this web socket.
     */
    private int sentPingCount;

    /**
     * Total number of pings received by this web socket.
     */
    private int receivedPingCount;

    /**
     * Total number of pongs received by this web socket.
     */
    private int receivedPongCount;

    /**
     * True if we have sent a ping that is still awaiting a reply.
     */
    private boolean awaitingPong;

    public RealWebSocket(Request request, WebSocketListener listener, Random random,
                         long pingIntervalMillis) {
        //使用get的方式进行握手
        if (!"GET".equals(request.method())) {
            throw new IllegalArgumentException("Request must be GET: " + request.method());
        }
        this.originalRequest = request;
        this.listener = listener;
        this.random = random;
        this.pingIntervalMillis = pingIntervalMillis;
        //初始化key，以备后续连接建立及握手之用。
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        this.key = ByteString.of(nonce).base64();
        //初始化了 writerRunnable
        this.writerRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    while (writeOneFrame()) {
                    }
                } catch (IOException e) {
                    failWebSocket(e, null);
                }
            }
        };
    }

    @Override
    public Request request() {
        return originalRequest;
    }

    @Override
    public synchronized long queueSize() {
        return queueSize;
    }

    @Override
    public void cancel() {
        call.cancel();
    }

    public void connect(OkHttpClient client) {
        client = client.newBuilder()
                .eventListener(EventListener.NONE)
                .protocols(ONLY_HTTP1)
                .build();
        final Request request = originalRequest.newBuilder()
                .header("Upgrade", "websocket")
                .header("Connection", "Upgrade")
                .header("Sec-WebSocket-Key", key)
                .header("Sec-WebSocket-Version", "13")
                .build();
        call = Internal.instance.newWebSocketCall(client, request);
        /**
         * WebSocket协议首先会通过发送一个http请求来完成一个握手的过程
         * 客户端发送一个请求协议升级的get请求给服务端
         * 服务端如果支持的话会返回http code 为101，表示可以切换到对应的协议
         */
        call.enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) {
                try {
                    //第一步就是检查 HTTP 响应
                    checkResponse(response);
                } catch (ProtocolException e) {
                    failWebSocket(e, response);
                    closeQuietly(response);
                    return;
                }


                //将HTTP流推广到Web套接字流中
                StreamAllocation streamAllocation = Internal.instance.streamAllocation(call);
                streamAllocation.noNewStreams(); //阻止连接池

                //第二步：初始化用于输入输出的 Source 和 Sink。
                // Source 和 Sink 创建于之前发送HTTP请求的时候。这里会阻止在这个连接上再创建新的流。
                Streams streams = streamAllocation.connection().newWebSocketStreams(streamAllocation);

                // Process all web socket messages.
                try {
                    //第三步是调用回调 onOpen()。
                    listener.onOpen(RealWebSocket.this, response);
                    //第四步是初始化 Reader 和 Writer：
                    String name = "OkHttp WebSocket " + request.url().redact();
                    initReaderAndWriter(name, streams);
                    //第五步是配置socket的超时时间为0，也就是阻塞IO。
                    streamAllocation.connection().socket().setSoTimeout(0);
                    //第六步执行 loopReader()。这实际上是进入了消息读取循环了，也就是数据接收的逻辑了。
                    loopReader();
                } catch (Exception e) {
                    failWebSocket(e, null);
                }
            }

            @Override
            public void onFailure(Call call, IOException e) {
                failWebSocket(e, null);
            }
        });
    }

    void checkResponse(Response response) throws ProtocolException {
        //响应码是 101。
        if (response.code() != 101) {
            throw new ProtocolException("Expected HTTP 101 response but was '"
                    + response.code() + " " + response.message() + "'");
        }
        //"Connection" header 的值为 "Upgrade"，以表明服务器并没有在处理完请求之后把连接个断开。
        String headerConnection = response.header("Connection");
        if (!"Upgrade".equalsIgnoreCase(headerConnection)) {
            throw new ProtocolException("Expected 'Connection' header value 'Upgrade' but was '"
                    + headerConnection + "'");
        }
        //"Upgrade" header 的值为 "websocket"，以表明服务器接受后面使用 WebSocket 来通信。
        String headerUpgrade = response.header("Upgrade");
        if (!"websocket".equalsIgnoreCase(headerUpgrade)) {
            throw new ProtocolException(
                    "Expected 'Upgrade' header value 'websocket' but was '" + headerUpgrade + "'");
        }
        //"Sec-WebSocket-Accept" header 的值为，key + WebSocketProtocol.ACCEPT_MAGIC 做 SHA1 hash，然后做 base64 编码，来做服务器接受连接的验证。
        String headerAccept = response.header("Sec-WebSocket-Accept");
        String acceptExpected = ByteString.encodeUtf8(key + WebSocketProtocol.ACCEPT_MAGIC)
                .sha1().base64();
        if (!acceptExpected.equals(headerAccept)) {
            throw new ProtocolException("Expected 'Sec-WebSocket-Accept' header value '"
                    + acceptExpected + "' but was '" + headerAccept + "'");
        }
    }

    public void initReaderAndWriter(String name, Streams streams) throws IOException {
        synchronized (this) {
            this.streams = streams;
            this.writer = new WebSocketWriter(streams.client, streams.sink, random);
            this.executor = new ScheduledThreadPoolExecutor(1, Util.threadFactory(name, false));
            if (pingIntervalMillis != 0) {
                executor.scheduleAtFixedRate(
                        new PingRunnable(), pingIntervalMillis, pingIntervalMillis, MILLISECONDS);
            }
            if (!messageAndCloseQueue.isEmpty()) {
                runWriter(); // Send messages that were enqueued before we were connected.
            }
        }

        reader = new WebSocketReader(streams.client, streams.source, this);
    }

    /**
     * Receive frames until there are no more. Invoked only by the reader thread.
     * 接收帧，直到没有更多。 仅由读者线程调用。
     */
    public void loopReader() throws IOException {
        while (receivedCloseCode == -1) {
            // This method call results in one or more onRead* methods being called on this thread.
            //此方法调用将导致在此线程上调用一个或多个onRead *方法
            reader.processNextFrame();
        }
    }

    /**
     * For testing: receive a single frame and return true if there are more frames to read. Invoked
     * only by the reader thread.
     */
    boolean processNextFrame() throws IOException {
        try {
            reader.processNextFrame();
            return receivedCloseCode == -1;
        } catch (Exception e) {
            failWebSocket(e, null);
            return false;
        }
    }

    /**
     * For testing: wait until the web socket's executor has terminated.
     */
    void awaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException {
        executor.awaitTermination(timeout, timeUnit);
    }

    /**
     * For testing: force this web socket to release its threads.
     */
    void tearDown() throws InterruptedException {
        if (cancelFuture != null) {
            cancelFuture.cancel(false);
        }
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    synchronized int sentPingCount() {
        return sentPingCount;
    }

    synchronized int receivedPingCount() {
        return receivedPingCount;
    }

    synchronized int receivedPongCount() {
        return receivedPongCount;
    }

    @Override
    public void onReadMessage(String text) throws IOException {
        listener.onMessage(this, text);
    }

    @Override
    public void onReadMessage(ByteString bytes) throws IOException {
        listener.onMessage(this, bytes);
    }

    @Override
    public synchronized void onReadPing(ByteString payload) {
        // Don't respond to pings after we've failed or sent the close frame.
        if (failed || (enqueuedClose && messageAndCloseQueue.isEmpty())) return;

        pongQueue.add(payload);
        runWriter();
        receivedPingCount++;
    }

    @Override
    public synchronized void onReadPong(ByteString buffer) {
        // This API doesn't expose pings.
        receivedPongCount++;
        awaitingPong = false;
    }

    @Override
    public void onReadClose(int code, String reason) {
        if (code == -1) throw new IllegalArgumentException();

        Streams toClose = null;
        synchronized (this) {
            if (receivedCloseCode != -1) throw new IllegalStateException("already closed");
            receivedCloseCode = code;
            receivedCloseReason = reason;
            if (enqueuedClose && messageAndCloseQueue.isEmpty()) {
                toClose = this.streams;
                this.streams = null;
                if (cancelFuture != null) cancelFuture.cancel(false);
                this.executor.shutdown();
            }
        }

        try {
            listener.onClosing(this, code, reason);

            if (toClose != null) {
                listener.onClosed(this, code, reason);
            }
        } finally {
            closeQuietly(toClose);
        }
    }

    // Writer methods to enqueue frames. They'll be sent asynchronously by the writer thread.

    @Override
    public boolean send(String text) {
        if (text == null) throw new NullPointerException("text == null");
        return send(ByteString.encodeUtf8(text), OPCODE_TEXT);
    }

    @Override
    public boolean send(ByteString bytes) {
        if (bytes == null) throw new NullPointerException("bytes == null");
        return send(bytes, OPCODE_BINARY);
    }

    private synchronized boolean send(ByteString data, int formatOpcode) {
        //对于发送失败过或被关闭了的 WebSocket，将无法再发送信息。
        // Don't send new frames after we've failed or enqueued a close frame.
        if (failed || enqueuedClose) return false;

        //当消息队列中的未发送数据超出最大大小限制，WebSocket 连接会被直接关闭。
        if (queueSize + data.size() > MAX_QUEUE_SIZE) {
            close(CLOSE_CLIENT_GOING_AWAY, null);
            return false;
        }

        // Enqueue the message frame.
        queueSize += data.size();
        messageAndCloseQueue.add(new Message(formatOpcode, data));
        runWriter();
        return true;
    }

    synchronized boolean pong(ByteString payload) {
        // Don't send pongs after we've failed or sent the close frame.
        if (failed || (enqueuedClose && messageAndCloseQueue.isEmpty())) return false;

        pongQueue.add(payload);
        runWriter();
        return true;
    }

    @Override
    public boolean close(int code, String reason) {
        return close(code, reason, CANCEL_AFTER_CLOSE_MILLIS);
    }

    synchronized boolean close(int code, String reason, long cancelAfterCloseMillis) {
        //先检查一下 close code 的有效性在合法范围内
        validateCloseCode(code);
        //构造一个 Close 消息放入发送消息队列
        ByteString reasonBytes = null;
        if (reason != null) {
            reasonBytes = ByteString.encodeUtf8(reason);
            if (reasonBytes.size() > CLOSE_MESSAGE_MAX) {
                throw new IllegalArgumentException("reason.size() > " + CLOSE_MESSAGE_MAX + ": " + reason);
            }
        }

        if (failed || enqueuedClose) return false;

        // Immediately prevent further frames from being enqueued.
        enqueuedClose = true;

        // Enqueue the close frame.
        messageAndCloseQueue.add(new Close(code, reasonBytes, cancelAfterCloseMillis));
        //调度 writerRunnable 执行
        runWriter();
        return true;
    }

    private void runWriter() {
        assert (Thread.holdsLock(this));

        if (executor != null) {
            executor.execute(writerRunnable);
        }
    }

    /**
     * Attempts to remove a single frame from a queue and send it. This prefers to write urgent pongs
     * before less urgent messages and close frames. For example it's possible that a caller will
     * enqueue messages followed by pongs, but this sends pongs followed by messages. Pongs are always
     * written in the order they were enqueued.
     * <p>
     * <p>If a frame cannot be sent - because there are none enqueued or because the web socket is not
     * connected - this does nothing and returns false. Otherwise this returns true and the caller
     * should immediately invoke this method again until it returns false.
     * <p>
     * <p>This method may only be invoked by the writer thread. There may be only thread invoking this
     * method at a time.
     */
    boolean writeOneFrame() throws IOException {
        WebSocketWriter writer;
        ByteString pong;
        Object messageOrClose = null;
        int receivedCloseCode = -1;
        String receivedCloseReason = null;
        Streams streamsToClose = null;

        synchronized (RealWebSocket.this) {
            if (failed) {
                return false; // websocket连接失败，跳出循环
            }

            writer = this.writer;
            pong = pongQueue.poll();
            //判断是否有pong消息
            if (pong == null) {
                messageOrClose = messageAndCloseQueue.poll();
                //判断是否为关闭帧
                if (messageOrClose instanceof Close) {
                    //如果此Web套接字尚未读取关闭帧，则为-1
                    receivedCloseCode = this.receivedCloseCode;
                    receivedCloseReason = this.receivedCloseReason;
                    if (receivedCloseCode != -1) {
                        //被动关闭的发送-停掉发送消息用的 executor
                        //会通过 onClosed() 通知用户
                        streamsToClose = this.streams;
                        this.streams = null;
                        this.executor.shutdown();
                    } else {
                        //主动关闭的发送
                        //不会通过 onClosed() 通知用户
                        // When we request a graceful close also schedule a cancel of the websocket.
                        //当我们要求优雅的关闭时也安排取消websocket
                        cancelFuture = executor.schedule(new CancelRunnable(),
                                ((Close) messageOrClose).cancelAfterCloseMillis, MILLISECONDS);
                    }
                } else if (messageOrClose == null) {
                    return false; //消息队列为空，跳出循环
                }
            }
        }

        try {
            if (pong != null) {
                writer.writePong(pong);

            } else if (messageOrClose instanceof Message) {
                ByteString data = ((Message) messageOrClose).data;
                //这里将数据转化为可供websocket交互的格式
                BufferedSink sink = Okio.buffer(writer.newMessageSink(
                        ((Message) messageOrClose).formatOpcode, data.size()));
                sink.write(data);
                sink.close();
                synchronized (this) {
                    queueSize -= data.size();
                }

            } else if (messageOrClose instanceof Close) {
                Close close = (Close) messageOrClose;
                writer.writeClose(close.code, close.reason);

                // We closed the writer: now both reader and writer are closed.
                if (streamsToClose != null) {
                    listener.onClosed(this, receivedCloseCode, receivedCloseReason);
                }

            } else {
                throw new AssertionError();
            }

            return true;
        } finally {
            //释放资源
            closeQuietly(streamsToClose);
        }
    }

    private final class PingRunnable implements Runnable {
        PingRunnable() {
        }

        @Override
        public void run() {
            writePingFrame();
        }
    }

    void writePingFrame() {
        WebSocketWriter writer;
        int failedPing;
        synchronized (this) {
            if (failed) return;
            writer = this.writer;
            failedPing = awaitingPong ? sentPingCount : -1;
            sentPingCount++;
            awaitingPong = true;
        }

        if (failedPing != -1) {
            failWebSocket(new SocketTimeoutException("sent ping but didn't receive pong within "
                            + pingIntervalMillis + "ms (after " + (failedPing - 1) + " successful ping/pongs)"),
                    null);
            return;
        }

        try {
            writer.writePing(ByteString.EMPTY);
        } catch (IOException e) {
            failWebSocket(e, null);
        }
    }

    public void failWebSocket(Exception e, @Nullable Response response) {
        Streams streamsToClose;
        synchronized (this) {
            if (failed) return; // Already failed.
            failed = true;
            streamsToClose = this.streams;
            this.streams = null;
            if (cancelFuture != null) cancelFuture.cancel(false);
            if (executor != null) executor.shutdown();
        }

        try {
            listener.onFailure(this, e, response);
        } finally {
            closeQuietly(streamsToClose);
        }
    }

    static final class Message {
        final int formatOpcode;
        final ByteString data;

        Message(int formatOpcode, ByteString data) {
            this.formatOpcode = formatOpcode;
            this.data = data;
        }
    }

    static final class Close {
        final int code;
        final ByteString reason;
        final long cancelAfterCloseMillis;

        Close(int code, ByteString reason, long cancelAfterCloseMillis) {
            this.code = code;
            this.reason = reason;
            this.cancelAfterCloseMillis = cancelAfterCloseMillis;
        }
    }

    public abstract static class Streams implements Closeable {
        public final boolean client;
        public final BufferedSource source;
        public final BufferedSink sink;

        public Streams(boolean client, BufferedSource source, BufferedSink sink) {
            this.client = client;
            this.source = source;
            this.sink = sink;
        }
    }

    final class CancelRunnable implements Runnable {
        @Override
        public void run() {
            cancel();
        }
    }
}
