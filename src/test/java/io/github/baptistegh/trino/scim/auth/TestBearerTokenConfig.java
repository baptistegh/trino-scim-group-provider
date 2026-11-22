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

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;

final class TestBearerTokenConfig
{
    @Test
    void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(BearerTokenConfig.class)
                .setBearerToken(null));
    }

    @Test
    void testExplicitPropertyMappings()
    {
        assertFullMapping(
                ImmutableMap.of("scim.bearer-token", "token"),
                new BearerTokenConfig().setBearerToken("token"));
    }
}
