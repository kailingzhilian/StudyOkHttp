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
package okhttp3.internal.http;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpRetryException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;

import okhttp3.Address;
import okhttp3.Call;
import okhttp3.CertificatePinner;
import okhttp3.EventListener;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;
import okhttp3.internal.connection.RouteException;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http2.ConnectionShutdownException;

import static java.net.HttpURLConnection.HTTP_CLIENT_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static java.net.HttpURLConnection.HTTP_SEE_OTHER;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static okhttp3.internal.Util.closeQuietly;
import static okhttp3.internal.http.StatusLine.HTTP_PERM_REDIRECT;
import static okhttp3.internal.http.StatusLine.HTTP_TEMP_REDIRECT;

/**
 * This interceptor recovers from failures and follows redirects as necessary. It may throw an
 * {@link IOException} if the call was canceled.
 * 这个拦截器从故障中恢复并在必要时遵循重定向。 如果呼叫被取消，它可能会抛出{@link IOException}。
 */
public final class RetryAndFollowUpInterceptor implements Interceptor {
    /**
     * How many redirects and auth challenges should we attempt? Chrome follows 21 redirects; Firefox,
     * curl, and wget follow 20; Safari follows 16; and HTTP/1.0 recommends 5.
     * *我们应该尝试多少次重定向和认证挑战？
     * Chrome遵循21次重定向; Firefox，curl和wget遵循20; Safari遵循16; HTTP / 1.0建议5
     */
    private static final int MAX_FOLLOW_UPS = 20;

    private final OkHttpClient client;
    private final boolean forWebSocket;
    private volatile StreamAllocation streamAllocation;
    private Object callStackTrace;
    private volatile boolean canceled;

    public RetryAndFollowUpInterceptor(OkHttpClient client, boolean forWebSocket) {
        this.client = client;
        this.forWebSocket = forWebSocket;
    }

