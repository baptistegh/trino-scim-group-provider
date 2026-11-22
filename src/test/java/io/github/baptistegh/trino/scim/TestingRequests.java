/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.baptistegh.trino.scim;

import io.airlift.http.client.Request;

public final class TestingRequests
{
    private TestingRequests() {}

    /**
     * Newer Airlift versions deprecate {@link Request#getHeader(String)} in favor of an overload that
     * does not exist in the oldest supported Trino version, so tests read headers through this method.
     */
    @SuppressWarnings("deprecation")
    public static String header(Request request, String name)
    {
        return request.getHeader(name);
    }
}
