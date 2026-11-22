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

import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.inject.Inject;
import io.airlift.log.Logger;
import io.github.baptistegh.trino.scim.model.ScimGroupMembership;
import io.github.baptistegh.trino.scim.model.ScimUser;
import io.trino.cache.EvictableCacheBuilder;
import io.trino.spi.TrinoException;
import io.trino.spi.security.GroupProvider;

import java.util.Optional;
import java.util.Set;

import static io.github.baptistegh.trino.scim.ScimErrorCode.SCIM_GROUP_LOOKUP_FAILED;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Resolves the groups of a user from a SCIM 2.0 service.
 * <p>
 * Lookups only need the user name, so groups are also resolved for users who are not
 * the authenticated user: impersonated users, and owners of views, routines, row filters
 * and column masks.
 * <p>
 * A failed lookup fails the request instead of returning no groups, because returning
 * fewer groups can grant more access when rules deny access based on group membership.
 */
public class ScimGroupProvider
        implements GroupProvider
{
    private static final Logger log = Logger.get(ScimGroupProvider.class);

    private final ScimClient client;
    private final boolean includeIndirectGroups;
    private final LoadingCache<String, Set<String>> cache;

    @Inject
    public ScimGroupProvider(ScimClient client, ScimGroupProviderConfig config)
    {
        this.client = requireNonNull(client, "client is null");
        this.includeIndirectGroups = config.isIncludeIndirectGroups();
        // Failures are not cached, so the next request retries the lookup
        this.cache = EvictableCacheBuilder.newBuilder()
                .expireAfterWrite(config.getCacheTtl().toMillis(), MILLISECONDS)
                .maximumSize(config.getCacheMaximumSize())
                .shareNothingWhenDisabled()
                .build(CacheLoader.from(this::loadGroups));
    }

    @Override
    public Set<String> getGroups(String user)
    {
        requireNonNull(user, "user is null");
        try {
            return cache.getUnchecked(user);
        }
        catch (UncheckedExecutionException e) {
            throw new TrinoException(SCIM_GROUP_LOOKUP_FAILED, format("Failed to resolve groups of user '%s' from SCIM: %s", user, e.getCause().getMessage()), e.getCause());
        }
    }

    private Set<String> loadGroups(String user)
    {
        Optional<ScimUser> scimUser = client.findUser(user);
        if (scimUser.isEmpty()) {
            log.debug("User '%s' not found in SCIM service", user);
            return ImmutableSet.of();
        }
        if (scimUser.get().groups() == null) {
            return ImmutableSet.of();
        }

        ImmutableSet.Builder<String> groups = ImmutableSet.builder();
        for (ScimGroupMembership membership : scimUser.get().groups()) {
            if (membership.isIndirect() && !includeIndirectGroups) {
                continue;
            }
            if (membership.display() == null || membership.display().isEmpty()) {
                log.warn("Ignoring group %s of user '%s': SCIM service returned no display name", membership.value(), user);
                continue;
            }
            groups.add(membership.display());
        }
        return groups.build();
    }
}
