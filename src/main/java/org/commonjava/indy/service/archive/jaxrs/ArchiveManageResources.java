/**
 * Copyright (C) 2011-2021 Red Hat, Inc. (https://github.com/Commonjava/service-parent)
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
package org.commonjava.indy.service.archive.jaxrs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.commonjava.indy.service.archive.controller.ArchiveController;
import org.commonjava.indy.service.archive.model.dto.HistoricalContentDTO;
import org.commonjava.indy.service.archive.util.HistoricalContentListReader;
import org.commonjava.indy.service.archive.util.TransferStreamingOutput;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.resteasy.spi.HttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Map;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM;
import static javax.ws.rs.core.Response.noContent;

@Path( "/api/archive" )
public class ArchiveManageResources
{
    @Inject
    ObjectMapper objectMapper;

    @Inject
    HistoricalContentListReader reader;

    @Inject
    ArchiveController controller;

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Operation( description = "Generate archive based on tracked content" )
    @APIResponse( responseCode = "201", description = "The archive is created successfully" )
    @RequestBody( description = "The tracked content definition JSON", name = "body", required = true,
            content = @Content( schema = @Schema( implementation = HistoricalContentDTO.class ) ) )
    @POST
    @Path( "generate" )
    @Consumes( APPLICATION_JSON )
    public Response create( final @Context UriInfo uriInfo, final @Context HttpRequest request )
    {
        String json = null;
        HistoricalContentDTO content = null;
        File zip = null;
        try
        {
            json = IOUtils.toString( request.getInputStream(), Charset.defaultCharset() );
            content = objectMapper.readValue( json, HistoricalContentDTO.class );
        }
        catch ( final IOException e )
        {
            final String message = "Failed to read historical content file from request body.";
            logger.error( message, e );
            ResponseBuilder builder = Response.status( Status.INTERNAL_SERVER_ERROR ).type( MediaType.TEXT_PLAIN ).entity( message );
            return builder.build();
        }
        if ( content != null )
        {
            Map<String, String> downloadPaths = reader.readPaths( content );
            controller.downloadArtifacts( downloadPaths, content );
        }
        try
        {
            zip = controller.generateArchiveZip( content );
        }
        catch ( final IOException e )
        {
            final String message = "Failed to generate historical archive from content.";
            logger.error( message, e );
            ResponseBuilder builder = Response.status( Status.INTERNAL_SERVER_ERROR ).type( MediaType.TEXT_PLAIN ).entity( message );
            return builder.build();
        }
        finally
        {
            controller.renderArchive( zip, content.getBuildConfigId(), content.getTrackId() );
        }

        return Response.created( uriInfo.getRequestUri() ).build();
    }

    @Operation( description = "Get latest historical build archive by buildConfigId" )
    @APIResponse( responseCode = "200", description = "Get the history archive successfully" )
    @APIResponse( responseCode = "204", description = "The history archive doesn't exist" )
    @Path( "{buildConfigId}" )
    @Produces ( APPLICATION_OCTET_STREAM )
    @GET
    public Response get( final @PathParam( "buildConfigId" ) String buildConfigId, final @Context UriInfo uriInfo )
    {
        Response response = null;
        try
        {
            File target = controller.getArchiveInputStream( buildConfigId );
            if ( target != null )
            {
                InputStream inputStream = FileUtils.openInputStream( target );
                final ResponseBuilder builder = Response.ok( new TransferStreamingOutput( inputStream ) );

                response = buildWithHeader( builder, buildConfigId );
            }
        }
        catch ( IOException e )
        {
            final String message = "Failed to get historical archive for build config Id " + buildConfigId;
            logger.error( message, e );
            ResponseBuilder builder = Response.status( Status.INTERNAL_SERVER_ERROR ).type( MediaType.TEXT_PLAIN ).entity( message );
            return builder.build();
        }
        return response;
    }

    @Operation( description = "Delete the build archive by buildConfigId" )
    @APIResponse( responseCode = "204", description = "The history archive is deleted or doesn't exist" )
    @Path( "{buildConfigId}" )
    @DELETE
    public Response delete( final @PathParam( "buildConfigId" ) String buildConfigId, final @Context UriInfo uriInfo )
    {
        try
        {
            controller.deleteArchive( buildConfigId );
        }
        catch ( IOException e )
        {
            final String message = "Failed to delete historical archive for build config Id " + buildConfigId;
            logger.error( message, e );
            ResponseBuilder builder = Response.status( Status.INTERNAL_SERVER_ERROR ).type( MediaType.TEXT_PLAIN ).entity( message );
            return builder.build();
        }
        return noContent().build();
    }

    private Response buildWithHeader( ResponseBuilder builder, final String buildConfigId )
    {
        StringBuilder header = new StringBuilder();
        header.append( "attachment;" )
                .append( "filename=" )
                .append( buildConfigId )
                .append( ".tar.gz" );
        return builder.header( "Content-Disposition", header.toString() ).build();
    }
}
