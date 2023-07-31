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
package org.commonjava.indy.service.archive.jaxrs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import io.vertx.core.eventbus.EventBus;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.commonjava.indy.service.archive.controller.ArchiveController;
import org.commonjava.indy.service.archive.model.dto.HistoricalContentDTO;
import org.commonjava.indy.service.archive.util.TransferStreamingOutput;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.annotations.jaxrs.PathParam;
import org.jboss.resteasy.spi.HttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.UriInfo;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Optional;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static javax.ws.rs.core.Response.accepted;
import static javax.ws.rs.core.Response.noContent;
import static org.commonjava.indy.service.archive.jaxrs.ResponseHelper.buildWithZipHeader;
import static org.commonjava.indy.service.archive.jaxrs.ResponseHelper.fromResponse;

@Tag( name = "Archive Management", description = "Resource for managing the historical archive" )
@Path( "/api/archive" )
public class ArchiveManageResources
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ArchiveController controller;

    @Inject
    EventBus bus;

    @Operation( description = "Generate archive based on tracked content" )
    @APIResponse( responseCode = "202", description = "The archive created request is accepted" )
    @RequestBody( description = "The tracked content definition JSON", name = "body", required = true, content = @Content( mediaType = APPLICATION_JSON, example =
                    "{" + "\"buildConfigId\": \"XXX\"," + "\"downloads\":" + "[{" + "    \"storeKey\": \"\","
                                    + "    \"path\": \"\"," + "    \"md5\": \"\"," + "    \"sha256\": \"\","
                                    + "    \"sha1\": \"\"," + "    \"size\": 001" + "  }," + "..."
                                    + "]}", schema = @Schema( implementation = HistoricalContentDTO.class ) ) )
    @POST
    @Path( "generate" )
    @Consumes( APPLICATION_JSON )
    public Uni<Response> create( final @Context UriInfo uriInfo, final @Context HttpRequest request )
    {
        HistoricalContentDTO content;
        try
        {
            content = objectMapper.readValue( request.getInputStream(), HistoricalContentDTO.class );
            if ( content == null )
            {
                final String message = "Failed to read historical content which is empty.";
                logger.error( message );
                return fromResponse( message );
            }
        }
        catch ( final IOException e )
        {
            final String message = "Failed to read historical content file from request body.";
            logger.error( message, e );
            return fromResponse( message );
        }

        controller.generate( content );
        return Uni.createFrom()
                  .item( accepted().type( MediaType.TEXT_PLAIN )
                                   .entity( "Archive created request is accepted." )
                                   .build() );
    }

    @Operation( description = "Get the status of generating archive based on build config Id" )
    @APIResponse( responseCode = "200", description = "Get the status of generating history archive" )
    @APIResponse( responseCode = "404", description = "The status of generating history archive doesn't exist" )
    @GET
    @Path( "status/{buildConfigId}" )
    public Uni<Response> getGenerateStatus( final @PathParam( "buildConfigId" ) String buildConfigId,
                                            final @Context UriInfo uriInfo )
    {
        Response response;
        if ( controller.statusExists( buildConfigId ) )
        {
            String msg = String.format( "Archive generating on build config Id: %s is %s.", buildConfigId,
                                        controller.getStatus( buildConfigId ) );
            response = Response.ok().type( MediaType.TEXT_PLAIN ).entity( msg ).build();
        }
        else
        {
            response = Response.status( NOT_FOUND )
                               .type( MediaType.TEXT_PLAIN )
                               .entity( "Not found process of generating." )
                               .build();
        }
        return Uni.createFrom().item( response );
    }

    @Operation( description = "Get latest historical build archive by buildConfigId" )
    @APIResponse( responseCode = "200", description = "Get the history archive successfully" )
    @APIResponse( responseCode = "404", description = "The history archive doesn't exist" )
    @Path( "{buildConfigId}" )
    @Produces( APPLICATION_OCTET_STREAM )
    @GET
    public Uni<Response> get( final @PathParam( "buildConfigId" ) String buildConfigId, final @Context UriInfo uriInfo )
    {
        Response response;
        try
        {
            Optional<File> target = controller.getArchiveInputStream( buildConfigId );
            if ( target.isPresent() )
            {
                InputStream inputStream = FileUtils.openInputStream( target.get() );
                final ResponseBuilder builder = Response.ok( new TransferStreamingOutput( inputStream ) );
                response = buildWithZipHeader( builder, buildConfigId );
            }
            else
            {
                response = Response.status( NOT_FOUND ).build();
            }
        }
        catch ( final IOException e )
        {
            final String message = "Failed to get historical archive for build config id: " + buildConfigId;
            logger.error( message, e );
            return fromResponse( message );
        }
        return Uni.createFrom().item( response );
    }

    @Operation( description = "Delete the build archive by buildConfigId" )
    @APIResponse( responseCode = "204", description = "The history archive is deleted or doesn't exist" )
    @Path( "{buildConfigId}" )
    @DELETE
    public Uni<Response> delete( final @PathParam( "buildConfigId" ) String buildConfigId,
                                 final @Context UriInfo uriInfo )
    {
        try
        {
            controller.deleteArchive( buildConfigId );
        }
        catch ( final IOException e )
        {
            final String message = "Failed to delete historical archive for build config id: " + buildConfigId;
            logger.error( message, e );
            return fromResponse( message );
        }
        return Uni.createFrom().item( noContent().build() );
    }

    @Operation( description = "Clean up all the temp workplace" )
    @APIResponse( responseCode = "204", description = "The workplace cleanup is finished" )
    @Path( "cleanup" )
    @DELETE
    public Uni<Response> delete( final @Context UriInfo uriInfo )
    {
        try
        {
            controller.cleanup();
        }
        catch ( final IOException e )
        {
            final String message = "Failed to clean up all the temp workplace";
            logger.error( message, e );
            return fromResponse( message );
        }
        return Uni.createFrom().item( noContent().build() );
    }
}
