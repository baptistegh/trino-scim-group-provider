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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.net.URI;

import static java.util.concurrent.TimeUnit.MINUTES;

public class ScimGroupProviderConfig
{
    public enum AuthenticationType
    {
        OAUTH2_CLIENT_CREDENTIALS,
        BEARER_TOKEN,
    }

    private URI baseUrl;
    private AuthenticationType authenticationType = AuthenticationType.OAUTH2_CLIENT_CREDENTIALS;
    private String userFilterAttribute = "userName";
    private boolean includeIndirectGroups = true;
    private Duration cacheTtl = new Duration(5, MINUTES);
    private long cacheMaximumSize = 10_000;

    @NotNull
    public URI getBaseUrl()
    {
        return baseUrl;
    }

    @Config("scim.base-url")
    @ConfigDescription("Base URL of the SCIM 2.0 service, for example https://idp.example.com/realms/myrealm/scim/v2")
    public ScimGroupProviderConfig setBaseUrl(URI baseUrl)
    {
        this.baseUrl = baseUrl;
        return this;
    }

    @NotNull
    public AuthenticationType getAuthenticationType()
    {
        return authenticationType;
    }

    @Config("scim.authentication.type")
    @ConfigDescription("How the plugin authenticates to the SCIM service")
    public ScimGroupProviderConfig setAuthenticationType(AuthenticationType authenticationType)
    {
        this.authenticationType = authenticationType;
        return this;
    }

    @NotNull
    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.:$-]*", message = "must be a SCIM attribute path")
    public String getUserFilterAttribute()
    {
        return userFilterAttribute;
    }

    @Config("scim.user-filter-attribute")
    @ConfigDescription("SCIM User attribute that is matched against the Trino user name")
    public ScimGroupProviderConfig setUserFilterAttribute(String userFilterAttribute)
    {
        this.userFilterAttribute = userFilterAttribute;
        return this;
    }

    public boolean isIncludeIndirectGroups()
    {
        return includeIndirectGroups;
    }

    @Config("scim.include-indirect-groups")
    @ConfigDescription("Include groups the user belongs to through a nested group membership")
    public ScimGroupProviderConfig setIncludeIndirectGroups(boolean includeIndirectGroups)
    {
        this.includeIndirectGroups = includeIndirectGroups;
        return this;
    }

    @NotNull
    @MinDuration("0s")
    public Duration getCacheTtl()
    {
        return cacheTtl;
    }

    @Config("scim.cache.ttl")
    @ConfigDescription("How long the groups of a user are cached; 0s disables caching")
    public ScimGroupProviderConfig setCacheTtl(Duration cacheTtl)
    {
        this.cacheTtl = cacheTtl;
        return this;
    }

    @Min(1)
    public long getCacheMaximumSize()
    {
        return cacheMaximumSize;
    }

    @Config("scim.cache.maximum-size")
    @ConfigDescription("Maximum number of users whose groups are cached")
    public ScimGroupProviderConfig setCacheMaximumSize(long cacheMaximumSize)
    {
        this.cacheMaximumSize = cacheMaximumSize;
        return this;
    }
}
