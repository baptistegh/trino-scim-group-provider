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
import io.airlift.units.Duration;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;
import static io.airlift.testing.ValidationAssertions.assertFailsValidation;
import static io.github.baptistegh.trino.scim.ScimGroupProviderConfig.AuthenticationType.BEARER_TOKEN;
import static io.github.baptistegh.trino.scim.ScimGroupProviderConfig.AuthenticationType.OAUTH2_CLIENT_CREDENTIALS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

final class TestScimGroupProviderConfig
{
    @Test
    void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(ScimGroupProviderConfig.class)
                .setBaseUrl(null)
                .setAuthenticationType(OAUTH2_CLIENT_CREDENTIALS)
                .setUserFilterAttribute("userName")
                .setIncludeIndirectGroups(true)
                .setCacheTtl(new Duration(5, MINUTES))
                .setCacheMaximumSize(10_000));
    }

    @Test
    void testExplicitPropertyMappings()
    {
        Map<String, String> properties = ImmutableMap.<String, String>builder()
                .put("scim.base-url", "https://idp.example.com/scim/v2")
                .put("scim.authentication.type", "BEARER_TOKEN")
                .put("scim.user-filter-attribute", "emails.value")
                .put("scim.include-indirect-groups", "false")
                .put("scim.cache.ttl", "30s")
                .put("scim.cache.maximum-size", "42")
                .buildOrThrow();

        ScimGroupProviderConfig expected = new ScimGroupProviderConfig()
                .setBaseUrl(URI.create("https://idp.example.com/scim/v2"))
                .setAuthenticationType(BEARER_TOKEN)
                .setUserFilterAttribute("emails.value")
                .setIncludeIndirectGroups(false)
                .setCacheTtl(new Duration(30, SECONDS))
                .setCacheMaximumSize(42);

        assertFullMapping(properties, expected);
    }

    @Test
    void testValidation()
    {
        assertFailsValidation(new ScimGroupProviderConfig(), "baseUrl", "must not be null", NotNull.class);

        assertFailsValidation(
                validConfig().setUserFilterAttribute("userName eq \"x\" or userName"),
                "userFilterAttribute",
                "must be a SCIM attribute path",
                Pattern.class);
    }

    private static ScimGroupProviderConfig validConfig()
    {
        return new ScimGroupProviderConfig().setBaseUrl(URI.create("https://idp.example.com/scim/v2"));
    }
}
