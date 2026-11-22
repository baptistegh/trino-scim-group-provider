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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

/**
 * Loads the packaged plugin into the {@code trinodb/trino} image selected by the {@code trino.test.version}
 * system property, and checks group resolution through Trino itself, including the cases that motivate a
 * group provider: impersonation with SET SESSION AUTHORIZATION and SECURITY DEFINER views.
 * <p>
 * Trino runs without authentication here: the group provider only sees user names, so how the user
 * authenticated does not change what this test covers.
 */
@TestInstance(PER_CLASS)
final class TrinoCompatibilityIT
{
    private static final int TRINO_PORT = 8080;
    private static final String PLUGIN_DIRECTORY = "/usr/lib/trino/plugin/scim-group-provider";

    private Network network;
    private TestingKeycloak keycloak;
    private GenericContainer<?> trino;

    @BeforeAll
    @SuppressWarnings("resource")
    void setUp()
            throws Exception
    {
        network = Network.newNetwork();
        keycloak = new TestingKeycloak(network);
        keycloak.start();

        String trinoVersion = requireNonNull(System.getProperty("trino.test.version"), "trino.test.version is not set");
        trino = new GenericContainer<>("trinodb/trino:" + trinoVersion)
                .withNetwork(network)
                .withExposedPorts(TRINO_PORT)
                .withCopyFileToContainer(MountableFile.forHostPath(unpackPlugin()), PLUGIN_DIRECTORY)
                .withCopyFileToContainer(MountableFile.forClasspathResource("trino/group-provider.properties"), "/etc/trino/group-provider.properties")
                .withCopyFileToContainer(MountableFile.forClasspathResource("trino/access-control.properties"), "/etc/trino/access-control.properties")
                .withCopyFileToContainer(MountableFile.forClasspathResource("trino/rules.json"), "/etc/trino/rules.json")
                .waitingFor(Wait.forLogMessage(".*======== SERVER STARTED ========.*", 1).withStartupTimeout(Duration.ofMinutes(3)))
                .withStartupAttempts(1)
                .withLogConsumer(frame -> appendServerLog(trinoVersion, frame.getUtf8String()));
        try {
            trino.start();
        }
        catch (RuntimeException e) {
            // Plugin loading errors only show up in the server log
            System.err.println("Trino %s failed to start:%n%s".formatted(trinoVersion, trino.getLogs()));
            throw e;
        }

        awaitWorkerAvailable();

        // alice is in engineering through /engineering/data-engineers, which the rules allow to create objects
        execute("alice", "CREATE SCHEMA memory.secret");
        execute("alice", "CREATE TABLE memory.secret.salaries AS SELECT * FROM (VALUES ('alice', 100), ('bob', 90)) AS t(name, salary)");
        execute("alice", "CREATE SCHEMA memory.shared");
        execute("alice", "CREATE VIEW memory.shared.salary_count SECURITY DEFINER AS SELECT count(*) AS employees FROM memory.secret.salaries");
    }

    @AfterAll
    void tearDown()
    {
        if (trino != null) {
            trino.close();
        }
        if (keycloak != null) {
            keycloak.close();
        }
        if (network != null) {
            network.close();
        }
    }

    @Test
    void testCurrentGroups()
            throws SQLException
    {
        assertThat(currentGroups("alice")).containsExactlyInAnyOrder("data-engineers", "engineering");
        assertThat(currentGroups("bob")).containsExactly("analysts");
        assertThat(currentGroups("carol")).isEmpty();
    }

    @Test
    void testGroupBasedAccessControl()
            throws SQLException
    {
        assertThat(queryLong("bob", "SELECT count(*) FROM tpch.tiny.nation")).isEqualTo(25);
        assertThatThrownBy(() -> queryLong("carol", "SELECT count(*) FROM tpch.tiny.nation"))
                .hasMessageContaining("Access Denied");
    }

