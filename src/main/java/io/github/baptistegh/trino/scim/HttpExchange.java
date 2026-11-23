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

import io.airlift.http.client.HttpClient;
import io.airlift.http.client.Request;
import io.airlift.http.client.StringResponseHandler.StringResponse;
import org.eclipse.jetty.client.HttpResponseException;

import java.util.Optional;

import static io.airlift.http.client.StringResponseHandler.createStringResponseHandler;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public final class HttpExchange
{
    private HttpExchange() {}

    /**
     * @param body empty when the HTTP client rejected the response before reading it
     * @param retryAfter value of the {@code Retry-After} header
     */
    public record Result(int statusCode, String body, Optional<String> retryAfter)
    {
        public Result
        {
            requireNonNull(body, "body is null");
            requireNonNull(retryAfter, "retryAfter is null");
        }
    }

    /**
     * Newer Airlift versions deprecate {@code setHeader(String, String)} in favor of an overload taking
     * {@code HeaderName}, which does not exist in the oldest supported Trino version.
     */
    @SuppressWarnings("deprecation")
    public static Request.Builder setHeader(Request.Builder builder, String name, String value)
    {
        return builder.setHeader(name, value);
    }

    /**
     * Sends the request and returns the status and body of any response, including error responses.
     *
     * @throws ScimException when no response was received
     */
    public static Result execute(HttpClient httpClient, Request request)
    {
        try {
            StringResponse response = httpClient.execute(request, createStringResponseHandler());
            return new Result(response.getStatusCode(), response.getBody() == null ? "" : response.getBody(), Optional.ofNullable(header(response, "Retry-After")));
        }
        catch (RuntimeException e) {
            // Jetty fails a 401 that has no WWW-Authenticate header ("Authentication challenge without
            // WWW-Authenticate header") before the response handler runs. Keycloak's SCIM API, among others,
            // responds like that, so recover the status to report it like any other error response.
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpResponseException responseException && responseException.getResponse() != null) {
                    return new Result(responseException.getResponse().getStatus(), "", Optional.empty());
                }
            }
            throw new ScimException(format("Request %s %s failed: %s", request.getMethod(), request.getUri(), e.getMessage()), e);
        }
    }

    /**
     * Newer Airlift versions deprecate {@code getHeader(String)} in favor of an overload taking
     * {@code HeaderName}, which does not exist in the oldest supported Trino version.
     */
    @SuppressWarnings("deprecation")
    private static String header(StringResponse response, String name)
    {
        return response.getHeader(name);
    }
}
