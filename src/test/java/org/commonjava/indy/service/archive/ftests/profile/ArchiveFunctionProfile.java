/**
 * Copyright (C) 2011-2021 Red Hat, Inc. (https://github.com/Commonjava/service-parent)
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
package org.commonjava.indy.service.archive.ftests.profile;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.HashMap;
import java.util.Map;

public class ArchiveFunctionProfile
                implements QuarkusTestProfile
{
    public static final String MAIN_INDY = "indy-admin.psi.redhat.com";

    public static final String STORAGE_DIR = "data";

    @Override
    public Map<String, String> getConfigOverrides()
    {
        Map<String, String> configs = new HashMap<>();
        configs.put( "pre-seed.main-indy", MAIN_INDY );
        configs.put( "pre-seed.storage-dir", STORAGE_DIR );
        return configs;
    }

    @Override
    public String getConfigProfile()
    {
        return "dev";
    }
}