    /**
     * Immediately closes the socket connection if it's currently held. Use this to interrupt an
     * in-flight request from any thread. It's the caller's responsibility to close the request body
     * and response body streams; otherwise resources may be leaked.
     * <p>
     * <p>This method is safe to be called concurrently, but provides limited guarantees. If a
     * transport layer connection has been established (such as a HTTP/2 stream) that is terminated.
     * Otherwise if a socket connection is being established, that is terminated.
     * <p>
     * 如果当前持有，立即关闭套接字连接。
     * 使用它来中断来自任何线程的正在进行的请求。
     * 关闭请求主体和响应主体流是调用者的责任; 否则资源可能会泄露。
     * <p>
     * <p>此方法可安全地同时调用，但提供有限的保证。
     * 如果传输层连接已建立（例如HTTP / 2流）已终止。
     * 否则，如果套接字连接正在建立，则终止。
     */
    public void cancel() {
        canceled = true;
        StreamAllocation streamAllocation = this.streamAllocation;
        if (streamAllocation != null) streamAllocation.cancel();
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void setCallStackTrace(Object callStackTrace) {
        this.callStackTrace = callStackTrace;
    }

    public StreamAllocation streamAllocation() {
        return streamAllocation;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        RealInterceptorChain realChain = (RealInterceptorChain) chain;
        Call call = realChain.call();
        EventListener eventListener = realChain.eventListener();
        //1. 构建一个StreamAllocation对象，StreamAllocation相当于是个管理类，维护了
        //Connections、Streams和Calls之间的管理，该类初始化一个Socket连接对象，获取输入/输出流对象。
        StreamAllocation streamAllocation = new StreamAllocation(client.connectionPool(),
                createAddress(request.url()), call, eventListener, callStackTrace);
        this.streamAllocation = streamAllocation;
        //重定向次数
        int followUpCount = 0;
        Response priorResponse = null;

        //将前一步得到的followUp 赋值给request,重新进入循环,
        while (true) {
            if (canceled) {
                //删除连接上的call请求
                streamAllocation.release();
                throw new IOException("Canceled");
            }

            Response response;
            boolean releaseConnection = true;
            try {

                //2. 继续执行下一个Interceptor，即BridgeInterceptor
                response = realChain.proceed(request, streamAllocation, null, null);
                releaseConnection = false;
            } catch (RouteException e) {
                //抛出异常以指示通过单个路由连接的问题。
                // The attempt to connect via a route failed. The request will not have been sent.
                //尝试通过路由进行连接失败。 该请求不会被发送
                if (!recover(e.getLastConnectException(), streamAllocation, false, request)) {
                    throw e.getLastConnectException();
                }
                releaseConnection = false;
                continue;
            } catch (IOException e) {
                // An attempt to communicate with a server failed. The request may have been sent.
                //尝试与服务器通信失败。 该请求可能已发送
                boolean requestSendStarted = !(e instanceof ConnectionShutdownException);
                if (!recover(e, streamAllocation, requestSendStarted, request)) throw e;
                releaseConnection = false;
                continue;
            } finally {
                // We're throwing an unchecked exception. Release any resources.
                // 检测到其他未知异常，则释放连接和资源
                if (releaseConnection) {
                    streamAllocation.streamFailed(null);
                    streamAllocation.release();
                }
            }

            // Attach the prior response if it exists. Such responses never have a body.
            //构建响应体，这个响应体的body为空。
            if (priorResponse != null) {
                response = response.newBuilder()
                        .priorResponse(priorResponse.newBuilder()
                                .body(null)
                                .build())
                        .build();
            }

            Request followUp;
            try {
                // 根据响应码处理请求，返回Request不为空时则进行重定向处理-拿到重定向的request
                followUp = followUpRequest(response, streamAllocation.route());
            } catch (IOException e) {
                streamAllocation.release();
                throw e;
            }

            if (followUp == null) {
                if (!forWebSocket) {
                    streamAllocation.release();
                }
                return response;
            }

            closeQuietly(response.body());

            //重定向的次数不能超过20次
            if (++followUpCount > MAX_FOLLOW_UPS) {
                streamAllocation.release();
                throw new ProtocolException("Too many follow-up requests: " + followUpCount);
            }

            if (followUp.body() instanceof UnrepeatableRequestBody) {
                streamAllocation.release();
                throw new HttpRetryException("Cannot retry streamed HTTP body", response.code());
            }

            if (!sameConnection(response, followUp.url())) {
                streamAllocation.release();
                streamAllocation = new StreamAllocation(client.connectionPool(),
                        createAddress(followUp.url()), call, eventListener, callStackTrace);
                this.streamAllocation = streamAllocation;
            } else if (streamAllocation.codec() != null) {
                throw new IllegalStateException("Closing the body of " + response
                        + " didn't close its backing stream. Bad interceptor?");
            }

            request = followUp;
            priorResponse = response;
        }
    }

    private Address createAddress(HttpUrl url) {
        SSLSocketFactory sslSocketFactory = null;
        HostnameVerifier hostnameVerifier = null;
        CertificatePinner certificatePinner = null;
        if (url.isHttps()) {
            sslSocketFactory = client.sslSocketFactory();
            hostnameVerifier = client.hostnameVerifier();
            certificatePinner = client.certificatePinner();
        }

        return new Address(url.host(), url.port(), client.dns(), client.socketFactory(),
                sslSocketFactory, hostnameVerifier, certificatePinner, client.proxyAuthenticator(),
                client.proxy(), client.protocols(), client.connectionSpecs(), client.proxySelector());
    }

    /**
     * Report and attempt to recover from a failure to communicate with a server. Returns true if
     * {@code e} is recoverable, or false if the failure is permanent. Requests with a body can only
     * be recovered if the body is buffered or if the failure occurred before the request has been
     * sent.
     * <p>
     * 报告并尝试从与服务器通信失败中恢复。
     * 如果{ecode}是可恢复的，则返回true;如果失败是永久的，则返回false。
     * 具有正文的请求只能在正文缓冲或发送请求之前发生故障时才能恢复。
     */
    private boolean recover(IOException e, StreamAllocation streamAllocation,
                            boolean requestSendStarted, Request userRequest) {
        streamAllocation.streamFailed(e);

        // The application layer has forbidden retries.
        //应用程序层禁止重试
        if (!client.retryOnConnectionFailure()) return false;

        // We can't send the request body again.
        //我们无法再发送请求体
        if (requestSendStarted && userRequest.body() instanceof UnrepeatableRequestBody)
            return false;

        // This exception is fatal.
        if (!isRecoverable(e, requestSendStarted)) return false;

        // No more routes to attempt.
        //没有更多的路线尝试。
        if (!streamAllocation.hasMoreRoutes()) return false;

        // For failure recovery, use the same route selector with a new connection.
        //对于故障恢复，请使用具有新连接的相同路由选择器
        return true;
    }

    private boolean isRecoverable(IOException e, boolean requestSendStarted) {
        // If there was a protocol problem, don't recover.
        if (e instanceof ProtocolException) {
            return false;
        }

        // If there was an interruption don't recover, but if there was a timeout connecting to a route
        // we should try the next route (if there is one).
        if (e instanceof InterruptedIOException) {
            return e instanceof SocketTimeoutException && !requestSendStarted;
        }

        // Look for known client-side or negotiation errors that are unlikely to be fixed by trying
        // again with a different route.
        if (e instanceof SSLHandshakeException) {
            // If the problem was a CertificateException from the X509TrustManager,
            // do not retry.
            if (e.getCause() instanceof CertificateException) {
                return false;
            }
        }
        if (e instanceof SSLPeerUnverifiedException) {
            // e.g. a certificate pinning error.
            return false;
        }

        // An example of one we might want to retry with a different route is a problem connecting to a
        // proxy and would manifest as a standard IOException. Unless it is one we know we should not
        // retry, we return true and try a new route.
        return true;
    }

    /**
     * Figures out the HTTP request to make in response to receiving {@code userResponse}. This will
     * either add authentication headers, follow redirects or handle a client request timeout. If a
     * follow-up is either unnecessary or not applicable, this returns null.
     * 计算出HTTP请求响应收到的userResponse响应。
     * 这将添加身份验证标头，遵循重定向或处理客户端请求超时。
     * 如果后续措施不必要或不适用，则返回null。
     */
    private Request followUpRequest(Response userResponse, Route route) throws IOException {
        if (userResponse == null) throw new IllegalStateException();
        int responseCode = userResponse.code();

        final String method = userResponse.request().method();
        switch (responseCode) {
            //407，代理认证
            case HTTP_PROXY_AUTH:
                Proxy selectedProxy = route != null
                        ? route.proxy()
                        : client.proxy();
                if (selectedProxy.type() != Proxy.Type.HTTP) {
                    throw new ProtocolException("Received HTTP_PROXY_AUTH (407) code while not using proxy");
                }
                return client.proxyAuthenticator().authenticate(route, userResponse);
            //401，未经认证
            case HTTP_UNAUTHORIZED:
                return client.authenticator().authenticate(route, userResponse);
            //307，308
            case HTTP_PERM_REDIRECT:
            case HTTP_TEMP_REDIRECT:
                // "If the 307 or 308 status code is received in response to a request other than GET
                // or HEAD, the user agent MUST NOT automatically redirect the request"
                //如果接收到307或308状态码以响应除GET或HEAD以外的请求，则用户代理绝不能自动重定向请求”
                if (!method.equals("GET") && !method.equals("HEAD")) {
                    return null;
                }
                // fall-through
                //300，301，302，303
            case HTTP_MULT_CHOICE:
            case HTTP_MOVED_PERM:
            case HTTP_MOVED_TEMP:
            case HTTP_SEE_OTHER:
                // Does the client allow redirects?
                //客户端在配置中是否允许重定向
                if (!client.followRedirects()) return null;

                String location = userResponse.header("Location");
                if (location == null) return null;
                HttpUrl url = userResponse.request().url().resolve(location);

                // Don't follow redirects to unsupported protocols.
                // url为null，不允许重定向
                if (url == null) return null;

                // If configured, don't follow redirects between SSL and non-SSL.
                //查询是否存在http与https之间的重定向
                boolean sameScheme = url.scheme().equals(userResponse.request().url().scheme());
                if (!sameScheme && !client.followSslRedirects()) return null;

                // Most redirects don't include a request body.
                //大多数重定向不包含请求体
                Request.Builder requestBuilder = userResponse.request().newBuilder();
                if (HttpMethod.permitsRequestBody(method)) {
                    final boolean maintainBody = HttpMethod.redirectsWithBody(method);
                    if (HttpMethod.redirectsToGet(method)) {
                        requestBuilder.method("GET", null);
                    } else {
                        RequestBody requestBody = maintainBody ? userResponse.request().body() : null;
                        requestBuilder.method(method, requestBody);
                    }
                    if (!maintainBody) {
                        requestBuilder.removeHeader("Transfer-Encoding");
                        requestBuilder.removeHeader("Content-Length");
                        requestBuilder.removeHeader("Content-Type");
                    }
                }

                // When redirecting across hosts, drop all authentication headers. This
                // is potentially annoying to the application layer since they have no
                // way to retain them.
                //在跨主机重定向时，请删除所有身份验证标头。 这对应用程序层来说可能很烦人，因为他们无法保留它们。
                if (!sameConnection(userResponse, url)) {
                    requestBuilder.removeHeader("Authorization");
                }

                return requestBuilder.url(url).build();
            //408，超时
            case HTTP_CLIENT_TIMEOUT:
                // 408's are rare in practice, but some servers like HAProxy use this response code. The
                // spec says that we may repeat the request without modifications. Modern browsers also
                // repeat the request (even non-idempotent ones.)
                if (!client.retryOnConnectionFailure()) {
                    // The application layer has directed us not to retry the request.
                    //应用程序层指示我们不要重试请求
                    return null;
                }

                if (userResponse.request().body() instanceof UnrepeatableRequestBody) {
                    return null;
                }

                if (userResponse.priorResponse() != null
                        && userResponse.priorResponse().code() == HTTP_CLIENT_TIMEOUT) {
                    // We attempted to retry and got another timeout. Give up.
                    return null;
                }

                if (retryAfter(userResponse, 0) > 0) {
                    return null;
                }

                return userResponse.request();

            case HTTP_UNAVAILABLE:
                if (userResponse.priorResponse() != null
                        && userResponse.priorResponse().code() == HTTP_UNAVAILABLE) {
                    // We attempted to retry and got another timeout. Give up.
                    return null;
                }

                if (retryAfter(userResponse, Integer.MAX_VALUE) == 0) {
                    // specifically received an instruction to retry without delay
                    return userResponse.request();
                }

                return null;

            default:
                return null;
        }
    }

    private int retryAfter(Response userResponse, int defaultDelay) {
        String header = userResponse.header("Retry-After");

        if (header == null) {
            return defaultDelay;
        }

        // https://tools.ietf.org/html/rfc7231#section-7.1.3
        // currently ignores a HTTP-date, and assumes any non int 0 is a delay
        if (header.matches("\\d+")) {
            return Integer.valueOf(header);
        }

        return Integer.MAX_VALUE;
    }

    /**
     * Returns true if an HTTP request for {@code followUp} can reuse the connection used by this
     * engine.
     */
    private boolean sameConnection(Response response, HttpUrl followUp) {
        HttpUrl url = response.request().url();
        return url.host().equals(followUp.host())
                && url.port() == followUp.port()
                && url.scheme().equals(followUp.scheme());
    }
}
