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
package io.github.baptistegh.trino.scim.auth;

import com.google.common.base.Ticker;
import io.airlift.http.client.HttpStatus;
import io.airlift.http.client.Request;
import io.airlift.http.client.Response;
import io.airlift.http.client.StaticBodyGenerator;
import io.airlift.http.client.testing.TestingHttpClient;
import io.github.baptistegh.trino.scim.ScimException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.net.MediaType.JSON_UTF_8;
import static io.airlift.http.client.testing.TestingResponse.mockResponse;
import static io.github.baptistegh.trino.scim.TestingRequests.header;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestClientCredentialsAuthenticator
{
    private static final URI TOKEN_URL = URI.create("https://idp.example.com/token");

    @Test
    void testRequestsTokenWithClientCredentials()
    {
        List<Request> requests = new ArrayList<>();
        TestingHttpClient httpClient = new TestingHttpClient(request -> {
            requests.add(request);
            return tokenResponse("token-1", 300);
        });

        ClientCredentialsAuthenticator authenticator = new ClientCredentialsAuthenticator(
                httpClient,
                config().setClientId("trino:groups").setScope("scim:read users"),
                new FakeTicker());

        assertThat(authenticator.getAuthorizationHeader()).isEqualTo("Bearer token-1");

        assertThat(requests).hasSize(1);
        Request request = requests.getFirst();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getUri()).isEqualTo(TOKEN_URL);
        assertThat(header(request, "Content-Type")).isEqualTo("application/x-www-form-urlencoded");
        // Client id and secret are form-encoded before Basic encoding
        assertThat(header(request, "Authorization"))
                .isEqualTo("Basic " + Base64.getEncoder().encodeToString("trino%3Agroups:s%26cret".getBytes(UTF_8)));
        assertThat(new String(((StaticBodyGenerator) request.getBodyGenerator()).getBody(), UTF_8))
                .isEqualTo("grant_type=client_credentials&scope=scim%3Aread+users");
    }

    @Test
    void testReusesTokenUntilShortlyBeforeExpiry()
    {
        AtomicInteger issued = new AtomicInteger();
        TestingHttpClient httpClient = new TestingHttpClient(_ -> tokenResponse("token-" + issued.incrementAndGet(), 300));
        FakeTicker ticker = new FakeTicker();
        ClientCredentialsAuthenticator authenticator = new ClientCredentialsAuthenticator(httpClient, config(), ticker);

        assertThat(authenticator.getAuthorizationHeader()).isEqualTo("Bearer token-1");
        ticker.advance(269);
        assertThat(authenticator.getAuthorizationHeader()).isEqualTo("Bearer token-1");
        // Refreshed 30 seconds before the 300 second lifetime ends
        ticker.advance(1);
        assertThat(authenticator.getAuthorizationHeader()).isEqualTo("Bearer token-2");
        assertThat(issued).hasValue(2);
    }

    @Test
    void testInvalidate()
    {
        AtomicInteger issued = new AtomicInteger();
        TestingHttpClient httpClient = new TestingHttpClient(_ -> tokenResponse("token-" + issued.incrementAndGet(), 300));
        ClientCredentialsAuthenticator authenticator = new ClientCredentialsAuthenticator(httpClient, config(), new FakeTicker());

        assertThat(authenticator.getAuthorizationHeader()).isEqualTo("Bearer token-1");
        authenticator.invalidate();
        assertThat(authenticator.getAuthorizationHeader()).isEqualTo("Bearer token-2");
    }

    @Test
    void testTokenEndpointError()
    {
        TestingHttpClient httpClient = new TestingHttpClient(_ -> mockResponse(HttpStatus.UNAUTHORIZED, JSON_UTF_8, "{\"error\":\"invalid_client\"}"));
        ClientCredentialsAuthenticator authenticator = new ClientCredentialsAuthenticator(httpClient, config(), new FakeTicker());

        assertThatThrownBy(authenticator::getAuthorizationHeader)
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageContaining("invalid_client");
    }

    @Test
    void testMetrics()
    {
        AtomicInteger calls = new AtomicInteger();
        TestingHttpClient httpClient = new TestingHttpClient(_ -> calls.incrementAndGet() == 1
                ? mockResponse(HttpStatus.SERVICE_UNAVAILABLE, JSON_UTF_8, "{}")
                : tokenResponse("token", 300));
        ClientCredentialsAuthenticator authenticator = new ClientCredentialsAuthenticator(httpClient, config(), new FakeTicker());

        assertThatThrownBy(authenticator::getAuthorizationHeader).isInstanceOf(ScimException.class);
        assertThat(authenticator.getAuthorizationHeader()).isEqualTo("Bearer token");
        // Served from the cached token
        assertThat(authenticator.getAuthorizationHeader()).isEqualTo("Bearer token");

        assertThat(authenticator.getTokenRequests().getTotalCount()).isEqualTo(2);
        assertThat(authenticator.getTokenFailures().getTotalCount()).isEqualTo(1);
        assertThat(authenticator.getTokenRequestTime().getAllTime().getCount()).isEqualTo(2);
    }

    @Test
    void testRejectsNonBearerToken()
    {
        TestingHttpClient httpClient = new TestingHttpClient(_ -> mockResponse(HttpStatus.OK, JSON_UTF_8, "{\"access_token\":\"x\",\"token_type\":\"mac\"}"));
        ClientCredentialsAuthenticator authenticator = new ClientCredentialsAuthenticator(httpClient, config(), new FakeTicker());

        assertThatThrownBy(authenticator::getAuthorizationHeader)
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("Unsupported token type 'mac'");
    }

    private static ClientCredentialsConfig config()
    {
        return new ClientCredentialsConfig()
                .setTokenUrl(TOKEN_URL)
                .setClientId("trino")
                .setClientSecret("s&cret");
    }

    private static Response tokenResponse(String token, long expiresIn)
    {
        return mockResponse(HttpStatus.OK, JSON_UTF_8, "{\"access_token\":\"%s\",\"token_type\":\"Bearer\",\"expires_in\":%s}".formatted(token, expiresIn));
    }

    private static final class FakeTicker
            extends Ticker
    {
        private long nanos;

        @Override
        public long read()
        {
            return nanos;
        }

        void advance(long seconds)
        {
            nanos += SECONDS.toNanos(seconds);
        }
    }
}
