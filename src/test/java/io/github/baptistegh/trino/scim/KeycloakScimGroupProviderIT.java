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
import io.trino.spi.TrinoException;
import io.trino.spi.security.GroupProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.Network;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

/**
 * Runs the group provider against Keycloak's SCIM API.
 */
@TestInstance(PER_CLASS)
final class KeycloakScimGroupProviderIT
{
    private Network network;
    private TestingKeycloak keycloak;

    @BeforeAll
    void setUp()
    {
        network = Network.newNetwork();
        keycloak = new TestingKeycloak(network);
        keycloak.start();
    }

    @AfterAll
    void tearDown()
    {
        if (keycloak != null) {
            keycloak.close();
        }
        if (network != null) {
            network.close();
        }
    }

    @Test
    void testGroupsIncludingInheritedOnes()
    {
        GroupProvider provider = provider(ImmutableMap.of());

        // alice is a member of /engineering/data-engineers, so engineering is an indirect membership
        assertThat(provider.getGroups("alice")).containsExactlyInAnyOrder("data-engineers", "engineering");
        assertThat(provider.getGroups("bob")).containsExactly("analysts");
        assertThat(provider.getGroups("carol")).isEmpty();
        assertThat(provider.getGroups("nobody")).isEmpty();
    }

    @Test
    void testDirectGroupsOnly()
    {
        GroupProvider provider = provider(ImmutableMap.of("scim.include-indirect-groups", "false"));

        assertThat(provider.getGroups("alice")).containsExactly("data-engineers");
    }

    @Test
    void testFilterOnEmail()
    {
        GroupProvider provider = provider(ImmutableMap.of("scim.user-filter-attribute", "emails.value"));

        assertThat(provider.getGroups("bob@example.com")).containsExactly("analysts");
        assertThat(provider.getGroups("bob")).isEmpty();
    }

    @Test
    void testUserNameIsEscapedInFilter()
    {
        GroupProvider provider = provider(ImmutableMap.of());

        // Unescaped, this filter would match every user and fail the lookup as ambiguous
        assertThat(provider.getGroups("x\" or userName pr or userName eq \"y")).isEmpty();
    }

    @Test
    void testInvalidClientSecret()
    {
        GroupProvider provider = provider(ImmutableMap.of("scim.oauth2.client-secret", "wrong"));

        assertThatThrownBy(() -> provider.getGroups("alice"))
                .isInstanceOf(TrinoException.class)
                .hasMessageContaining("Failed to obtain an access token");
    }

    @Test
    void testTokenWithoutScimAudience()
    {
        // Keycloak answers 401 without a WWW-Authenticate header, which the HTTP client rejects before reading
        GroupProvider provider = provider(ImmutableMap.of("scim.oauth2.client-id", "trino-scim-no-audience"));

        assertThatThrownBy(() -> provider.getGroups("alice"))
                .isInstanceOf(TrinoException.class)
                .hasMessageContaining("SCIM service rejected the credentials")
                .hasMessageContaining("HTTP 401");
    }

    private GroupProvider provider(Map<String, String> overrides)
    {
        Map<String, String> config = new HashMap<>(ImmutableMap.of(
                "scim.base-url", keycloak.scimUrl(),
                "scim.oauth2.token-url", keycloak.tokenUrl(),
                "scim.oauth2.client-id", TestingKeycloak.SCIM_CLIENT_ID,
                "scim.oauth2.client-secret", TestingKeycloak.SCIM_CLIENT_SECRET,
                "scim.cache.ttl", "0s"));
        config.putAll(overrides);
        return new ScimGroupProviderFactory().create(config);
    }
}
