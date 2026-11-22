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
import com.google.inject.Inject;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.Request;
import io.airlift.json.JsonCodec;
import io.github.baptistegh.trino.scim.auth.ScimAuthenticator;
import io.github.baptistegh.trino.scim.model.ScimListResponse;
import io.github.baptistegh.trino.scim.model.ScimUser;

import java.net.URI;
import java.util.Optional;

import static com.google.common.net.HttpHeaders.ACCEPT;
import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static io.airlift.http.client.HttpUriBuilder.uriBuilderFrom;
import static io.airlift.http.client.Request.Builder.prepareGet;
import static io.airlift.json.JsonCodec.jsonCodec;
import static io.github.baptistegh.trino.scim.HttpExchange.setHeader;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Minimal SCIM 2.0 client (RFC 7644) that looks up a user and its group memberships.
 */
public class ScimClient
{
    private static final String SCIM_MEDIA_TYPE = "application/scim+json, application/json;q=0.9";
    private static final JsonCodec<ScimListResponse> LIST_RESPONSE_CODEC = jsonCodec(ScimListResponse.class);
    private static final JsonCodec<ScimUser> USER_CODEC = jsonCodec(ScimUser.class);
    private static final int MAX_ERROR_BODY_LENGTH = 512;

    private final HttpClient httpClient;
    private final ScimAuthenticator authenticator;
    private final URI baseUrl;
    private final String userFilterAttribute;

    @Inject
    public ScimClient(@ForScim HttpClient httpClient, ScimAuthenticator authenticator, ScimGroupProviderConfig config)
    {
        this.httpClient = requireNonNull(httpClient, "httpClient is null");
        this.authenticator = requireNonNull(authenticator, "authenticator is null");
        this.baseUrl = requireNonNull(config.getBaseUrl(), "baseUrl is null");
        this.userFilterAttribute = requireNonNull(config.getUserFilterAttribute(), "userFilterAttribute is null");
    }

    public Optional<ScimUser> findUser(String userName)
    {
        URI searchUri = uriBuilderFrom(baseUrl)
                .appendPath("Users")
                .addParameter("filter", equalityFilter(userFilterAttribute, userName))
                .addParameter("attributes", "groups")
                .build();
        ScimListResponse response = get(searchUri, LIST_RESPONSE_CODEC);

        if (response.resources().isEmpty()) {
            return Optional.empty();
        }
        if (response.resources().size() > 1) {
            throw new ScimException(format("SCIM service returned %s users where %s is '%s'", response.resources().size(), userFilterAttribute, userName));
        }

        ScimUser user = response.resources().getFirst();
        if (user.groups() == null && user.id() != null) {
            // Some services leave "groups" out of search results, or omit it when it is empty.
            // Reading the resource itself is the only way to tell the two apart.
            if (user.id().isEmpty() || user.id().contains("/") || user.id().equals(".") || user.id().equals("..")) {
                throw new ScimException(format("SCIM service returned an id that is not a valid path segment for user '%s': %s", userName, user.id()));
            }
            URI userUri = uriBuilderFrom(baseUrl)
                    .appendPath("Users")
                    .appendPath(user.id())
                    .addParameter("attributes", "groups")
                    .build();
            user = get(userUri, USER_CODEC);
        }
        return Optional.of(user);
    }

    /**
     * Builds {@code attribute eq "value"}. Filter values are JSON strings (RFC 7644, section 3.4.2.2),
     * so the value is escaped to prevent changing the meaning of the filter.
     */
    @VisibleForTesting
    static String equalityFilter(String attribute, String value)
    {
        StringBuilder filter = new StringBuilder(attribute).append(" eq \"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> filter.append("\\\"");
                case '\\' -> filter.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        filter.append(format("\\u%04x", (int) c));
                    }
                    else {
                        filter.append(c);
                    }
                }
            }
        }
        return filter.append('"').toString();
    }

    private <T> T get(URI uri, JsonCodec<T> codec)
    {
        HttpExchange.Result response = execute(uri);
        if (response.statusCode() == 401) {
            // The token may have been revoked or expired early; retry once with a fresh one
            authenticator.invalidate();
            response = execute(uri);
        }
        if (response.statusCode() == 401) {
            throw new ScimException(format("SCIM service rejected the credentials for %s: HTTP 401%s", uri, details(response.body())));
        }
        if (response.statusCode() != 200) {
            throw new ScimException(format("SCIM request %s failed: HTTP %s%s", uri, response.statusCode(), details(response.body())));
        }
        try {
            return codec.fromJson(response.body());
        }
        catch (IllegalArgumentException e) {
            throw new ScimException(format("Invalid response to SCIM request %s", uri), e);
        }
    }

    private HttpExchange.Result execute(URI uri)
    {
        Request.Builder builder = prepareGet().setUri(uri);
        setHeader(builder, AUTHORIZATION, authenticator.getAuthorizationHeader());
        setHeader(builder, ACCEPT, SCIM_MEDIA_TYPE);
        Request request = builder.build();
        return HttpExchange.execute(httpClient, request);
    }

    private static String details(String body)
    {
        if (body.isEmpty()) {
            return "";
        }
        return ": " + (body.length() <= MAX_ERROR_BODY_LENGTH ? body : body.substring(0, MAX_ERROR_BODY_LENGTH) + "...");
    }
}
