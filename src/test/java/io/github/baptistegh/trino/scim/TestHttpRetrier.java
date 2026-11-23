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
import io.airlift.units.Duration;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static io.github.baptistegh.trino.scim.TestingRetriers.NOW;
import static io.github.baptistegh.trino.scim.TestingRetriers.retrier;
import static java.time.ZoneOffset.UTC;
import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestHttpRetrier
{
    private final List<Long> sleeps = new ArrayList<>();
    private final AtomicInteger retries = new AtomicInteger();

    @Test
    void testDoesNotRetryOtherResponses()
    {
        for (int status : List.of(200, 400, 401, 403, 404, 500)) {
            AtomicInteger attempts = new AtomicInteger();
            HttpExchange.Result result = execute(new RetryConfig(), () -> {
                attempts.incrementAndGet();
                return response(status);
            });
            assertThat(result.statusCode()).isEqualTo(status);
            assertThat(attempts).hasValue(1);
        }
        assertThat(sleeps).isEmpty();
        assertThat(retries).hasValue(0);
    }

    @Test
    void testRetriesUntilSuccess()
    {
        for (int status : List.of(429, 502, 503, 504)) {
            sleeps.clear();
            AtomicInteger attempts = new AtomicInteger();
            HttpExchange.Result result = execute(new RetryConfig(), () -> attempts.incrementAndGet() < 3 ? response(status) : response(200));
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(attempts).hasValue(3);
            // 200ms then 400ms, each half fixed and half jitter
            assertThat(sleeps).containsExactly(millis(150), millis(300));
        }
    }

    @Test
    void testReturnsLastResponseWhenAttemptsAreExhausted()
    {
        AtomicInteger attempts = new AtomicInteger();
        HttpExchange.Result result = execute(new RetryConfig().setMaxAttempts(4), () -> {
            attempts.incrementAndGet();
            return response(503);
        });
        assertThat(result.statusCode()).isEqualTo(503);
        assertThat(attempts).hasValue(4);
        assertThat(retries).hasValue(3);
        assertThat(sleeps).containsExactly(millis(150), millis(300), millis(600));
    }

    @Test
    void testDelayIsCappedByMaxDelay()
    {
        execute(new RetryConfig().setMaxAttempts(5).setInitialDelay(new Duration(1, SECONDS)).setMaxDelay(new Duration(2, SECONDS)).setMaxDuration(new Duration(1, MINUTES)), () -> response(503));
        assertThat(sleeps).containsExactly(millis(750), millis(1500), millis(1500), millis(1500));
    }

    @Test
    void testRetriesWhenNoResponseIsReceived()
    {
        AtomicInteger attempts = new AtomicInteger();
        HttpExchange.Result result = execute(new RetryConfig(), () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new ScimException("Connection refused");
            }
            return response(200);
        });
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(retries).hasValue(1);

        assertThatThrownBy(() -> execute(new RetryConfig(), () -> {
            throw new ScimException("Connection refused");
        }))
                .isInstanceOf(ScimException.class)
                .hasMessage("Connection refused");
    }

    @Test
    void testUsesRetryAfter()
    {
        AtomicInteger attempts = new AtomicInteger();
        execute(new RetryConfig().setMaxAttempts(4), () -> switch (attempts.incrementAndGet()) {
            case 1 -> response(429, "2");
            case 2 -> response(503, RFC_1123_DATE_TIME.format(NOW.plusSeconds(3).atZone(UTC)));
            case 3 -> response(503, "soon");
            default -> response(200);
        });
        // The invalid value falls back to the exponential delay of the third attempt
        assertThat(sleeps).containsExactly(SECONDS.toNanos(2), SECONDS.toNanos(3), millis(600));
    }

    @Test
    void testStopsWhenNextAttemptWouldStartAfterMaxDuration()
    {
        AtomicInteger attempts = new AtomicInteger();
        HttpExchange.Result result = execute(new RetryConfig().setMaxDuration(new Duration(5, SECONDS)), () -> {
            attempts.incrementAndGet();
            return response(503, "30");
        });
        assertThat(result.statusCode()).isEqualTo(503);
        assertThat(attempts).hasValue(1);
        assertThat(sleeps).isEmpty();

        // The budget counts the delays already spent
        attempts.set(0);
        execute(new RetryConfig().setMaxAttempts(10).setMaxDuration(new Duration(5, SECONDS)), () -> {
            attempts.incrementAndGet();
            return response(503, "2");
        });
        assertThat(attempts).hasValue(3);
        assertThat(sleeps).containsExactly(SECONDS.toNanos(2), SECONDS.toNanos(2));
    }

    @Test
    void testInterrupted()
    {
        HttpRetrier retrier = new HttpRetrier(
                new RetryConfig(),
                Ticker.systemTicker(),
                Clock.systemUTC(),
                _ -> {
                    throw new InterruptedException();
                },
                () -> 0.5);
        try {
            assertThatThrownBy(() -> retrier.execute("test request", () -> response(503), () -> {}))
                    .isInstanceOf(ScimException.class)
                    .hasMessage("Interrupted while waiting to retry test request");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }
        finally {
            // Clears the flag for the next tests
            assertThat(Thread.interrupted()).isTrue();
        }
    }

    @Test
    void testParseRetryAfter()
    {
        HttpRetrier retrier = retrier(new RetryConfig(), sleeps);
        assertThat(retrier.parseRetryAfter("120")).contains(SECONDS.toNanos(120));
        assertThat(retrier.parseRetryAfter(" 0 ")).contains(0L);
        assertThat(retrier.parseRetryAfter("Mon, 23 Nov 2026 10:01:00 GMT")).contains(SECONDS.toNanos(60));
        // A date in the past means now
        assertThat(retrier.parseRetryAfter("Mon, 23 Nov 2026 09:00:00 GMT")).contains(0L);
        assertThat(retrier.parseRetryAfter("-1")).isEmpty();
        assertThat(retrier.parseRetryAfter("1.5")).isEmpty();
        assertThat(retrier.parseRetryAfter("99999999999999999999")).isEmpty();
        assertThat(retrier.parseRetryAfter("tomorrow")).isEmpty();
    }

    private HttpExchange.Result execute(RetryConfig config, Supplier<HttpExchange.Result> attempt)
    {
        return retrier(config, sleeps).execute("test request", attempt, retries::incrementAndGet);
    }

    private static HttpExchange.Result response(int status)
    {
        return new HttpExchange.Result(status, "", Optional.empty());
    }

    private static HttpExchange.Result response(int status, String retryAfter)
    {
        return new HttpExchange.Result(status, "", Optional.of(retryAfter));
    }

    private static long millis(long millis)
    {
        return MILLISECONDS.toNanos(millis);
    }
}
