/*
 * Copyright (C) 2015 Square, Inc.
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

import java.util.Collections;
import java.util.List;

/**
 * Provides <strong>policy</strong> and <strong>persistence</strong> for HTTP cookies.
 * 为HTTP cookie提供策略和持久性。
 * <p>As policy, implementations of this interface are responsible for selecting which cookies to
 * accept and which to reject. A reasonable policy is to reject all cookies, though that may
 * interfere with session-based authentication schemes that require cookies.
 * 作为政策，这个接口的实现负责选择要接受的cookie和拒绝的cookie。
 * 合理的政策是拒绝所有的cookies，尽管这可能会影响需要cookie的基于会话的认证方案。
 * <p>
 * <p>
 * <p>As persistence, implementations of this interface must also provide storage of cookies. Simple
 * implementations may store cookies in memory; sophisticated ones may use the file system or
 * database to hold accepted cookies.
 * 作为持久性，此接口的实现还必须提供cookie的存储。
 * 简单的实现可以将cookie存储在内存中; 复杂的可以使用文件系统或数据库来保存接受的cookie。
 * <p>
 * The <ahref="https://tools.ietf.org/html/rfc6265#section-5.3">cookie storage model</a> specifies policies for updating and expiring cookies.
 */
public interface CookieJar {
    /**
     * A cookie jar that never accepts any cookies.
     * 从不接受任何cookie
     */
    CookieJar NO_COOKIES = new CookieJar() {
        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            return Collections.emptyList();
        }
    };

    /**
     * Saves {@code cookies} from an HTTP response to this store according to this jar's policy.
     * <p>
     * <p>Note that this method may be called a second time for a single HTTP response if the response
     * includes a trailer. For this obscure HTTP feature, {@code cookies} contains only the trailer's
     * cookies.
     */
    void saveFromResponse(HttpUrl url, List<Cookie> cookies);

    /**
     * Load cookies from the jar for an HTTP request to {@code url}. This method returns a possibly
     * empty list of cookies for the network request.
     * <p>
     * <p>Simple implementations will return the accepted cookies that have not yet expired and that
     * {@linkplain Cookie#matches match} {@code url}.
     */
    List<Cookie> loadForRequest(HttpUrl url);
}
