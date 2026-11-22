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

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;

final class TestClientCredentialsConfig
{
    @Test
    void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(ClientCredentialsConfig.class)
                .setTokenUrl(null)
                .setClientId(null)
                .setClientSecret(null)
                .setScope(null));
    }

    @Test
    void testExplicitPropertyMappings()
    {
        Map<String, String> properties = ImmutableMap.<String, String>builder()
                .put("scim.oauth2.token-url", "https://idp.example.com/token")
                .put("scim.oauth2.client-id", "trino")
                .put("scim.oauth2.client-secret", "secret")
                .put("scim.oauth2.scope", "scim:read")
                .buildOrThrow();

        ClientCredentialsConfig expected = new ClientCredentialsConfig()
                .setTokenUrl(URI.create("https://idp.example.com/token"))
                .setClientId("trino")
                .setClientSecret("secret")
                .setScope("scim:read");

        assertFullMapping(properties, expected);
    }
}
