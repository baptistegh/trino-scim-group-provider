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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;
import com.google.inject.Inject;
import io.airlift.log.Logger;
import io.airlift.units.Duration;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.String.format;
import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
import static java.time.temporal.ChronoUnit.NANOS;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Retries HTTP requests that failed for a reason that is likely to go away: no response at all
 * (connection refused, reset, timeout), HTTP 429, or HTTP 502, 503 or 504. Other responses, including
 * HTTP 500, are returned right away, since retrying them would most likely get the same response.
 * <p>
 * The delay doubles with each retry, starting at {@code scim.retry.initial-delay} and capped by
 * {@code scim.retry.max-delay}, with random jitter so that nodes do not retry in lockstep. A
 * {@code Retry-After} header replaces the computed delay. No retry starts later than
 * {@code scim.retry.max-duration} after the first attempt: when the next delay would end past that, the
 * last result is returned, or its failure thrown, so the caller reports it like any failed request.
 */
public class HttpRetrier
{
    private static final Logger log = Logger.get(HttpRetrier.class);
    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(429, 502, 503, 504);

    @VisibleForTesting
    public interface Sleeper
    {
        void sleep(long nanos)
                throws InterruptedException;
    }

    private final int maxAttempts;
    private final long initialDelayNanos;
    private final long maxDelayNanos;
    private final long maxDurationNanos;
    private final Ticker ticker;
    private final Clock clock;
    private final Sleeper sleeper;
    private final DoubleSupplier random;

    @Inject
    public HttpRetrier(RetryConfig config)
    {
        this(config, Ticker.systemTicker(), Clock.systemUTC(), NANOSECONDS::sleep, () -> ThreadLocalRandom.current().nextDouble());
    }

    /**
     * @param random returns a number in [0, 1) that sets the jitter
     */
    @VisibleForTesting
    public HttpRetrier(RetryConfig config, Ticker ticker, Clock clock, Sleeper sleeper, DoubleSupplier random)
    {
        this.maxAttempts = config.getMaxAttempts();
        this.initialDelayNanos = config.getInitialDelay().roundTo(NANOSECONDS);
        this.maxDelayNanos = config.getMaxDelay().roundTo(NANOSECONDS);
        this.maxDurationNanos = config.getMaxDuration().roundTo(NANOSECONDS);
        this.ticker = requireNonNull(ticker, "ticker is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.sleeper = requireNonNull(sleeper, "sleeper is null");
        this.random = requireNonNull(random, "random is null");
    }

    /**
     * @param description what the request is, for log and error messages
     * @param attempt sends the request once, and throws {@link ScimException} when no response was received
     * @param onRetry called before each retry
     */
    public HttpExchange.Result execute(String description, Supplier<HttpExchange.Result> attempt, Runnable onRetry)
    {
        long start = ticker.read();
        for (int attemptNumber = 1; ; attemptNumber++) {
            HttpExchange.Result result = null;
            ScimException failure = null;
            try {
                result = attempt.get();
                if (!RETRYABLE_STATUS_CODES.contains(result.statusCode())) {
                    return result;
                }
            }
            catch (ScimException e) {
                failure = e;
            }

            String reason = failure != null ? failure.getMessage() : "HTTP " + result.statusCode();
            if (attemptNumber >= maxAttempts) {
                return resultOrThrow(result, failure);
            }
            Optional<Long> retryAfter = result == null ? Optional.empty() : result.retryAfter().flatMap(this::parseRetryAfter);
            long delay = retryAfter.isPresent() ? retryAfter.get() : backoff(attemptNumber);
            long remaining = maxDurationNanos - (ticker.read() - start);
            if (delay > remaining) {
                log.debug(
                        "Not retrying %s after %s: the next attempt would start %s after the first one, which is more than scim.retry.max-duration",
                        description,
                        reason,
                        Duration.succinctNanos(ticker.read() - start + delay));
                return resultOrThrow(result, failure);
            }

            log.debug("Retrying %s in %s after attempt %s failed: %s", description, Duration.succinctNanos(delay), attemptNumber, reason);
            onRetry.run();
            try {
                sleeper.sleep(delay);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ScimException(format("Interrupted while waiting to retry %s", description), e);
            }
        }
    }

    private static HttpExchange.Result resultOrThrow(HttpExchange.Result result, ScimException failure)
    {
        if (failure != null) {
            throw failure;
        }
        return result;
    }

    /**
     * Half of the exponential delay, plus a random part of the other half.
     */
    private long backoff(int attemptNumber)
    {
        long delay = min(maxDelayNanos, initialDelayNanos << min(attemptNumber - 1, 30));
        long fixed = delay / 2;
        return fixed + (long) (random.getAsDouble() * (delay - fixed));
    }

    /**
     * Parses a {@code Retry-After} value, either a number of seconds or an HTTP date (RFC 9110, section 10.2.3).
     */
    @VisibleForTesting
    Optional<Long> parseRetryAfter(String value)
    {
        String trimmed = value.trim();
        if (trimmed.matches("[0-9]{1,9}")) {
            return Optional.of(SECONDS.toNanos(Long.parseLong(trimmed)));
        }
        try {
            ZonedDateTime date = ZonedDateTime.parse(trimmed, RFC_1123_DATE_TIME);
            return Optional.of(max(0, clock.instant().until(date, NANOS)));
        }
        catch (DateTimeParseException | ArithmeticException e) {
            log.debug("Ignoring invalid Retry-After header: %s", value);
            return Optional.empty();
        }
    }
}
