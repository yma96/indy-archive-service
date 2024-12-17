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
package org.commonjava.indy.service.archive.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.CookieStore;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.commonjava.indy.service.archive.config.PreSeedConfig;
import org.commonjava.indy.service.archive.model.ArchiveStatus;
import org.commonjava.indy.service.archive.model.dto.HistoricalContentDTO;
import org.commonjava.indy.service.archive.model.dto.HistoricalEntryDTO;
import org.commonjava.indy.service.archive.util.HistoricalContentListReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@ApplicationScoped
public class ArchiveController
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    public final static String EVENT_GENERATE_ARCHIVE = "generate-archive";

    public final static String CONTENT_DIR = "/content";

    public final static String ARCHIVE_DIR = "/archive";

    private final String ARCHIVE_SUFFIX = ".zip";

    private final String PART_SUFFIX = ".part";

    private final String PART_ARCHIVE_SUFFIX = PART_SUFFIX + ARCHIVE_SUFFIX;

    private static final Set<String> CHECKSUMS = Collections.unmodifiableSet( new HashSet<String>()
    {
        {
            add( ".sha1" );
            add( ".sha256" );
            add( ".md5" );
        }
    } );

    private static final Map<String, Object> buildConfigLocks = new ConcurrentHashMap<>();

    @Inject
    HistoricalContentListReader reader;

    @Inject
    PreSeedConfig preSeedConfig;

    @Inject
    ObjectMapper objectMapper;

    private ExecutorService downloadExecutor;

    private ExecutorService generateExecutor;

    private CloseableHttpClient client;

    private String contentDir;

    private String archiveDir;

    private final Map<String, String> treated = new HashMap<>();

    @PostConstruct
    public void init()
            throws IOException
    {
        int threads = preSeedConfig.threadMultiplier().orElse( 4 ) * Runtime.getRuntime().availableProcessors();
        downloadExecutor = Executors.newFixedThreadPool( threads, ( final Runnable r ) -> {
            final Thread t = new Thread( r );
            t.setName( "Download-" + t.getName() );
            t.setDaemon( true );
            return t;
        } );
        generateExecutor = Executors.newFixedThreadPool( threads, ( final Runnable r ) -> {
            final Thread t = new Thread( r );
            t.setName( "Generate-" + t.getName() );
            t.setDaemon( true );
            return t;
        } );

        final PoolingHttpClientConnectionManager ccm = new PoolingHttpClientConnectionManager();
        ccm.setMaxTotal( 500 );
        RequestConfig rc = RequestConfig.custom().build();
        client = HttpClients.custom().setConnectionManager( ccm ).setDefaultRequestConfig( rc ).build();

        String storeDir = preSeedConfig.storageDir().orElse( "data" );
        contentDir = String.format( "%s%s", storeDir, CONTENT_DIR );
        archiveDir = String.format( "%s%s", storeDir, ARCHIVE_DIR );
        restoreGenerateStatusFromDisk();
    }

    @PreDestroy
    public void destroy()
    {
        IOUtils.closeQuietly( client, null );
    }

    public void generate( HistoricalContentDTO content )
    {
        String buildConfigId = content.getBuildConfigId();
        Object lock = buildConfigLocks.computeIfAbsent( buildConfigId, k -> new Object() );
        synchronized ( lock )
        {
            if ( isInProgress( buildConfigId ) )
            {
                logger.info( "There is already generation process in progress for buildConfigId {}, this request will skip.",
                             buildConfigId );
                // Conflicted generation, just return immediately
                return;
            }

            recordInProgress( buildConfigId );
            Future<?> future = generateExecutor.submit( () -> {
                try
                {
                    doGenerate( content );
                    recordCompleted( buildConfigId );
                }
                catch ( Exception e )
                {
                    // If error happens on generation, remove the status to make sure following generation
                    removeStatus( buildConfigId );
                    cleanupBCWorkspace( buildConfigId );
                    logger.error( "Generation failed for buildConfigId {}", buildConfigId, e );
                }
                finally
                {
                    buildConfigLocks.remove( buildConfigId );
                    logger.info( "<<<Lock released for buildConfigId {}", buildConfigId );
                }
            } );
            try
            {
                future.get( preSeedConfig.generationTimeoutMinutes().orElse( 60 ), TimeUnit.MINUTES );
            }
            catch ( TimeoutException e )
            {
                // If timeout happens on generation, cancel and remove the status to make sure following generation
                future.cancel( true );
                removeStatus( buildConfigId );
                cleanupBCWorkspace( buildConfigId );
                logger.error( "Generation timeout for buildConfigId {}", buildConfigId, e );
            }
            catch ( InterruptedException | ExecutionException e )
            {
                // If future task level error happens on generation, cancel and remove the status to make sure following generation
                future.cancel( true );
                removeStatus( buildConfigId );
                cleanupBCWorkspace( buildConfigId );
                logger.error( "Generation future task level failed for buildConfigId {}", buildConfigId, e );
            }
        }
    }

    protected Boolean doGenerate( HistoricalContentDTO content )
    {
        String buildConfigId = content.getBuildConfigId();
        logger.info( "<<<Handle generate event: {}, build config id: {}", EVENT_GENERATE_ARCHIVE, buildConfigId );

        Map<String, HistoricalEntryDTO> entryDTOs = reader.readEntries( content );
        Map<String, String> downloadPaths = new HashMap<>();
        entryDTOs.forEach( ( key, value ) -> downloadPaths.put( key, value.getPath() ) );

        Optional<File> archive;
        try
        {
            downloadArtifacts( entryDTOs, downloadPaths, content );
            archive = generateArchive( new ArrayList<>( downloadPaths.values() ), content );
        }
        catch ( final InterruptedException e )
        {
            logger.error( "Artifacts downloading is interrupted, build config id: " + buildConfigId, e );
            return false;
        }
        catch ( final ExecutionException e )
        {
            logger.error( "Artifacts download execution manager failed, build config id: " + buildConfigId, e );
            return false;
        }
        catch ( final IOException e )
        {
            logger.error( "Failed to generate historical archive from content, build config id: " + buildConfigId, e );
            return false;
        }

        boolean created = false;
        if ( archive.isPresent() && archive.get().exists() )
        {
            created = renderArchive( archive.get(), buildConfigId );
        }

        return created;
    }

    public Optional<File> getArchiveInputStream( final String buildConfigId )
            throws IOException
    {
        File zip = new File( archiveDir, buildConfigId + ARCHIVE_SUFFIX );
        if ( zip.exists() )
        {
            return Optional.of( zip );
        }
        return Optional.empty();
    }

    public void deleteArchive( final String buildConfigId )
            throws IOException
    {
        File zip = new File( archiveDir, buildConfigId + ARCHIVE_SUFFIX );
        if ( zip.exists() )
        {
            Files.delete( zip.toPath() );
        }
        logger.info( "Historical archive for build config id: {} is deleted.", buildConfigId );
    }

    public void deleteArchiveWithChecksum( final String buildConfigId, final String checksum )
            throws IOException
    {
        logger.info( "Start to delete archive with checksum validation, buildConfigId {}, checksum {}", buildConfigId,
                     checksum );
        File zip = new File( archiveDir, buildConfigId + ARCHIVE_SUFFIX );
        if ( !zip.exists() )
        {
            return;
        }

        try (FileInputStream fis = new FileInputStream( zip ))
        {
            String storedChecksum = DigestUtils.sha256Hex( fis );

            // only delete the zip once checksum is matched
            if ( storedChecksum.equals( checksum ) )
            {
                Files.delete( zip.toPath() );
                logger.info( "Historical archive for build config id: {} is deleted, checksum {}.", buildConfigId,
                             storedChecksum );
            }
            else
            {
                logger.info( "Don't delete the {} zip, transferred checksum {}, but stored checksum {}.", buildConfigId,
                             checksum, storedChecksum );
            }
        }
    }

    public void cleanup()
            throws IOException
    {
        File dir = new File( contentDir );
        List<File> artifacts = walkAllFiles( contentDir );
        for ( File artifact : artifacts )
        {
            artifact.delete();
        }
        dir.delete();
        logger.info( "Temporary workplace {} cleanup is finished.", contentDir );
    }

    public void cleanupBCWorkspace( String buildConfigId )
    {
        String path = String.format( "%s/%s", contentDir, buildConfigId );
        try
        {
            File dir = new File( path );
            List<File> artifacts = walkAllFiles( path );
            for ( File artifact : artifacts )
            {
                artifact.delete();
            }
            dir.delete();
            logger.info( "Temporary workplace {} cleanup is finished.", path );
        }
        catch ( IOException e )
        {
            logger.error( "Failed to walk files on path {}", path, e );
        }
    }

    public boolean statusExists( final String buildConfigId )
    {
        return treated.containsKey( buildConfigId );
    }

    public String getStatus( final String buildConfigId )
    {
        return treated.get( buildConfigId );
    }

    public boolean isInProgress( final String buildConfigId )
    {
        return statusExists( buildConfigId ) && getStatus( buildConfigId ).equals(
                ArchiveStatus.inProgress.getArchiveStatus() );
    }

    private void downloadArtifacts( final Map<String, HistoricalEntryDTO> entryDTOs,
                                    final Map<String, String> downloadPaths, final HistoricalContentDTO content )
            throws InterruptedException, ExecutionException, IOException
    {
        BasicCookieStore cookieStore = new BasicCookieStore();
        ExecutorCompletionService<Boolean> executor = new ExecutorCompletionService<>( downloadExecutor );

        String buildConfigId = content.getBuildConfigId();
        String contentBuildDir = String.format( "%s/%s", contentDir, buildConfigId );
        // cleanup the build config content dir before download
        cleanupBCWorkspace( buildConfigId );

        HistoricalContentDTO originalTracked = unpackHistoricalArchive( contentBuildDir, buildConfigId );
        Map<String, List<String>> originalChecksumsMap = new HashMap<>();
        if ( originalTracked != null )
        {
            logger.trace( "originalChecksumsMap generated for {}", buildConfigId );
            Map<String, HistoricalEntryDTO> originalEntries = reader.readEntries( originalTracked );
            originalEntries.forEach( ( key, entry ) -> originalChecksumsMap.put( key, new ArrayList<>(
                    Arrays.asList( entry.getSha1(), entry.getSha256(), entry.getMd5() ) ) ) );
        }

        for ( String path : downloadPaths.keySet() )
        {
            String filePath = downloadPaths.get( path );
            HistoricalEntryDTO entry = entryDTOs.get( path );
            List<String> checksums =
                    new ArrayList<>( Arrays.asList( entry.getSha1(), entry.getSha256(), entry.getMd5() ) );
            List<String> originalChecksums = originalChecksumsMap.get( path );
            executor.submit( download( contentBuildDir, path, filePath, checksums, originalChecksums, cookieStore ) );
        }
        int success = 0;
        int failed = 0;
        for ( int i = 0; i < downloadPaths.size(); i++ )
        {
            if ( executor.take().get() )
            {
                success++;
            }
            else
            {
                failed++;
            }
        }
        // file the latest tracked json at the end
        fileTrackedContent( contentBuildDir, content );
        logger.info( "Artifacts download completed, success:{}, failed:{}", success, failed );
    }

    private Optional<File> generateArchive( final List<String> paths, final HistoricalContentDTO content )
            throws IOException
    {
        String buildConfigId = content.getBuildConfigId();
        String contentBuildDir = String.format( "%s/%s", contentDir, buildConfigId );
        File dir = new File( contentBuildDir );
        if ( !dir.exists() )
        {
            return Optional.empty();
        }

        final File part = new File( archiveDir, buildConfigId + PART_ARCHIVE_SUFFIX );
        part.getParentFile().mkdirs();

        logger.info( "Writing archive to: '{}'", part.getAbsolutePath() );
        ZipOutputStream zip = new ZipOutputStream( new FileOutputStream( part ) );

        // adding tracked file
        paths.add( buildConfigId );
        byte[] buffer = new byte[1024];
        for ( String path : paths )
        {
            logger.trace( "Adding {} to archive {} in folder {}", path, part.getName(), archiveDir );
            File artifact = new File( contentBuildDir, path );
            if ( !artifact.exists() )
            {
                logger.warn( "No such file found during zip entry put {}", artifact.getAbsolutePath() );
                continue;
            }
            logger.trace( "Put file {} in the zip", artifact.getAbsolutePath() );
            FileInputStream fis = new FileInputStream( artifact );

            zip.putNextEntry( new ZipEntry( path ) );

            int length;
            while ( ( length = fis.read( buffer ) ) > 0 )
            {
                zip.write( buffer, 0, length );
            }
            zip.closeEntry();
            fis.close();
        }
        zip.close();

        for ( String path : paths )
        {
            logger.debug( "Clean temporary content workplace dir {}, path {}", contentBuildDir, path );
            File artifact = new File( contentBuildDir, path );
            artifact.delete();
        }
        logger.debug( "Clean temporary content workplace dir {}", contentBuildDir );
        dir.delete();

        return Optional.of( part );
    }

    private boolean renderArchive( File part, final String buildConfigId )
    {
        final File target = new File( archiveDir, buildConfigId + ARCHIVE_SUFFIX );
        try
        {
            if ( target.exists() )
            {
                Files.delete( target.toPath() );
            }
        }
        catch ( final SecurityException | IOException e )
        {
            logger.error( "Failed to delete the obsolete archive file {}", target.getPath(), e );
            return false;
        }
        target.getParentFile().mkdirs();
        part.renameTo( target );
        logger.info( "Writing archive finished: '{}'", target.getAbsolutePath() );
        return true;
    }

    public List<File> walkAllFiles( String path )
            throws IOException
    {
        List<File> contents = new ArrayList<>();
        Path directoryPath = Paths.get( path );
        if ( Files.exists( directoryPath ) )
        {
            contents = Files.walk( directoryPath )
                            .filter( Files::isRegularFile )
                            .map( Path::toFile )
                            .collect( Collectors.toList() );
        }
        return contents;
    }

    private void fileTrackedContent( String contentBuildDir, final HistoricalContentDTO content )
    {
        File tracked = new File( contentBuildDir, content.getBuildConfigId() );
        tracked.getParentFile().mkdirs();

        ByteArrayInputStream input = null;
        try (FileOutputStream out = new FileOutputStream( tracked ))
        {
            String json = objectMapper.writeValueAsString( content );
            input = new ByteArrayInputStream( json.getBytes() );
            IOUtils.copy( input, out );
        }
        catch ( final IOException e )
        {
            logger.error( "Failed to file tracked content, path: " + tracked.getPath(), e );
        }
        finally
        {
            IOUtils.closeQuietly( input, null );
        }
    }

    private HistoricalContentDTO unpackHistoricalArchive( String contentBuildDir, String buildConfigId )
            throws IOException
    {
        final File archive = new File( archiveDir, buildConfigId + ARCHIVE_SUFFIX );
        if ( !archive.exists() )
        {
            logger.debug( "Don't find historical archive for buildConfigId: {}.", buildConfigId );
            return null;
        }

        logger.info( "Start unpacking historical archive for buildConfigId: {}.", buildConfigId );
        ZipInputStream inputStream = new ZipInputStream( new FileInputStream( archive ) );
        ZipEntry entry;
        while ( ( entry = inputStream.getNextEntry() ) != null )
        {
            logger.trace( "entry path:" + entry.getName() );
            File outputFile = new File( contentBuildDir, entry.getName() );
            outputFile.getParentFile().mkdirs();
            try ( FileOutputStream outputStream = new FileOutputStream( outputFile ) )
            {
                inputStream.transferTo( outputStream );
            }

        }
        inputStream.close();

        File originalTracked = new File( contentBuildDir, buildConfigId );
        if ( originalTracked.exists() )
        {
            return objectMapper.readValue( originalTracked, HistoricalContentDTO.class );
        }
        else
        {
            logger.debug( "No tracked json file found after zip unpack for buildConfigId {}", buildConfigId );
            return null;
        }
    }

    private boolean validateChecksum( final String filePath, final List<String> current, final List<String> original )
    {
        if ( CHECKSUMS.stream().anyMatch( suffix -> filePath.toLowerCase().endsWith( suffix ) ) )
        {
            // skip to validate checksum files
            return false;
        }
        if ( original == null || original.isEmpty() || original.stream().allMatch( Objects::isNull ) )
        {
            return false;
        }
        // once sha1 is matched, skip downloading
        if ( original.get( 0 ) != null && original.get( 0 ).equals( current.get( 0 ) ) )
        {
            return true;
        }
        // once sha256 is matched, skip downloading
        if ( original.get( 1 ) != null && original.get( 1 ).equals( current.get( 1 ) ) )
        {
            return true;
        }
        // once md5 is matched, skip downloading
        return original.get( 2 ) != null && original.get( 2 ).equals( current.get( 2 ) );
    }

    private Callable<Boolean> download( final String contentBuildDir, final String path, final String filePath,
                                        final List<String> checksums, final List<String> originalChecksums,
                                        final CookieStore cookieStore )
    {
        return () -> {
            final File target = new File( contentBuildDir, filePath );

            if ( target.exists() && validateChecksum( filePath, checksums, originalChecksums ) )
            {
                logger.debug(
                        "<<<Already existed in historical archive, and checksum matches, skip downloading, path: {}.",
                        path );
                return true;
            }
            final File dir = target.getParentFile();
            dir.mkdirs();
            final File part = new File( dir, target.getName() + PART_SUFFIX );

            final HttpClientContext context = new HttpClientContext();
            context.setCookieStore( cookieStore );
            final HttpGet request = new HttpGet( path );
            InputStream input = null;
            if ( target.exists() )
            {
                // prevent the obsolete file still existed caused by http error
                target.delete();
            }
            try
            {
                CloseableHttpResponse response = client.execute( request, context );
                int statusCode = response.getStatusLine().getStatusCode();
                if ( statusCode == 200 )
                {
                    try (FileOutputStream out = new FileOutputStream( part ))
                    {
                        input = response.getEntity().getContent();
                        IOUtils.copy( input, out );
                    }
                    part.renameTo( target );
                    logger.trace( "<<<Downloaded path: {}", path );
                    return true;
                }
                else if ( statusCode == 404 )
                {
                    logger.warn( "<<<Not Found path: {}", path );
                    return false;
                }
                else
                {
                    logger.warn( "<<<Error path: {}, statusCode: {}, protocol: {}, reason:{}.", path, statusCode,
                                 response.getStatusLine().getProtocolVersion().getProtocol(),
                                 response.getStatusLine().getReasonPhrase() );
                    return false;
                }
            }
            catch ( final Exception e )
            {
                logger.error( "Download failed for path: " + path, e );
            }
            finally
            {
                request.releaseConnection();
                request.reset();
                IOUtils.closeQuietly( input, null );
            }
            return false;
        };
    }

    private void restoreGenerateStatusFromDisk() throws IOException
    {
        File targetDir = new File( archiveDir );
        if ( !targetDir.exists() )
        {
            return;
        }
        List<File> contents = walkAllFiles( archiveDir );
        for ( File content : contents )
        {
            if ( content.getName().endsWith( ARCHIVE_SUFFIX ) )
            {
                treated.put( content.getName().split( ARCHIVE_SUFFIX )[0], ArchiveStatus.completed.getArchiveStatus() );
            }
        }
    }

    private void recordInProgress( final String buildConfigId )
    {
        treated.remove( buildConfigId );
        treated.put( buildConfigId, ArchiveStatus.inProgress.getArchiveStatus() );
    }

    private void recordCompleted( final String buildConfigId )
    {
        treated.remove( buildConfigId );
        treated.put( buildConfigId, ArchiveStatus.completed.getArchiveStatus() );
    }

    private void removeStatus( final String buildConfigId )
    {
        treated.remove( buildConfigId );
    }
}
