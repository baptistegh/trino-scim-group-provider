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

import com.google.common.net.MediaType;
import io.airlift.http.client.HttpStatus;
import io.airlift.http.client.Request;
import io.airlift.http.client.Response;
import io.airlift.http.client.testing.TestingHttpClient;
import io.github.baptistegh.trino.scim.auth.ScimAuthenticator;
import io.github.baptistegh.trino.scim.model.ScimGroupMembership;
import io.github.baptistegh.trino.scim.model.ScimUser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.airlift.http.client.testing.TestingResponse.mockResponse;
import static io.github.baptistegh.trino.scim.ScimClient.equalityFilter;
import static io.github.baptistegh.trino.scim.TestingRequests.header;
import static java.net.URLDecoder.decode;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestScimClient
{
    static final MediaType SCIM_JSON = MediaType.create("application", "scim+json");
    static final URI BASE_URL = URI.create("https://idp.example.com/realms/test/scim/v2");

    @Test
    void testEqualityFilter()
    {
        assertThat(equalityFilter("userName", "alice")).isEqualTo("userName eq \"alice\"");
        // Quotes and backslashes cannot end the string literal and change the filter
        assertThat(equalityFilter("userName", "x\" or userName pr or userName eq \"y"))
                .isEqualTo("userName eq \"x\\\" or userName pr or userName eq \\\"y\"");
        assertThat(equalityFilter("userName", "a\\b")).isEqualTo("userName eq \"a\\\\b\"");
        assertThat(equalityFilter("userName", "a\nb")).isEqualTo("userName eq \"a\\u000ab\"");
    }

    @Test
    void testFindUser()
    {
        List<Request> requests = new ArrayList<>();
        ScimClient client = client(request -> {
            requests.add(request);
            return scimResponse(
                    """
                    {
                      "schemas": ["urn:ietf:params:scim:api:messages:2.0:ListResponse"],
                      "totalResults": 1,
                      "Resources": [{
                        "id": "1234",
                        "userName": "alice",
                        "groups": [
                          {"value": "g1", "display": "data-engineers", "type": "direct", "$ref": "Groups/g1"},
                          {"value": "g2", "display": "engineering", "type": "indirect"}
                        ]
                      }]
                    }
                    """);
        });

        assertThat(client.findUser("alice")).contains(new ScimUser("1234", List.of(
                new ScimGroupMembership("g1", "data-engineers", "direct"),
                new ScimGroupMembership("g2", "engineering", "indirect"))));

        assertThat(requests).hasSize(1);
        Request request = requests.getFirst();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getUri().getPath()).isEqualTo("/realms/test/scim/v2/Users");
        assertThat(decode(request.getUri().getRawQuery(), UTF_8)).isEqualTo("filter=userName eq \"alice\"&attributes=groups");
        assertThat(header(request, "Authorization")).isEqualTo("Bearer test-token");
        assertThat(header(request, "Accept")).startsWith("application/scim+json");
    }

    @Test
    void testUserNotFound()
    {
        ScimClient client = client(_ -> scimResponse("{\"totalResults\": 0, \"Resources\": []}"));
        assertThat(client.findUser("nobody")).isEmpty();

        // Resources may be left out when nothing matched
        client = client(_ -> scimResponse("{\"totalResults\": 0}"));
        assertThat(client.findUser("nobody")).isEmpty();
    }

    @Test
    void testAmbiguousUser()
    {
        ScimClient client = client(_ -> scimResponse("{\"totalResults\": 2, \"Resources\": [{\"id\": \"1\"}, {\"id\": \"2\"}]}"));
        assertThatThrownBy(() -> client.findUser("alice"))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("returned 2 users where userName is 'alice'");
    }

    @Test
    void testReadsUserWhenSearchOmitsGroups()
    {
        List<Request> requests = new ArrayList<>();
        ScimClient client = client(request -> {
            requests.add(request);
            if (request.getUri().getPath().endsWith("/Users")) {
                return scimResponse("{\"totalResults\": 1, \"Resources\": [{\"id\": \"2819c223-7f76\"}]}");
            }
            return scimResponse("{\"id\": \"2819c223-7f76\", \"groups\": [{\"value\": \"g1\", \"display\": \"analysts\"}]}");
        });

        assertThat(client.findUser("bob")).contains(new ScimUser("2819c223-7f76", List.of(new ScimGroupMembership("g1", "analysts", null))));
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).getUri().getRawPath()).isEqualTo("/realms/test/scim/v2/Users/2819c223-7f76");
        assertThat(decode(requests.get(1).getUri().getRawQuery(), UTF_8)).isEqualTo("attributes=groups");
    }

    @Test
    void testRejectsIdThatIsNotAPathSegment()
    {
        for (String id : List.of("../../admin", "a/b", "..", "")) {
            ScimClient client = client(_ -> scimResponse("{\"totalResults\": 1, \"Resources\": [{\"id\": \"%s\"}]}".formatted(id)));
            assertThatThrownBy(() -> client.findUser("bob"))
                    .isInstanceOf(ScimException.class)
                    .hasMessageContaining("not a valid path segment");
        }
    }

    @Test
    void testRetriesOnceWithNewCredentialsAfterUnauthorized()
    {
        CountingAuthenticator authenticator = new CountingAuthenticator();
        AtomicInteger calls = new AtomicInteger();
        ScimClient client = client(authenticator, _ -> calls.incrementAndGet() == 1
                ? mockResponse(HttpStatus.UNAUTHORIZED, SCIM_JSON, "{}")
                : scimResponse("{\"totalResults\": 0}"));

        assertThat(client.findUser("alice")).isEmpty();
        assertThat(authenticator.invalidations).hasValue(1);
        assertThat(calls).hasValue(2);
    }

    @Test
    void testUnauthorizedAfterRetry()
    {
        CountingAuthenticator authenticator = new CountingAuthenticator();
        ScimClient client = client(authenticator, _ -> mockResponse(HttpStatus.UNAUTHORIZED, SCIM_JSON, "{\"detail\": \"Invalid token audience\"}"));

        assertThatThrownBy(() -> client.findUser("alice"))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("SCIM service rejected the credentials")
                .hasMessageContaining("Invalid token audience");
        assertThat(authenticator.invalidations).hasValue(1);
    }

    @Test
    void testErrorResponse()
    {
        ScimClient client = client(_ -> mockResponse(HttpStatus.FORBIDDEN, SCIM_JSON, "{\"detail\": \"insufficient permissions\"}"));
        assertThatThrownBy(() -> client.findUser("alice"))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("HTTP 403")
                .hasMessageContaining("insufficient permissions");
    }

    @Test
    void testInvalidResponse()
    {
        ScimClient client = client(_ -> scimResponse("<html>login page</html>"));
        assertThatThrownBy(() -> client.findUser("alice"))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("Invalid response");
    }

    @Test
    void testMetrics()
    {
        AtomicInteger calls = new AtomicInteger();
        ScimClient client = client(_ -> switch (calls.incrementAndGet()) {
            case 1 -> scimResponse("{\"totalResults\": 0}");
            // Retried once with a new token, then succeeds
            case 2 -> mockResponse(HttpStatus.UNAUTHORIZED, SCIM_JSON, "{}");
            case 3 -> scimResponse("{\"totalResults\": 0}");
            case 4 -> mockResponse(HttpStatus.SERVICE_UNAVAILABLE, SCIM_JSON, "{}");
            case 5 -> scimResponse("<html>login page</html>");
            default -> throw new UncheckedIOException(new IOException("Connection refused"));
        });

        assertThat(client.findUser("alice")).isEmpty();
        assertThat(client.findUser("alice")).isEmpty();
        assertThatThrownBy(() -> client.findUser("alice")).hasMessageContaining("HTTP 503");
        assertThatThrownBy(() -> client.findUser("alice")).hasMessageContaining("Invalid response");
        assertThatThrownBy(() -> client.findUser("alice")).hasMessageContaining("Connection refused");

        assertThat(client.getRequests().getTotalCount()).isEqualTo(6);
        assertThat(client.getRequestTime().getAllTime().getCount()).isEqualTo(6);
        assertThat(client.getCredentialRetries().getTotalCount()).isEqualTo(1);
        assertThat(client.getErrorResponses().getTotalCount()).isEqualTo(1);
        assertThat(client.getInvalidResponses().getTotalCount()).isEqualTo(1);
        assertThat(client.getConnectionFailures().getTotalCount()).isEqualTo(1);
    }

    @Test
    void testCustomFilterAttribute()
    {
        List<Request> requests = new ArrayList<>();
        ScimGroupProviderConfig config = new ScimGroupProviderConfig().setBaseUrl(BASE_URL).setUserFilterAttribute("emails.value");
        ScimClient client = new ScimClient(new TestingHttpClient(request -> {
            requests.add(request);
            return scimResponse("{\"totalResults\": 0}");
        }), new CountingAuthenticator(), config);

        assertThat(client.findUser("alice@example.com")).isEqualTo(Optional.empty());
        assertThat(decode(requests.getFirst().getUri().getRawQuery(), UTF_8)).startsWith("filter=emails.value eq \"alice@example.com\"");
    }

    static ScimClient client(Function<Request, Response> handler)
    {
        return client(new CountingAuthenticator(), handler);
    }

    static ScimClient client(ScimAuthenticator authenticator, Function<Request, Response> handler)
    {
        return new ScimClient(new TestingHttpClient(handler::apply), authenticator, new ScimGroupProviderConfig().setBaseUrl(BASE_URL));
    }

    static Response scimResponse(String json)
    {
        return mockResponse(HttpStatus.OK, SCIM_JSON, json);
    }

    static final class CountingAuthenticator
            implements ScimAuthenticator
    {
        final AtomicInteger invalidations = new AtomicInteger();

        @Override
        public String getAuthorizationHeader()
        {
            return "Bearer test-token";
        }

        @Override
        public void invalidate()
        {
            invalidations.incrementAndGet();
        }
    }
}