    @Test
    void testSetSessionAuthorizationResolvesGroupsOfImpersonatedUser()
            throws SQLException
    {
        try (Connection connection = connect("alice"); Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION AUTHORIZATION bob");
            assertThat(queryString(statement, "SELECT current_user")).isEqualTo("bob");
            assertThat(groups(statement)).containsExactly("analysts");
        }

        try (Connection connection = connect("carol"); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("SET SESSION AUTHORIZATION alice"))
                    .hasMessageContaining("Access Denied");
        }
    }

    @Test
    void testSecurityDefinerViewRunsWithGroupsOfOwner()
            throws SQLException
    {
        // bob cannot read the table, but the view reads it as its owner alice, whose groups come from SCIM
        assertThatThrownBy(() -> queryLong("bob", "SELECT count(*) FROM memory.secret.salaries"))
                .hasMessageContaining("Access Denied");
        assertThat(queryLong("bob", "SELECT employees FROM memory.shared.salary_count")).isEqualTo(2);
    }

    private List<String> currentGroups(String user)
            throws SQLException
    {
        try (Connection connection = connect(user); Statement statement = connection.createStatement()) {
            return groups(statement);
        }
    }

    private static List<String> groups(Statement statement)
            throws SQLException
    {
        try (ResultSet resultSet = statement.executeQuery("SELECT current_groups()")) {
            assertThat(resultSet.next()).isTrue();
            Object[] groups = (Object[]) resultSet.getArray(1).getArray();
            return Arrays.stream(groups).map(String.class::cast).toList();
        }
    }

    private long queryLong(String user, String sql)
            throws SQLException
    {
        try (Connection connection = connect(user);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    private static String queryString(Statement statement, String sql)
            throws SQLException
    {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private void execute(String user, String sql)
            throws SQLException
    {
        try (Connection connection = connect(user); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Connection connect(String user)
            throws SQLException
    {
        String url = "jdbc:trino://%s:%s".formatted(trino.getHost(), trino.getMappedPort(TRINO_PORT));
        return DriverManager.getConnection(url, user, null);
    }

    /**
     * "SERVER STARTED" is logged before the node announces itself, and until then queries that need a
     * worker fail with NO_NODES_AVAILABLE.
     */
    private void awaitWorkerAvailable()
            throws InterruptedException
    {
        long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        while (true) {
            try {
                queryLong("alice", "SELECT count(*) FROM tpch.tiny.region");
                return;
            }
            catch (SQLException e) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("Trino did not become ready", e);
                }
                Thread.sleep(500);
            }
        }
    }

    /**
     * Keeps the server log next to the test reports, which CI uploads when a job fails.
     */
    private static synchronized void appendServerLog(String trinoVersion, String text)
    {
        try {
            Path log = Path.of("target", "failsafe-reports", "trino-" + trinoVersion + "-server.log");
            Files.createDirectories(log.getParent());
            Files.writeString(log, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Extracts the plugin zip built by the package phase, the same artifact users install.
     */
    private static Path unpackPlugin()
            throws IOException
    {
        Path zip = Path.of(requireNonNull(System.getProperty("plugin.zip"), "plugin.zip is not set; run the tests with 'mvn verify'"));
        // Permissions are copied into the container, where Trino runs as an unprivileged user
        Path target = Files.createTempDirectory("trino-scim-plugin", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        List<Path> files = new ArrayList<>();
        try (InputStream input = Files.newInputStream(zip); ZipInputStream zipInput = new ZipInputStream(input)) {
            for (ZipEntry entry = zipInput.getNextEntry(); entry != null; entry = zipInput.getNextEntry()) {
                if (entry.isDirectory()) {
                    continue;
                }
                // Entries are under a top-level <artifact>-<version>/ directory
                Path file = target.resolve(Path.of(entry.getName()).getFileName().toString());
                Files.copy(zipInput, file);
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
                files.add(file);
            }
        }
        assertThat(files).as("plugin files in %s", zip).isNotEmpty();
        return target;
    }
}
