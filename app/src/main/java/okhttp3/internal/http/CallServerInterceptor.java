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
import java.net.ProtocolException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Util;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.connection.StreamAllocation;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.Okio;
import okio.Sink;

/**
 * This is the last interceptor in the chain. It makes a network call to the server.
 * 这是链中的最后一个拦截器。 它会对服务器进行网络请求
 */
public final class CallServerInterceptor implements Interceptor {
    private final boolean forWebSocket;

    public CallServerInterceptor(boolean forWebSocket) {
        this.forWebSocket = forWebSocket;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        //这些对象在前面的Interceptor都已经创建完毕
        RealInterceptorChain realChain = (RealInterceptorChain) chain;
        HttpCodec httpCodec = realChain.httpStream();
        StreamAllocation streamAllocation = realChain.streamAllocation();
        RealConnection connection = (RealConnection) realChain.connection();
        Request request = realChain.request();

        long sentRequestMillis = System.currentTimeMillis();
        //1. 写入请求头
        realChain.eventListener().requestHeadersStart(realChain.call());
        httpCodec.writeRequestHeaders(request);
        realChain.eventListener().requestHeadersEnd(realChain.call(), request);

        Response.Builder responseBuilder = null;
        if (HttpMethod.permitsRequestBody(request.method()) && request.body() != null) {
            // If there's a "Expect: 100-continue" header on the request, wait for a "HTTP/1.1 100
            // Continue" response before transmitting the request body. If we don't get that, return
            // what we did get (such as a 4xx response) without ever transmitting the request body.
            //如果请求中存在“Expect：100-continue”标头，请在发送请求主体之前等待“HTTP / 1.1 100 Continue”响应。
            // 如果我们没有得到，请返回我们得到的内容（例如4xx响应），而不发送请求主体。
            if ("100-continue".equalsIgnoreCase(request.header("Expect"))) {
                httpCodec.flushRequest();
                realChain.eventListener().responseHeadersStart(realChain.call());
                responseBuilder = httpCodec.readResponseHeaders(true);
            }
            //2 写入请求体
            if (responseBuilder == null) {
                // Write the request body if the "Expect: 100-continue" expectation was met.
                realChain.eventListener().requestBodyStart(realChain.call());
                long contentLength = request.body().contentLength();
                CountingSink requestBodyOut =
                        new CountingSink(httpCodec.createRequestBody(request, contentLength));
                BufferedSink bufferedRequestBody = Okio.buffer(requestBodyOut);

                request.body().writeTo(bufferedRequestBody);
                bufferedRequestBody.close();
                realChain.eventListener()
                        .requestBodyEnd(realChain.call(), requestBodyOut.successfulCount);
            } else if (!connection.isMultiplexed()) {
                // If the "Expect: 100-continue" expectation wasn't met, prevent the HTTP/1 connection
                // from being reused. Otherwise we're still obligated to transmit the request body to
                // leave the connection in a consistent state.
                streamAllocation.noNewStreams();
            }
        }

        httpCodec.finishRequest();

        if (responseBuilder == null) {
            realChain.eventListener().responseHeadersStart(realChain.call());
            responseBuilder = httpCodec.readResponseHeaders(false);
        }

        Response response = responseBuilder
                .request(request)
                .handshake(streamAllocation.connection().handshake())
                .sentRequestAtMillis(sentRequestMillis)
                .receivedResponseAtMillis(System.currentTimeMillis())
                .build();
        //4 读取响应体
        int code = response.code();
        if (code == 100) {
            // server sent a 100-continue even though we did not request one.
            // try again to read the actual response
            responseBuilder = httpCodec.readResponseHeaders(false);

            response = responseBuilder
                    .request(request)
                    .handshake(streamAllocation.connection().handshake())
                    .sentRequestAtMillis(sentRequestMillis)
                    .receivedResponseAtMillis(System.currentTimeMillis())
                    .build();

            code = response.code();
        }

        realChain.eventListener()
                .responseHeadersEnd(realChain.call(), response);

        if (forWebSocket && code == 101) {
            // Connection is upgrading, but we need to ensure interceptors see a non-null response body.
            response = response.newBuilder()
                    .body(Util.EMPTY_RESPONSE)
                    .build();
        } else {
            response = response.newBuilder()
                    .body(httpCodec.openResponseBody(response))
                    .build();
        }

        if ("close".equalsIgnoreCase(response.request().header("Connection"))
                || "close".equalsIgnoreCase(response.header("Connection"))) {
            streamAllocation.noNewStreams();
        }

        if ((code == 204 || code == 205) && response.body().contentLength() > 0) {
            throw new ProtocolException(
                    "HTTP " + code + " had non-zero Content-Length: " + response.body().contentLength());
        }

        return response;
    }

    static final class CountingSink extends ForwardingSink {
        long successfulCount;

        CountingSink(Sink delegate) {
            super(delegate);
        }

        @Override
        public void write(Buffer source, long byteCount) throws IOException {
            super.write(source, byteCount);
            successfulCount += byteCount;
        }
    }
}
