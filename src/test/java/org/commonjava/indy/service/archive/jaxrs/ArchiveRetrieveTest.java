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
package org.commonjava.indy.service.archive.jaxrs;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.commonjava.indy.service.archive.jaxrs.mock.MockTestProfile;
import org.junit.jupiter.api.Test;

import javax.ws.rs.core.MediaType;

import static io.restassured.RestAssured.given;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static javax.ws.rs.core.Response.Status.OK;
import static org.commonjava.indy.service.archive.jaxrs.mock.MockArchiveController.ERR_BUILD;
import static org.commonjava.indy.service.archive.jaxrs.mock.MockArchiveController.EXIST_BUILD;
import static org.commonjava.indy.service.archive.jaxrs.mock.MockArchiveController.EXIST_LARGE_BUILD;
import static org.commonjava.indy.service.archive.jaxrs.mock.MockArchiveController.NOT_FOUND_BUILD;

@QuarkusTest
@TestProfile( MockTestProfile.class )
public class ArchiveRetrieveTest
{
    @Test
    public void testArchiveExists()
    {
        given().when()
               .get( "/api/archive/" + EXIST_BUILD )
               .then()
               .statusCode( OK.getStatusCode() )
               .contentType( MediaType.APPLICATION_OCTET_STREAM );

        given().when().head( "/api/archive/" + EXIST_BUILD ).then().statusCode( OK.getStatusCode() );
    }

    @Test
    public void testArchiveNotFound()
    {
        given().when().get( "/api/archive/" + NOT_FOUND_BUILD ).then().statusCode( NOT_FOUND.getStatusCode() );
    }

    @Test
    public void testArchiveErr()
    {
        given().when().get( "/api/archive/" + ERR_BUILD ).then().statusCode( INTERNAL_SERVER_ERROR.getStatusCode() );
    }

    @Test
    public void testLargeArchive()
    {
        given().when()
               .get( "/api/archive/" + EXIST_LARGE_BUILD )
               .then()
               .statusCode( OK.getStatusCode() )
               .contentType( MediaType.APPLICATION_OCTET_STREAM );

        given().when().head( "/api/archive/" + EXIST_LARGE_BUILD ).then().statusCode( OK.getStatusCode() );
    }
}