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
package io.github.baptistegh.trino.scim.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;

import java.util.List;

import static java.util.Objects.requireNonNullElse;

/**
 * SCIM ListResponse (RFC 7644, section 3.4.2). {@code Resources} may be absent when nothing matched.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimListResponse(
        @JsonProperty("totalResults") Integer totalResults,
        @JsonProperty("Resources") List<ScimUser> resources)
{
    public ScimListResponse
    {
        resources = ImmutableList.copyOf(requireNonNullElse(resources, ImmutableList.of()));
    }
}
