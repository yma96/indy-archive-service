/**
 * Copyright (C) 2021-2023 Red Hat, Inc. (https://github.com/Commonjava/service-parent)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.indy.service.archive.util;

import org.commonjava.indy.service.archive.config.PreSeedConfig;

import java.util.Optional;

public class TestPreSeedConfig
        implements PreSeedConfig
{
    Optional<String> mainIndy;
    public TestPreSeedConfig(Optional<String> mainIndy)
    {
        this.mainIndy = mainIndy;
    }

    @Override
    public Optional<String> mainIndy()
    {
        return mainIndy;
    }

    @Override
    public Optional<String> storageDir()
    {
        return Optional.of( "data" );
    }

    @Override
    public Optional<Long> notUsedDaysCleanup()
    {
        return Optional.of( 365l );
    }

    @Override
    public Optional<Integer> threadMultiplier()
    {
        return Optional.of( 4 );
    }

    public Optional<Integer> generationTimeoutMinutes()
    {
        return Optional.of( 60 );
    }

}
