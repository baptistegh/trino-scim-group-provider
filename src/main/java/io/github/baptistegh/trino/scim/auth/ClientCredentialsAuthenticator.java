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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.inject.Inject;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.Request;
import io.airlift.json.JsonCodec;
import io.airlift.stats.CounterStat;
import io.airlift.stats.TimeStat;
import io.github.baptistegh.trino.scim.ForScim;
import io.github.baptistegh.trino.scim.HttpExchange;
import io.github.baptistegh.trino.scim.ScimException;
import io.github.baptistegh.trino.scim.model.TokenResponse;
import org.weakref.jmx.Managed;
import org.weakref.jmx.Nested;

import java.net.URI;
import java.util.Base64;
import java.util.Optional;

import static com.google.common.net.HttpHeaders.ACCEPT;
import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static io.airlift.http.client.Request.Builder.preparePost;
import static io.airlift.http.client.StaticBodyGenerator.createStaticBodyGenerator;
import static io.airlift.json.JsonCodec.jsonCodec;
import static io.github.baptistegh.trino.scim.HttpExchange.setHeader;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.String.format;
import static java.net.URLEncoder.encode;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Obtains access tokens with the OAuth 2.0 client credentials grant (RFC 6749, section 4.4) and
 * reuses each token until shortly before it expires.
 */
public class ClientCredentialsAuthenticator
        implements ScimAuthenticator
{
    private static final JsonCodec<TokenResponse> TOKEN_RESPONSE_CODEC = jsonCodec(TokenResponse.class);
    private static final long DEFAULT_EXPIRES_IN_SECONDS = 60;
    private static final long MAX_REFRESH_MARGIN_SECONDS = 30;

    private final HttpClient httpClient;
    private final URI tokenUrl;
    private final String clientAuthorizationHeader;
    private final Optional<String> scope;
    private final Ticker ticker;

    private final CounterStat tokenRequests = new CounterStat();
    private final TimeStat tokenRequestTime = new TimeStat(MILLISECONDS);
    private final CounterStat tokenFailures = new CounterStat();

    @GuardedBy("this")
    private AccessToken token;

    @Inject
    public ClientCredentialsAuthenticator(@ForScim HttpClient httpClient, ClientCredentialsConfig config)
    {
        this(httpClient, config, Ticker.systemTicker());
    }

    @VisibleForTesting
    ClientCredentialsAuthenticator(HttpClient httpClient, ClientCredentialsConfig config, Ticker ticker)
    {
        this.httpClient = requireNonNull(httpClient, "httpClient is null");
        this.tokenUrl = requireNonNull(config.getTokenUrl(), "tokenUrl is null");
        // client_secret_basic: both parts are form-encoded before being joined (RFC 6749, section 2.3.1)
        String credentials = encode(config.getClientId(), UTF_8) + ":" + encode(config.getClientSecret(), UTF_8);
        this.clientAuthorizationHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(UTF_8));
        this.scope = requireNonNull(config.getScope(), "scope is null");
        this.ticker = requireNonNull(ticker, "ticker is null");
    }

    @Override
    public synchronized String getAuthorizationHeader()
    {
        if (token == null || ticker.read() >= token.refreshAtNanos()) {
            token = fetchToken();
        }
        return "Bearer " + token.value();
    }

    @Override
    public synchronized void invalidate()
    {
        token = null;
    }

    /**
     * Token requests sent to the token endpoint.
     */
    @Managed
    @Nested
    public CounterStat getTokenRequests()
    {
        return tokenRequests;
    }

    @Managed
    @Nested
    public TimeStat getTokenRequestTime()
    {
        return tokenRequestTime;
    }

    /**
     * Token requests that did not return a usable token.
     */
    @Managed
    @Nested
    public CounterStat getTokenFailures()
    {
        return tokenFailures;
    }

    private AccessToken fetchToken()
    {
        tokenRequests.update(1);
        try (TimeStat.BlockTimer _ = tokenRequestTime.time()) {
            return requestToken();
        }
        catch (RuntimeException e) {
            tokenFailures.update(1);
            throw e;
        }
    }

    private AccessToken requestToken()
    {
        StringBuilder body = new StringBuilder("grant_type=client_credentials");
        scope.ifPresent(value -> body.append("&scope=").append(encode(value, UTF_8)));

        Request.Builder builder = preparePost()
                .setUri(tokenUrl)
                .setBodyGenerator(createStaticBodyGenerator(body.toString(), UTF_8));
        setHeader(builder, AUTHORIZATION, clientAuthorizationHeader);
        setHeader(builder, CONTENT_TYPE, "application/x-www-form-urlencoded");
        setHeader(builder, ACCEPT, "application/json");
        Request request = builder.build();

        long requestedAt = ticker.read();
        HttpExchange.Result response;
        try {
            response = HttpExchange.execute(httpClient, request);
        }
        catch (ScimException e) {
            throw new ScimException(format("Failed to obtain an access token from %s", tokenUrl), e);
        }
        if (response.statusCode() != 200) {
            throw new ScimException(format("Failed to obtain an access token from %s: HTTP %s: %s", tokenUrl, response.statusCode(), response.body()));
        }

        TokenResponse tokenResponse;
        try {
            tokenResponse = TOKEN_RESPONSE_CODEC.fromJson(response.body());
        }
        catch (IllegalArgumentException e) {
            throw new ScimException(format("Invalid token response from %s", tokenUrl), e);
        }
        if (tokenResponse.accessToken() == null || tokenResponse.accessToken().isEmpty()) {
            throw new ScimException(format("Token response from %s does not contain an access_token", tokenUrl));
        }
        if (tokenResponse.tokenType() != null && !tokenResponse.tokenType().equalsIgnoreCase("bearer")) {
            throw new ScimException(format("Unsupported token type '%s' returned by %s", tokenResponse.tokenType(), tokenUrl));
        }

        long expiresIn = tokenResponse.expiresIn() == null ? DEFAULT_EXPIRES_IN_SECONDS : tokenResponse.expiresIn();
        // Refresh ahead of expiry so a token never expires in flight
        long lifetime = max(0, expiresIn - min(MAX_REFRESH_MARGIN_SECONDS, expiresIn / 2));
        return new AccessToken(tokenResponse.accessToken(), requestedAt + SECONDS.toNanos(lifetime));
    }

    private record AccessToken(String value, long refreshAtNanos) {}
}
