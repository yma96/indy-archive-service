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

import io.smallrye.mutiny.Uni;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;

import static javax.ws.rs.core.Response.serverError;

@ApplicationScoped
public class ResponseHelper
{

    public static Uni<Response> fromResponse( String message )
    {
        return Uni.createFrom().item( serverError().type( MediaType.TEXT_PLAIN ).entity( message ).build() );
    }

    public static Response buildWithZipHeader( ResponseBuilder builder, final String buildConfigId )
    {
        StringBuilder header = new StringBuilder();
        header.append( "attachment;" ).append( "filename=" ).append( buildConfigId ).append( ".zip" );
        return builder.header( "Content-Disposition", header.toString() ).build();
    }
}
