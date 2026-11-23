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

import io.airlift.stats.TimeStat;

public final class TestingStats
{
    private TestingStats() {}

    /**
     * Number of durations recorded by a {@link TimeStat}. Newer Airlift versions only merge recorded durations
     * into what the getters return once in a while, and {@code snapshot()} forces the merge. The accessors of the
     * snapshot itself were renamed, so the count is read through the getters, which work with every version.
     */
    public static double recordedCount(TimeStat stat)
    {
        stat.snapshot();
        return stat.getAllTime().getCount();
    }
}
