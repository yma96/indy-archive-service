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
        logger.info( "Start not used archive files cleanup." );
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
                        "Not used archive files cleanup is finished for archive file: {}, not used days: {}, config: {}.",
                        artifact.getPath(), days, notUsedDays );
            }
        }
    }
}
