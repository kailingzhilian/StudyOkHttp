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
package okhttp3.internal.http;

import java.io.IOException;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.internal.Version;
import okio.GzipSource;
import okio.Okio;

import static okhttp3.internal.Util.hostHeader;

/**
 * Bridges from application code to network code. First it builds a network request from a user
 * request. Then it proceeds to call the network. Finally it builds a user response from the network
 * response.
 * <p>
 * 从应用程序代码到网络代码的桥梁。
 * 首先它从用户请求建立一个网络请求。
 * 然后继续呼叫网络。
 * 最后，它从网络响应中建立用户响应。
 */
public final class BridgeInterceptor implements Interceptor {
    private final CookieJar cookieJar;

    public BridgeInterceptor(CookieJar cookieJar) {
        this.cookieJar = cookieJar;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request userRequest = chain.request();
        Request.Builder requestBuilder = userRequest.newBuilder();

        RequestBody body = userRequest.body();
        if (body != null) {
            //1 进行Header的包装
            MediaType contentType = body.contentType();
            if (contentType != null) {
                requestBuilder.header("Content-Type", contentType.toString());
            }

            long contentLength = body.contentLength();
            if (contentLength != -1) {
                requestBuilder.header("Content-Length", Long.toString(contentLength));
                requestBuilder.removeHeader("Transfer-Encoding");
            } else {
                requestBuilder.header("Transfer-Encoding", "chunked");
                requestBuilder.removeHeader("Content-Length");
            }
        }

        if (userRequest.header("Host") == null) {
            requestBuilder.header("Host", hostHeader(userRequest.url(), false));
        }

        if (userRequest.header("Connection") == null) {
            requestBuilder.header("Connection", "Keep-Alive");
        }

        // If we add an "Accept-Encoding: gzip" header field we're responsible for also decompressing
        // the transfer stream.
        //如果我们添加一个“Accept-Encoding：gzip”头字段，我们也负责解压缩传输流。
        boolean transparentGzip = false;
        if (userRequest.header("Accept-Encoding") == null && userRequest.header("Range") == null) {
            transparentGzip = true;
            requestBuilder.header("Accept-Encoding", "gzip");
        }
        //创建OkhttpClient配置的cookieJar
        List<Cookie> cookies = cookieJar.loadForRequest(userRequest.url());
        if (!cookies.isEmpty()) {
            requestBuilder.header("Cookie", cookieHeader(cookies));
        }

        if (userRequest.header("User-Agent") == null) {
            requestBuilder.header("User-Agent", Version.userAgent());
        }
        //拼接完后执行下一个
        Response networkResponse = chain.proceed(requestBuilder.build());
        //解析服务器返回的Header，如果没有这个cookie，则不进行解析
        HttpHeaders.receiveHeaders(cookieJar, userRequest.url(), networkResponse.headers());

        Response.Builder responseBuilder = networkResponse.newBuilder()
                .request(userRequest);
        ////判断服务器是否支持gzip压缩，如果支持，则将压缩提交给Okio库来处理
        if (transparentGzip
                && "gzip".equalsIgnoreCase(networkResponse.header("Content-Encoding"))
                && HttpHeaders.hasBody(networkResponse)) {
            GzipSource responseBody = new GzipSource(networkResponse.body().source());
            Headers strippedHeaders = networkResponse.headers().newBuilder()
                    .removeAll("Content-Encoding")
                    .removeAll("Content-Length")
                    .build();
            responseBuilder.headers(strippedHeaders);
            String contentType = networkResponse.header("Content-Type");
            responseBuilder.body(new RealResponseBody(contentType, -1L, Okio.buffer(responseBody)));
        }

        return responseBuilder.build();
    }

    /**
     * Returns a 'Cookie' HTTP request header with all cookies, like {@code a=b; c=d}.
     */
    private String cookieHeader(List<Cookie> cookies) {
        StringBuilder cookieHeader = new StringBuilder();
        for (int i = 0, size = cookies.size(); i < size; i++) {
            if (i > 0) {
                cookieHeader.append("; ");
            }
            Cookie cookie = cookies.get(i);
            cookieHeader.append(cookie.name()).append('=').append(cookie.value());
        }
        return cookieHeader.toString();
    }
}
