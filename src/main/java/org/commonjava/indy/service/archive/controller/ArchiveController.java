/**
 * Copyright (C) 2011-2021 Red Hat, Inc. (https://github.com/Commonjava/indy)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.indy.service.archive.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import java.util.zip.ZipOutputStream;

@ApplicationScoped
public class ArchiveController
{

    public final static String EVENT_GENERATE_ARCHIVE = "generate-archive";

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private final String CONTENT_DIR = "/content";

    private final String ARCHIVE_DIR = "/archive";

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
    public void init() throws IOException
    {
        int threads = 4 * Runtime.getRuntime().availableProcessors();
        executorService = Executors.newFixedThreadPool( threads, ( final Runnable r ) -> {
            final Thread t = new Thread( r );
            t.setName( "Content-Download" );
            t.setDaemon( true );
            return t;
        } );

        final PoolingHttpClientConnectionManager ccm = new PoolingHttpClientConnectionManager();
        ccm.setMaxTotal( 500 );

        RequestConfig rc = RequestConfig.custom().build();
        client = HttpClients.custom().setConnectionManager( ccm ).setDefaultRequestConfig( rc ).build();

        String storeDir = preSeedConfig.storageDir.orElse( "data" );
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
        ExecutorService generateExecutor = Executors.newFixedThreadPool( 2, ( final Runnable r ) -> {
            final Thread t = new Thread( r );
            t.setName( "Archive-Generate" );
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
            archive = generateArchive( content );
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

    public Optional<File> getArchiveInputStream( final String buildConfigId ) throws IOException
    {
        File targetDir = new File( archiveDir );
        if ( !targetDir.exists() )
        {
            return Optional.empty();
        }

        List<File> contents = walkAllFiles( archiveDir );
        for ( File content : contents )
        {
            if ( content.getName().equals( buildConfigId + ARCHIVE_SUFFIX ) )
            {
                return Optional.of( content );
            }
        }
        return Optional.empty();
    }

    public void deleteArchive( final String buildConfigId ) throws IOException
    {
        File targetDir = new File( archiveDir );
        if ( !targetDir.exists() )
        {
            return;
        }
        List<File> contents = walkAllFiles( archiveDir );
        for ( File content : contents )
        {
            if ( content.getName().equals( buildConfigId + ARCHIVE_SUFFIX ) )
            {
                content.delete();
            }
        }
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
                    throws InterruptedException, ExecutionException
    {
        BasicCookieStore cookieStore = new BasicCookieStore();
        ExecutorCompletionService<Boolean> executor = new ExecutorCompletionService<>( executorService );

        String contentBuildDir = String.format( "%s/%s", contentDir, content.getBuildConfigId() );
        File dir = new File( contentBuildDir );
        dir.delete();

        fileTrackedContent( contentBuildDir, content );

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

    private Optional<File> generateArchive( final HistoricalContentDTO content ) throws IOException
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
        List<File> artifacts = walkAllFiles( contentBuildDir );

        byte[] buffer = new byte[1024];
        for ( File artifact : artifacts )
        {
            logger.trace( "Adding {} to archive {} in folder {}", artifact.getName(), part.getName(), archiveDir );
            FileInputStream fis = new FileInputStream( artifact );
            String entryPath = artifact.getPath().split( contentBuildDir )[1];

            zip.putNextEntry( new ZipEntry( entryPath ) );

            int length;
            while ( ( length = fis.read( buffer ) ) > 0 )
            {
                zip.write( buffer, 0, length );
            }
            zip.closeEntry();
            fis.close();
        }
        zip.close();

        //clean obsolete build contents
        for ( File artifact : artifacts )
        {
            artifact.delete();
        }
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
        return true;
    }

    private List<File> walkAllFiles( String path ) throws IOException
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

    private Callable<Boolean> download( String contentBuildDir, final String path, final String filePath,
                                        final CookieStore cookieStore )
    {
        return () -> {
            Thread.currentThread().setName( "download--" + path );

            final File target = new File( contentBuildDir, filePath );
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
                    return true;
                }
                else if ( statusCode == 404 )
                {
                    logger.trace( "<<<Not Found path: {}", path );
                    return false;
                }
                else
                {
                    logger.trace( "<<<Error path: {}", path );
                    return false;
                }
            }
            catch ( final Exception e )
            {
                e.printStackTrace();
                logger.trace( "Download failed for path: {}", path );
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
                continue;
            }
            else if ( content.getName().endsWith( ARCHIVE_SUFFIX ) )
            {
                treated.put( content.getName().split( ARCHIVE_SUFFIX )[0], ArchiveStatus.completed.getArchiveStatus() );
                continue;
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
