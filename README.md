# Trino SCIM group provider

A [Trino](https://trino.io) group provider that looks up the groups of a user in a
[SCIM 2.0](https://datatracker.ietf.org/doc/html/rfc7644) service, such as the SCIM API of Keycloak.

Trino calls a group provider with nothing but a user name: for the user who logged in, but also for
impersonated users (`SET SESSION AUTHORIZATION`, the JDBC `sessionUser` property) and for the owners of
`SECURITY DEFINER` views, materialized views, SQL routines, row filters and column masks. Groups read from
an OAuth 2.0 / OIDC token only exist for the user who logged in, which is why Trino removed that option
([trinodb/trino#15669](https://github.com/trinodb/trino/pull/15669),
[#28646](https://github.com/trinodb/trino/pull/28646)). This plugin asks the identity provider for the
groups of any user, so all of these cases get the right groups.

## How it works

For each user, the plugin sends one request (two for services that leave `groups` out of search results):

```
GET {scim.base-url}/Users?filter=userName eq "alice"&attributes=groups
Authorization: Bearer <token obtained with the OAuth 2.0 client credentials grant>
```

It returns the `display` name of each entry of the user's `groups` attribute. Results are cached
(`scim.cache.ttl`). A user that does not exist has no groups. If the SCIM service cannot be reached or returns
an error, the query fails rather than running with fewer groups, since fewer groups can mean more access when
rules deny access based on group membership.

## Compatibility

**Trino:** 476 and later. The plugin is built against Trino 476, and CI loads the same artifact into Trino
476, 478, 480, 482, 484 and the latest release.

**SCIM services:** the plugin only uses standard SCIM 2.0: an `eq` filter on the Users endpoint and the
`groups` attribute of the User resource (RFC 7643, section 4.1.2), including its `type` (`direct` or
`indirect`).

| Service | Status |
|---|---|
| Keycloak 26.6+ (`scim-api` feature, experimental in Keycloak) | Tested in CI with 26.8.2 |
| Other SCIM 2.0 servers that return `groups` on User resources | Expected to work, untested |
| Microsoft Entra ID, Okta | Not supported: they are SCIM *clients* that push users to applications, and do not serve a SCIM API to query |

Keycloak returns group names, not paths: `/finance/analysts` and `/hr/analysts` are both `analysts`.
Keycloak realm roles are not exposed through SCIM.

## Installation

Download `trino-scim-group-provider-<version>.zip` from the
[releases](https://github.com/baptistegh/trino-scim-group-provider/releases). Each release lists the Trino versions
that exact zip was tested with. To check that the zip is the one built by this repository's release workflow:

```shell
sha256sum --check trino-scim-group-provider-<version>.zip.sha256
gh attestation verify trino-scim-group-provider-<version>.zip --repo baptistegh/trino-scim-group-provider
```

You can also build it with `mvn package`, which writes it to `target/`.

Unzip it into the plugin directory of every Trino node, for example `/usr/lib/trino/plugin/scim-group-provider/`,
then create `etc/group-provider.properties`:

```properties
group-provider.name=scim
scim.base-url=https://keycloak.example.com/realms/myrealm/scim/v2
scim.oauth2.token-url=https://keycloak.example.com/realms/myrealm/protocol/openid-connect/token
scim.oauth2.client-id=trino-scim
scim.oauth2.client-secret=${ENV:SCIM_CLIENT_SECRET}
```

## Configuration

| Property | Description | Default |
|---|---|---|
| `scim.base-url` | Base URL of the SCIM service. Required. | |
| `scim.authentication.type` | `OAUTH2_CLIENT_CREDENTIALS` or `BEARER_TOKEN` | `OAUTH2_CLIENT_CREDENTIALS` |
| `scim.oauth2.token-url` | Token endpoint for the client credentials grant | |
| `scim.oauth2.client-id` | Client ID | |
| `scim.oauth2.client-secret` | Client secret, sent with HTTP Basic authentication | |
| `scim.oauth2.scope` | Optional space-separated scopes to request | |
| `scim.bearer-token` | Static token, with `scim.authentication.type=BEARER_TOKEN` | |
| `scim.user-filter-attribute` | SCIM attribute matched against the Trino user name, for example `emails.value` | `userName` |
| `scim.include-indirect-groups` | Include groups inherited through nested groups | `true` |
| `scim.cache.ttl` | How long the groups of a user are cached; `0s` disables the cache | `5m` |
| `scim.cache.maximum-size` | Maximum number of cached users | `10000` |
| `scim.http-client.*` | [Airlift HTTP client](https://github.com/airlift/airlift/blob/master/http-client/src/main/java/io/airlift/http/client/HttpClientConfig.java) settings, for example `scim.http-client.request-timeout` or `scim.http-client.trust-store-path` | |

Group names can be converted to lower or upper case with Trino's `group-provider.group-case` property.

### Keycloak setup

1. Start Keycloak with `--features=scim-api` and enable **SCIM API** in the realm settings
   (`scimApiEnabled` in a realm export).
2. Create a confidential client with **Service accounts** enabled, and only that flow.
3. Give its service account these `realm-management` roles: `view-users`, `query-users`, `query-groups`
   and `view-realm`.
4. Add an **Audience** mapper to the client, with *Included Custom Audience* set to the realm's SCIM URL as
   Keycloak builds it from its frontend URL (`hostname` setting), for example
   `https://keycloak.example.com/realms/myrealm/scim/v2`, and *Add to access token* enabled. Without it,
   Keycloak rejects every SCIM request, and the plugin reports
   `SCIM service rejected the credentials ...: HTTP 401`.

`src/test/resources/keycloak/trino-realm.json` is a complete example.

## Local environment

`dev/` starts Keycloak and Trino. You log in to Trino through Keycloak (OIDC), and Trino resolves your groups
through SCIM. The access rules (`src/test/resources/trino/rules.json`) grant access based on those groups.

```shell
mvn clean package
docker compose -f dev/docker-compose.yml up --build
```

Use `TRINO_VERSION=484 docker compose ...` to pick another Trino image.

| User / password | Groups in Keycloak | Groups in Trino | Access |
|---|---|---|---|
| `alice` / `alice` | `/engineering/data-engineers` | `data-engineers`, `engineering` | All catalogs; may impersonate `bob` |
| `bob` / `bob` | `/analysts` | `analysts` | Read `tpch` and `memory.shared` |
| `carol` / `carol` | none | none | Nothing |

- Trino web UI: <https://localhost:8443/ui> (self-signed certificate)
- CLI: `trino --server https://localhost:8443 --insecure --external-authentication --user alice`.
  Pass `--user` with the name you log in with: the CLI otherwise sends your operating system user name as the
  session user, which the rules treat as impersonating that user and deny.
- Keycloak admin console: <http://localhost:8180> (`admin` / `admin`)

Things to try once logged in:

```sql
SELECT current_user, current_groups();

-- As alice: the session now runs as bob, with bob's groups
SET SESSION AUTHORIZATION bob;
SELECT current_user, current_groups();
```

## Development

```shell
mvn verify                              # checks, unit tests, then integration tests in Docker
mvn verify -DskipTests                  # checks only
mvn verify -Dtrino.test.version=484     # integration tests against another Trino image
mvn airstyle:format                     # format the code like Trino and Airlift
```

`mvn verify` runs the same checks as CI, configured like Trino and Airlift:

| Check | Tool |
|---|---|
| Formatting | [Airstyle](https://github.com/airlift/airstyle), fixed with `mvn airstyle:format` |
| Code style | Checkstyle with the Airbase rules |
| License header on every Java file | license-maven-plugin with the Airbase Apache header |
| Compiler warnings | `-Xlint:all`, warnings fail the build |
| Bug patterns | [Error Prone](https://errorprone.info), with the checks Trino raises to errors (`.mvn/errorprone.config`) |
| Outdated APIs | Modernizer, with Trino's exceptions (`.mvn/modernizer-exclusions.txt`) |
| Dependencies | `dependency:analyze`: every used dependency is declared, and no declared one is unused |
| Workflows and local environment | actionlint, `docker compose config` (CI only) |

`-Dchecks.skip` skips them, except compiler warnings and Error Prone.

The integration tests start Keycloak and Trino with Testcontainers. `TrinoCompatibilityIT` installs the
packaged plugin into the `trinodb/trino:${trino.test.version}` image and checks `current_groups()`,
group-based access rules, `SET SESSION AUTHORIZATION` and `SECURITY DEFINER` views.

To support an older Trino version, lower `dep.trino.version` and `project.build.targetJdk` (the Java version
that Trino version runs on) in `pom.xml`, and add the version to the matrix in `.github/workflows/ci.yml`.

## Releasing

Versions follow [semantic versioning](https://semver.org), independently of Trino versions. Raising the oldest
supported Trino version (`dep.trino.version`) is a breaking change.

To release, push a tag on a commit of `main` that passed CI:

```shell
git tag -s v0.1.0 -m v0.1.0
git push origin v0.1.0
```

`.github/workflows/release.yml` then:

1. Takes the version from the tag (`v0.1.0` → `0.1.0`); a suffix such as `v0.2.0-rc.1` makes a pre-release.
   `pom.xml` keeps a `-SNAPSHOT` version: the release build sets the version with `-Drevision`, so releasing
   needs no version commit.
2. Runs the whole CI workflow on the tag: checks, unit tests, and the Trino matrix from
   `.github/compatibility.json`.
3. Rebuilds the artifacts with the `release` profile, checks that the zip is byte-identical to the one CI
   tested, signs everything with GPG, and uploads it to Maven Central, where it waits for validation.
4. Publishes a GitHub release with the zip that passed those tests, its SHA-256 checksum, a build provenance
   attestation, the tested Trino and Keycloak versions, and notes generated from the merged pull requests,
   grouped by label (`.github/release.yml`).
5. **You publish the Maven Central deployment by hand**: check it on
   <https://central.sonatype.com/publishing/deployments> and click *Publish*. A version published to Central can
   never be changed or removed, so this is the one manual step. If something is wrong, *Drop* the deployment,
   delete the tag and the GitHub release, and release again.

The workflow needs these repository secrets: `CENTRAL_USERNAME` and `CENTRAL_PASSWORD` (a user token of the
Central Portal, for the verified `io.github.baptistegh` namespace), `GPG_PRIVATE_KEY` (armored private key) and
`GPG_PASSPHRASE`. The public key must be on a key server Central checks, such as `keyserver.ubuntu.com`.

Builds are reproducible: CI uses the commit time as the timestamp of the jar and zip entries, so rebuilding a tag
with `mvn package -Drevision=0.1.0 -Dproject.build.outputTimestamp=$(git log -1 --format=%ct)` produces the same
zip.

Dependency updates are proposed by [Renovate](https://docs.renovatebot.com) (`renovate.json`), except the oldest
supported Trino version, which is only changed on purpose.
