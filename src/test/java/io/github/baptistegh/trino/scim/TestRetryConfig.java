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
import jakarta.validation.constraints.AssertTrue;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;
import static io.airlift.testing.ValidationAssertions.assertFailsValidation;
import static io.airlift.testing.ValidationAssertions.assertValidates;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

final class TestRetryConfig
{
    @Test
    void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(RetryConfig.class)
                .setMaxAttempts(3)
                .setInitialDelay(new Duration(200, MILLISECONDS))
                .setMaxDelay(new Duration(2, SECONDS))
                .setMaxDuration(new Duration(10, SECONDS)));
    }

    @Test
    void testExplicitPropertyMappings()
    {
        Map<String, String> properties = ImmutableMap.<String, String>builder()
                .put("scim.retry.max-attempts", "5")
                .put("scim.retry.initial-delay", "50ms")
                .put("scim.retry.max-delay", "1s")
                .put("scim.retry.max-duration", "3s")
                .buildOrThrow();

        RetryConfig expected = new RetryConfig()
                .setMaxAttempts(5)
                .setInitialDelay(new Duration(50, MILLISECONDS))
                .setMaxDelay(new Duration(1, SECONDS))
                .setMaxDuration(new Duration(3, SECONDS));

        assertFullMapping(properties, expected);
    }

    @Test
    void testValidation()
    {
        assertValidates(new RetryConfig());
        assertValidates(new RetryConfig().setMaxAttempts(1));
        assertFailsValidation(
                new RetryConfig().setInitialDelay(new Duration(5, SECONDS)),
                "initialDelayValid",
                "scim.retry.initial-delay must not be greater than scim.retry.max-delay",
                AssertTrue.class);
    }
}
