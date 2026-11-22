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
import io.trino.spi.security.GroupProviderFactory;
import org.junit.jupiter.api.Test;

import static com.google.common.collect.Iterables.getOnlyElement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestScimGroupProviderPlugin
{
    private final GroupProviderFactory factory = getOnlyElement(new ScimGroupProviderPlugin().getGroupProviderFactories());

    @Test
    void testCreateWithClientCredentials()
    {
        assertThat(factory.getName()).isEqualTo("scim");
        assertThat(factory.create(ImmutableMap.of(
                "scim.base-url", "https://idp.example.com/scim/v2",
                "scim.oauth2.token-url", "https://idp.example.com/token",
                "scim.oauth2.client-id", "trino",
                "scim.oauth2.client-secret", "secret",
                "scim.http-client.request-timeout", "5s")))
                .isInstanceOf(ScimGroupProvider.class);
    }

    @Test
    void testCreateWithBearerToken()
    {
        assertThat(factory.create(ImmutableMap.of(
                "scim.base-url", "https://idp.example.com/scim/v2",
                "scim.authentication.type", "BEARER_TOKEN",
                "scim.bearer-token", "token")))
                .isInstanceOf(ScimGroupProvider.class);
    }

    @Test
    void testRejectsIncompleteConfiguration()
    {
        assertThatThrownBy(() -> factory.create(ImmutableMap.of("scim.base-url", "https://idp.example.com/scim/v2")))
                .hasMessageContaining("scim.oauth2.token-url")
                .hasMessageContaining("scim.oauth2.client-id");

        // Properties of the authentication type that is not selected are rejected
        assertThatThrownBy(() -> factory.create(ImmutableMap.of(
                "scim.base-url", "https://idp.example.com/scim/v2",
                "scim.authentication.type", "BEARER_TOKEN",
                "scim.bearer-token", "token",
                "scim.oauth2.client-id", "trino")))
                .hasMessageContaining("Configuration property 'scim.oauth2.client-id' was not used");
    }
}
