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
package org.commonjava.indy.service.archive.schedule;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.commonjava.indy.service.archive.config.PreSeedConfig;
import org.commonjava.indy.service.archive.controller.ArchiveController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.commonjava.indy.service.archive.controller.ArchiveController.ARCHIVE_DIR;

@ApplicationScoped
public class ArchiveFileCleanup
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    ArchiveController controller;

    @Inject
    PreSeedConfig preSeedConfig;

    @Scheduled( delayed = "1h", every = "P1D" )
    void cleanup()
            throws IOException
    {
        logger.info( "<<<Start not used archive files cleanup." );
        String storeDir = preSeedConfig.storageDir().orElse( "data" );
        String archiveDir = String.format( "%s%s", storeDir, ARCHIVE_DIR );

        List<File> artifacts = controller.walkAllFiles( archiveDir );
        for ( File artifact : artifacts )
        {
            BasicFileAttributes attrs = Files.readAttributes( artifact.toPath(), BasicFileAttributes.class );
            Long notUsedDays = preSeedConfig.notUsedDaysCleanup().orElse( null );
            if ( notUsedDays == null )
            {
                return;
            }
            Long days = TimeUnit.MILLISECONDS.toDays( System.currentTimeMillis() - attrs.lastAccessTime().toMillis() );
            logger.debug( "file: {}, not used days: {}.", artifact.getPath(), days );
            if ( days >= notUsedDays )
            {
                artifact.delete();
                logger.info(
                        "<<<Not used archive files cleanup is finished for archive file: {}, not used days: {}, config: {}.",
                        artifact.getPath(), days, notUsedDays );
            }
        }
    }
}
