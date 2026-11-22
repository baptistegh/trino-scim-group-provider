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

import com.google.common.collect.ImmutableMap;
import io.airlift.http.client.HttpStatus;
import io.airlift.http.client.Request;
import io.airlift.http.client.Response;
import io.airlift.units.Duration;
import io.trino.spi.TrinoException;
import io.trino.spi.security.GroupProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.airlift.http.client.testing.TestingResponse.mockResponse;
import static io.github.baptistegh.trino.scim.ScimErrorCode.SCIM_GROUP_LOOKUP_FAILED;
import static io.github.baptistegh.trino.scim.TestScimClient.BASE_URL;
import static io.github.baptistegh.trino.scim.TestScimClient.SCIM_JSON;
import static io.github.baptistegh.trino.scim.TestScimClient.client;
import static io.github.baptistegh.trino.scim.TestScimClient.scimResponse;
import static java.net.URLDecoder.decode;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestScimGroupProvider
{
    private static final Map<String, String> USERS = ImmutableMap.of(
            "alice",
            """
            {"id": "1", "groups": [
              {"value": "g1", "display": "data-engineers", "type": "direct"},
              {"value": "g2", "display": "engineering", "type": "indirect"}
            ]}""",
            "bob",
            """
            {"id": "2", "groups": [{"value": "g3", "display": "analysts"}]}""",
            "carol",
            """
            {"id": "3", "groups": []}""",
            "dave",
            """
            {"id": "4", "groups": [{"value": "g4"}]}""");

    @Test
    void testGetGroups()
    {
        GroupProvider provider = provider(new ScimGroupProviderConfig(), fakeService(new AtomicInteger()));

        assertThat(provider.getGroups("alice")).containsExactlyInAnyOrder("data-engineers", "engineering");
        // A membership without a type is treated as direct
        assertThat(provider.getGroups("bob")).containsExactly("analysts");
        assertThat(provider.getGroups("carol")).isEmpty();
        // Memberships without a display name are skipped
        assertThat(provider.getGroups("dave")).isEmpty();
        assertThat(provider.getGroups("unknown")).isEmpty();
    }

    @Test
    void testExcludeIndirectGroups()
    {
        GroupProvider provider = provider(new ScimGroupProviderConfig().setIncludeIndirectGroups(false), fakeService(new AtomicInteger()));

        assertThat(provider.getGroups("alice")).containsExactly("data-engineers");
        assertThat(provider.getGroups("bob")).containsExactly("analysts");
    }

    @Test
    void testCaching()
    {
        AtomicInteger requests = new AtomicInteger();
        GroupProvider provider = provider(new ScimGroupProviderConfig(), fakeService(requests));

        provider.getGroups("alice");
        provider.getGroups("alice");
        provider.getGroups("unknown");
        provider.getGroups("unknown");
        assertThat(requests).hasValue(2);
    }

    @Test
    void testCachingDisabled()
    {
        AtomicInteger requests = new AtomicInteger();
        GroupProvider provider = provider(new ScimGroupProviderConfig().setCacheTtl(new Duration(0, SECONDS)), fakeService(requests));

        provider.getGroups("alice");
        provider.getGroups("alice");
        assertThat(requests).hasValue(2);
    }

    @Test
    void testFailureIsReportedAndNotCached()
    {
        AtomicBoolean failing = new AtomicBoolean(true);
        AtomicInteger requests = new AtomicInteger();
        Function<Request, Response> service = fakeService(requests);
        GroupProvider provider = provider(new ScimGroupProviderConfig(), request -> failing.get()
                ? mockResponse(HttpStatus.SERVICE_UNAVAILABLE, SCIM_JSON, "{}")
                : service.apply(request));

        assertThatThrownBy(() -> provider.getGroups("alice"))
                .isInstanceOfSatisfying(TrinoException.class, e -> assertThat(e.getErrorCode()).isEqualTo(SCIM_GROUP_LOOKUP_FAILED.toErrorCode()))
                .hasMessageContaining("Failed to resolve groups of user 'alice'")
                .hasMessageContaining("HTTP 503");

        failing.set(false);
        assertThat(provider.getGroups("alice")).containsExactlyInAnyOrder("data-engineers", "engineering");
    }

    private static GroupProvider provider(ScimGroupProviderConfig config, Function<Request, Response> handler)
    {
        return new ScimGroupProvider(client(handler), config.setBaseUrl(BASE_URL));
    }

    private static Function<Request, Response> fakeService(AtomicInteger requests)
    {
        return request -> {
            requests.incrementAndGet();
            String query = decode(request.getUri().getRawQuery(), UTF_8);
            String user = query.replaceFirst("^filter=userName eq \"([^\"]*)\".*$", "$1");
            String resource = USERS.get(user);
            if (resource == null) {
                return scimResponse("{\"totalResults\": 0, \"Resources\": []}");
            }
            return scimResponse("{\"totalResults\": 1, \"Resources\": [" + resource + "]}");
        };
    }
}
