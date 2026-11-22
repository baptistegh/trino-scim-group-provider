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

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;
import io.airlift.configuration.ConfigSecuritySensitive;
import jakarta.validation.constraints.NotNull;

import java.net.URI;
import java.util.Optional;

public class ClientCredentialsConfig
{
    private URI tokenUrl;
    private String clientId;
    private String clientSecret;
    private Optional<String> scope = Optional.empty();

    @NotNull
    public URI getTokenUrl()
    {
        return tokenUrl;
    }

    @Config("scim.oauth2.token-url")
    @ConfigDescription("OAuth 2.0 token endpoint used to obtain an access token for the SCIM service")
    public ClientCredentialsConfig setTokenUrl(URI tokenUrl)
    {
        this.tokenUrl = tokenUrl;
        return this;
    }

    @NotNull
    public String getClientId()
    {
        return clientId;
    }

    @Config("scim.oauth2.client-id")
    public ClientCredentialsConfig setClientId(String clientId)
    {
        this.clientId = clientId;
        return this;
    }

    @NotNull
    public String getClientSecret()
    {
        return clientSecret;
    }

    @Config("scim.oauth2.client-secret")
    @ConfigSecuritySensitive
    public ClientCredentialsConfig setClientSecret(String clientSecret)
    {
        this.clientSecret = clientSecret;
        return this;
    }

    public Optional<String> getScope()
    {
        return scope;
    }

    @Config("scim.oauth2.scope")
    @ConfigDescription("Optional space-separated scopes to request")
    public ClientCredentialsConfig setScope(String scope)
    {
        this.scope = Optional.ofNullable(scope);
        return this;
    }
}
