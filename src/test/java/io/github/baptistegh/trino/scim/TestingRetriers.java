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

import com.google.common.base.Ticker;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static java.time.ZoneOffset.UTC;

public final class TestingRetriers
{
    public static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    private TestingRetriers() {}

    public static HttpRetrier noRetries()
    {
        return new HttpRetrier(new RetryConfig().setMaxAttempts(1));
    }

    /**
     * A retrier that does not sleep: it records each delay in {@code sleeps} and moves its clock forward
     * by that much. The jitter is always half of its range.
     */
    public static HttpRetrier retrier(RetryConfig config, List<Long> sleeps)
    {
        AtomicLong nanos = new AtomicLong();
        Ticker ticker = new Ticker()
        {
            @Override
            public long read()
            {
                return nanos.get();
            }
        };
        return new HttpRetrier(config, ticker, Clock.fixed(NOW, UTC), delay -> {
            sleeps.add(delay);
            nanos.addAndGet(delay);
        }, () -> 0.5);
    }
}
