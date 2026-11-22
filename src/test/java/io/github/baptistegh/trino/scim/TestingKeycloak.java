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

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

/**
 * Keycloak with the SCIM API enabled and the realm from {@code keycloak/trino-realm.json}.
 */
final class TestingKeycloak
        implements AutoCloseable
{
    static final String NETWORK_ALIAS = "keycloak";
    static final String SCIM_CLIENT_ID = "trino-scim";
    static final String SCIM_CLIENT_SECRET = "trino-scim-secret";
    private static final int PORT = 8080;

    private final GenericContainer<?> container;

    @SuppressWarnings("resource")
    TestingKeycloak(Network network)
    {
        container = new GenericContainer<>(System.getProperty("keycloak.test.image", "quay.io/keycloak/keycloak:26.8.2"))
                .withNetwork(network)
                .withNetworkAliases(NETWORK_ALIAS)
                .withExposedPorts(PORT)
                // Keycloak's SCIM API requires the token audience to be the SCIM URL derived from this frontend URL,
                // so it is fixed: tests reach Keycloak on a random local port, Trino on keycloak:8080
                .withEnv("KC_HOSTNAME", "http://" + NETWORK_ALIAS + ":" + PORT)
                .withCopyFileToContainer(MountableFile.forClasspathResource("keycloak/trino-realm.json"), "/opt/keycloak/data/import/trino-realm.json")
                .withCommand("start-dev", "--import-realm", "--features=scim-api")
                .waitingFor(Wait.forHttp("/realms/trino/scim/v2/ServiceProviderConfig")
                        .forPort(PORT)
                        // The SCIM endpoints require a token; 401 means the realm was imported with SCIM enabled
                        .forStatusCodeMatching(status -> status == 200 || status == 401)
                        .withStartupTimeout(Duration.ofMinutes(3)));
    }

    void start()
    {
        container.start();
    }

    String externalUrl()
    {
        return "http://" + container.getHost() + ":" + container.getMappedPort(PORT);
    }

    String scimUrl()
    {
        return externalUrl() + "/realms/trino/scim/v2";
    }

    String tokenUrl()
    {
        return externalUrl() + "/realms/trino/protocol/openid-connect/token";
    }

    @Override
    public void close()
    {
        container.close();
    }
}
