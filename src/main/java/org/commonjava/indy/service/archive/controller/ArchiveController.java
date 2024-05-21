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
package org.commonjava.indy.service.archive.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@ApplicationScoped
public class ArchiveController
{

    public final static String EVENT_GENERATE_ARCHIVE = "generate-archive";

    public final static String CONTENT_DIR = "/content";

    public final static String ARCHIVE_DIR = "/archive";

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private final String ARCHIVE_SUFFIX = ".zip";

    private final String PART_SUFFIX = ".part";

    private final String PART_ARCHIVE_SUFFIX = PART_SUFFIX + ARCHIVE_SUFFIX;

    @Inject
    HistoricalContentListReader reader;

    @Inject
    PreSeedConfig preSeedConfig;

    @Inject
    ObjectMapper objectMapper;

    private ExecutorService executorService;

    private CloseableHttpClient client;

    private String contentDir;

    private String archiveDir;

    private final Map<String, String> treated = new HashMap<>();

    @PostConstruct
    public void init()
            throws IOException
    {
        int threads = 4 * Runtime.getRuntime().availableProcessors();
        executorService = Executors.newFixedThreadPool( threads, ( final Runnable r ) -> {
            final Thread t = new Thread( r );
            t.setName( "Download-" + t.getName() );
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
        int threads = 4 * Runtime.getRuntime().availableProcessors();
        ExecutorService generateExecutor = Executors.newFixedThreadPool( threads, ( final Runnable r ) -> {
            final Thread t = new Thread( r );
            t.setName( "Generate-" + t.getName() );
            t.setDaemon( true );
            return t;
        } );
        generateExecutor.execute( () -> doGenerate( content ) );
    }

    protected Boolean doGenerate( HistoricalContentDTO content )
    {
        logger.info( "Handle generate event: {}, build config id: {}", EVENT_GENERATE_ARCHIVE,
                     content.getBuildConfigId() );
        recordInProgress( content.getBuildConfigId() );

        Map<String, String> downloadPaths = reader.readPaths( content );
        Optional<File> archive;
        try
        {
            downloadArtifacts( downloadPaths, content );
            archive = generateArchive( new ArrayList<>( downloadPaths.values() ), content );
        }
        catch ( final InterruptedException e )
        {
            logger.error( "Artifacts downloading is interrupted, build config id: " + content.getBuildConfigId(), e );
            return false;
        }
        catch ( final ExecutionException e )
        {
            logger.error( "Artifacts download execution manager failed, build config id: " + content.getBuildConfigId(),
                          e );
            return false;
        }
        catch ( final IOException e )
        {
            logger.error( "Failed to generate historical archive from content, build config id: "
                                          + content.getBuildConfigId(), e );
            return false;
        }

        boolean created = false;
        if ( archive.isPresent() && archive.get().exists() )
        {
            created = renderArchive( archive.get(), content.getBuildConfigId() );
        }

        recordCompleted( content.getBuildConfigId() );
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
            zip.delete();
        }
        logger.info( "Historical archive for build config id: {} is deleted.", buildConfigId );
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
        logger.info( "Temporary workplace cleanup is finished." );
    }

    public boolean statusExists( final String buildConfigId )
    {
        return treated.containsKey( buildConfigId );
    }

    public String getStatus( String buildConfigId )
    {
        return treated.get( buildConfigId );
    }

    private void downloadArtifacts( final Map<String, String> downloadPaths, final HistoricalContentDTO content )
            throws InterruptedException, ExecutionException, IOException
    {
        BasicCookieStore cookieStore = new BasicCookieStore();
        ExecutorCompletionService<Boolean> executor = new ExecutorCompletionService<>( executorService );

        String contentBuildDir = String.format( "%s/%s", contentDir, content.getBuildConfigId() );
        File dir = new File( contentBuildDir );
        dir.delete();

        fileTrackedContent( contentBuildDir, content );
        unpackHistoricalArchive( contentBuildDir, content.getBuildConfigId() );

        for ( String path : downloadPaths.keySet() )
        {
            String filePath = downloadPaths.get( path );
            executor.submit( download( contentBuildDir, path, filePath, cookieStore ) );
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
        logger.info( "Artifacts download completed, success:{}, failed:{}", success, failed );
    }

    private Optional<File> generateArchive( final List<String> paths, final HistoricalContentDTO content )
            throws IOException
    {
        String contentBuildDir = String.format( "%s/%s", contentDir, content.getBuildConfigId() );
        File dir = new File( contentBuildDir );
        if ( !dir.exists() )
        {
            return Optional.empty();
        }

        final File part = new File( archiveDir, content.getBuildConfigId() + PART_ARCHIVE_SUFFIX );
        part.getParentFile().mkdirs();

        logger.info( "Writing archive to: '{}'", part.getAbsolutePath() );
        ZipOutputStream zip = new ZipOutputStream( new FileOutputStream( part ) );

        // adding tracked file
        paths.add( content.getBuildConfigId() );
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
            e.printStackTrace();
            logger.error( "Failed to delete the obsolete archive file {}", target.getPath() );
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
        List<File> contents = Files.walk( Paths.get( path ) )
                                   .filter( Files::isRegularFile )
                                   .map( Path::toFile )
                                   .collect( Collectors.toList() );
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

    private void unpackHistoricalArchive( String contentBuildDir, String buildConfigId )
            throws IOException
    {
        final File archive = new File( archiveDir, buildConfigId + ARCHIVE_SUFFIX );
        if ( !archive.exists() )
        {
            logger.debug( "Don't find historical archive for buildConfigId: {}.", buildConfigId );
            return;
        }

        logger.info( "Start unpacking historical archive for buildConfigId: {}.", buildConfigId );
        ZipInputStream inputStream = new ZipInputStream( new FileInputStream( archive ) );
        ZipEntry entry;
        while ( ( entry = inputStream.getNextEntry() ) != null )
        {
            File outputFile = new File( contentBuildDir, entry.getName() );
            outputFile.getParentFile().mkdirs();
            try ( FileOutputStream outputStream = new FileOutputStream( outputFile ) )
            {
                inputStream.transferTo( outputStream );
            }

        }
        inputStream.close();
    }

    private Callable<Boolean> download( String contentBuildDir, final String path, final String filePath,
                                        final CookieStore cookieStore )
    {
        return () -> {
            final File target = new File( contentBuildDir, filePath );
            if ( target.exists() )
            {
                logger.trace( "<<<Already existed in historical archive, skip downloading, path: {}.", path );
                return true;
            }
            final File dir = target.getParentFile();
            dir.mkdirs();
            final File part = new File( dir, target.getName() + PART_SUFFIX );

            final HttpClientContext context = new HttpClientContext();
            context.setCookieStore( cookieStore );
            final HttpGet request = new HttpGet( path );
            InputStream input = null;
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
            if ( content.getName().endsWith( PART_ARCHIVE_SUFFIX ) )
            {
                treated.put( content.getName().split( PART_ARCHIVE_SUFFIX )[0],
                             ArchiveStatus.inProgress.getArchiveStatus() );
            }
            else if ( content.getName().endsWith( ARCHIVE_SUFFIX ) )
            {
                treated.put( content.getName().split( ARCHIVE_SUFFIX )[0], ArchiveStatus.completed.getArchiveStatus() );
            }
        }
    }

    private void recordInProgress( String buildConfigId )
    {
        treated.remove( buildConfigId );
        treated.put( buildConfigId, ArchiveStatus.inProgress.getArchiveStatus() );
    }

    private void recordCompleted( String buildConfigId )
    {
        treated.remove( buildConfigId );
        treated.put( buildConfigId, ArchiveStatus.completed.getArchiveStatus() );
    }
}
