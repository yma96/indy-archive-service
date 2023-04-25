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
package org.commonjava.indy.service.archive.util;

import io.quarkus.test.junit.QuarkusTest;
import org.commonjava.indy.service.archive.config.PreSeedConfig;
import org.commonjava.indy.service.archive.model.StoreKey;
import org.commonjava.indy.service.archive.model.StoreType;
import org.commonjava.indy.service.archive.model.dto.HistoricalContentDTO;
import org.commonjava.indy.service.archive.model.dto.HistoricalEntryDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
public class HistoricalContentListReaderTest
{

    private final String MAIN_INDY = "indy.main.server";

    private final String MAVEN_PATH = "/org/apache/maven/maven-core/3.0/maven-core-3.0.jar";

    private final String MAVEN_METADATA_PATH = "/org/apache/maven/maven-core/3.0/maven-metadata.xml";

    private final String NPM_TARBALL_PATH = "/@babel/helper-validator-identifier/-/helper-validator-identifier-7.10.4.tgz";

    private final String NPM_METADATA_PATH = "/@babel%2fhelper-validator-identifier";

    @Test
    public void testMavenReadPaths()
    {
        StoreKey store = new StoreKey( "maven", StoreType.hosted, "test" );
        List<HistoricalEntryDTO> entryDTOs = new ArrayList<>();
        HistoricalEntryDTO entry = new HistoricalEntryDTO( store, MAVEN_PATH );
        HistoricalEntryDTO metaEntry = new HistoricalEntryDTO( store, MAVEN_METADATA_PATH );

        entryDTOs.add( entry );
        entryDTOs.add( metaEntry );

        HistoricalContentDTO contentDTO = new HistoricalContentDTO( "8888", entryDTOs.toArray(
                        new HistoricalEntryDTO[entryDTOs.size()] ) );

        PreSeedConfig preSeedConfig = new PreSeedConfig();
        preSeedConfig.setMainIndy( Optional.of( MAIN_INDY ) );
        HistoricalContentListReader reader = new HistoricalContentListReader( preSeedConfig );

        Map<String, String> paths = reader.readPaths( contentDTO );

        assertThat( paths.size(), equalTo( 1 ) );
        String storePath = MAIN_INDY + "/api/content" + entry.getStorePath();
        assertNotNull( paths.get( storePath + MAVEN_PATH ) );
        assertThat( paths.get( storePath + MAVEN_PATH ), equalTo( MAVEN_PATH ) );

        //maven-metadata.xml will be ignored
        assertNull( paths.get( storePath + MAVEN_METADATA_PATH ) );
    }

    @Test
    public void testNPMReadPaths()
    {
        List<HistoricalEntryDTO> entryDTOs = new ArrayList<>();
        StoreKey npmStore = new StoreKey( "npm", StoreType.hosted, "test" );
        HistoricalEntryDTO npmEntry = new HistoricalEntryDTO( npmStore, NPM_TARBALL_PATH );
        HistoricalEntryDTO npmMetaEntry = new HistoricalEntryDTO( npmStore, NPM_METADATA_PATH );

        entryDTOs.add( npmEntry );
        entryDTOs.add( npmMetaEntry );

        HistoricalContentDTO contentDTO = new HistoricalContentDTO( "8888", entryDTOs.toArray(
                        new HistoricalEntryDTO[entryDTOs.size()] ) );

        PreSeedConfig preSeedConfig = new PreSeedConfig();
        preSeedConfig.setMainIndy( Optional.of( MAIN_INDY ) );
        HistoricalContentListReader reader = new HistoricalContentListReader( preSeedConfig );

        Map<String, String> paths = reader.readPaths( contentDTO );

        assertThat( paths.size(), equalTo( 1 ) );
        String storePath = MAIN_INDY + "/api/content" + npmEntry.getStorePath();
        assertNotNull( paths.get( storePath + NPM_TARBALL_PATH ) );
        assertThat( paths.get( storePath + NPM_TARBALL_PATH ), equalTo( NPM_TARBALL_PATH ) );

        //npm package metadata will be ignored
        assertNull( paths.get( storePath + NPM_METADATA_PATH ) );
    }
}
