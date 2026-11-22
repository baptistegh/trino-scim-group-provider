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

import com.google.inject.Binder;
import com.google.inject.Scopes;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.github.baptistegh.trino.scim.auth.BearerTokenAuthenticator;
import io.github.baptistegh.trino.scim.auth.BearerTokenConfig;
import io.github.baptistegh.trino.scim.auth.ClientCredentialsAuthenticator;
import io.github.baptistegh.trino.scim.auth.ClientCredentialsConfig;
import io.github.baptistegh.trino.scim.auth.ScimAuthenticator;
import io.trino.spi.security.GroupProvider;

import static io.airlift.configuration.ConfigBinder.configBinder;
import static io.airlift.http.client.HttpClientBinder.httpClientBinder;

public class ScimGroupProviderModule
        extends AbstractConfigurationAwareModule
{
    @Override
    protected void setup(Binder binder)
    {
        configBinder(binder).bindConfig(ScimGroupProviderConfig.class);
        // Exposes scim.http-client.* properties (timeouts, TLS trust store, ...)
        httpClientBinder(binder).bindHttpClient("scim", ForScim.class);

        switch (buildConfigObject(ScimGroupProviderConfig.class).getAuthenticationType()) {
            case OAUTH2_CLIENT_CREDENTIALS -> {
                configBinder(binder).bindConfig(ClientCredentialsConfig.class);
                binder.bind(ScimAuthenticator.class).to(ClientCredentialsAuthenticator.class).in(Scopes.SINGLETON);
            }
            case BEARER_TOKEN -> {
                configBinder(binder).bindConfig(BearerTokenConfig.class);
                binder.bind(ScimAuthenticator.class).to(BearerTokenAuthenticator.class).in(Scopes.SINGLETON);
            }
        }

        binder.bind(ScimClient.class).in(Scopes.SINGLETON);
        binder.bind(GroupProvider.class).to(ScimGroupProvider.class).in(Scopes.SINGLETON);
    }
}
