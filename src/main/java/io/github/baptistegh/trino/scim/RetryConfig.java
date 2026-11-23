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

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;
import io.airlift.units.Duration;
import io.airlift.units.MinDuration;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class RetryConfig
{
    private int maxAttempts = 3;
    private Duration initialDelay = new Duration(200, MILLISECONDS);
    private Duration maxDelay = new Duration(2, SECONDS);
    private Duration maxDuration = new Duration(10, SECONDS);

    @Min(1)
    public int getMaxAttempts()
    {
        return maxAttempts;
    }

    @Config("scim.retry.max-attempts")
    @ConfigDescription("Maximum number of attempts of a request to the SCIM service or the token endpoint, including the first one; 1 disables retries")
    public RetryConfig setMaxAttempts(int maxAttempts)
    {
        this.maxAttempts = maxAttempts;
        return this;
    }

    @NotNull
    @MinDuration("1ms")
    public Duration getInitialDelay()
    {
        return initialDelay;
    }

    @Config("scim.retry.initial-delay")
    @ConfigDescription("Delay before the first retry; it doubles with each retry, with random jitter")
    public RetryConfig setInitialDelay(Duration initialDelay)
    {
        this.initialDelay = initialDelay;
        return this;
    }

    @NotNull
    @MinDuration("1ms")
    public Duration getMaxDelay()
    {
        return maxDelay;
    }

    @Config("scim.retry.max-delay")
    @ConfigDescription("Maximum delay between two attempts, unless the service asks for a longer one with Retry-After")
    public RetryConfig setMaxDelay(Duration maxDelay)
    {
        this.maxDelay = maxDelay;
        return this;
    }

    @NotNull
    @MinDuration("0s")
    public Duration getMaxDuration()
    {
        return maxDuration;
    }

    @Config("scim.retry.max-duration")
    @ConfigDescription("No retry starts later than this after the first attempt, so a request takes at most this long plus one request timeout")
    public RetryConfig setMaxDuration(Duration maxDuration)
    {
        this.maxDuration = maxDuration;
        return this;
    }

    @AssertTrue(message = "scim.retry.initial-delay must not be greater than scim.retry.max-delay")
    public boolean isInitialDelayValid()
    {
        return initialDelay == null || maxDelay == null || initialDelay.compareTo(maxDelay) <= 0;
    }
}
