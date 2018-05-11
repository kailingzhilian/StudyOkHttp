/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3;

import java.io.IOException;

/**
 * A call is a request that has been prepared for execution. A call can be canceled. As this object
 * represents a single request/response pair (stream), it cannot be executed twice.
 * 会话
 * call是已经准备好执行的请求。 call可以取消。 由于此对象表示单个请求/响应对（流），因此无法执行两次。
 */
public interface Call extends Cloneable {
    /**
     * Returns the original request that initiated this call.
     * 返回当前请求
     */
    Request request();

    /**
     * Invokes the request immediately, and blocks until the response can be processed or is in
     * error.
     * 立即调用请求，并阻塞，直到响应可以被处理或出错。
     * <p>
     * <p>To avoid leaking resources callers should close the {@link Response} which in turn will
     * close the underlying {@link ResponseBody}.
     * 避免泄漏资源呼叫者应该关闭{@link Response}，然后关闭基础的{@link ResponseBody}
     * <p>
     * <pre>@{code
     *
     *   // ensure the response (and underlying response body) is closed
     *   try (Response response = client.newCall(request).execute()) {
     *     ...
     *   }
     *
     * }</pre>
     * <p>
     * <p>The caller may read the response body with the response's {@link Response#body} method. To
     * avoid leaking resources callers must {@linkplain ResponseBody close the response body} or the
     * Response.
     * 调用者可以用响应的{@link Response＃body}方法读取响应主体。
     * 为了避免资源泄漏，调用者必须{ResponseBody关闭响应主体}或响应。
     * <p>
     * <p>Note that transport-layer success (receiving a HTTP response code, headers and body) does
     * not necessarily indicate application-layer success: {@code response} may still indicate an
     * unhappy HTTP response code like 404 or 500.
     * 请注意，传输层成功（接收HTTP响应代码，标题和正文）并不一定表示应用程序层成功：
     * {@code response}仍可能指示不愉快的HTTP响应代码，如404或500
     *
     * @throws IOException           if the request could not be executed due to cancellation, a connectivity
     *                               problem or timeout. Because networks can fail during an exchange, it is possible that the
     *                               remote server accepted the request before the failure.
     *                               如果由于取消，连接问题或超时而无法执行请求。 由于网络在交换过程中可能会失败，因此远程服务器可能在失败之前接受请求。
     * @throws IllegalStateException when the call has already been executed.
     *                               当呼叫已经被执行时
     */
    Response execute() throws IOException;

    /**
     * Schedules the request to be executed at some point in the future.
     * 安排在将来某个时候执行的请求
     * <p>
     * <p>The {@link OkHttpClient#dispatcher dispatcher} defines when the request will run: usually
     * immediately unless there are several other requests currently being executed.
     * OkHttpClient＃调度程序调度程序定义请求的运行时间：通常是立即执行，除非当前正在执行多个其他请求。
     * <p>
     * <p>This client will later call back {@code responseCallback} with either an HTTP response or a
     * failure exception.
     * 此客户端稍后会用HTTP响应或失败异常回调{@code responseCallback
     *
     * @throws IllegalStateException when the call has already been executed.
     */
    void enqueue(Callback responseCallback);

    /**
     * Cancels the request, if possible. Requests that are already complete cannot be canceled.
     * 如果可能，取消请求。 已完成的请求无法取消。
     */
    void cancel();

    /**
     * Returns true if this call has been either {@linkplain #execute() executed} or {@linkplain
     * #enqueue(Callback) enqueued}. It is an error to execute a call more than once.
     * 如果此调用已执行{@linkplain #execute（）}或{@linkplain #enqueue（Callback）入队}，则返回true。 多次执行call是错误的。
     */
    boolean isExecuted();

    boolean isCanceled();

    /**
     * Create a new, identical call to this one which can be enqueued or executed even if this call
     * has already been.
     * 创建一个新的，相同的呼叫，这个呼叫可以被排队或执行，即使这个呼叫已经结束。
     */
    Call clone();

    interface Factory {
        Call newCall(Request request);
    }
}
