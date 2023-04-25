/**
 * Copyright (C) 2011-2022 Red Hat, Inc. (https://github.com/Commonjava/service-parent)
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
package org.commonjava.indy.service.archive.config;

import io.quarkus.arc.config.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@ConfigProperties( prefix = "pre-seed" )
public class PreSeedConfig
{
    @ConfigProperty( name = "main-indy" )
    public Optional<String> mainIndy;

    @ConfigProperty( name = "storage-dir" )
    public Optional<String> storageDir;

    public Optional<String> getMainIndy()
    {
        return mainIndy;
    }

    public void setMainIndy( Optional<String> mainIndy )
    {
        this.mainIndy = mainIndy;
    }

    public Optional<String> getStorageDir()
    {
        return storageDir;
    }

    public void setStorageDir( Optional<String> storageDir )
    {
        this.storageDir = storageDir;
    }
}
