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
import org.apache.commons.io.FileUtils;
import org.commonjava.indy.service.archive.jaxrs.mock.MockTestProfile;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

import static io.restassured.RestAssured.given;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static javax.ws.rs.core.Response.Status.NO_CONTENT;
import static org.commonjava.indy.service.archive.jaxrs.mock.MockArchiveController.EXIST_BUILD;
import static org.commonjava.indy.service.archive.jaxrs.mock.MockArchiveController.EXIST_BUILD_ARCHIVE;
import static org.commonjava.indy.service.archive.jaxrs.mock.MockArchiveController.NOT_FOUND_BUILD;
import static org.commonjava.indy.service.archive.jaxrs.mock.MockArchiveController.SIZE_50K;
import static org.commonjava.indy.service.archive.util.TestUtil.getBytes;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile( MockTestProfile.class )
public class ArchiveDeleteTest
{
    @Test
    public void testSuccessDelete() throws IOException
    {
        File file = new File( "data/archive", EXIST_BUILD_ARCHIVE );
        FileUtils.write( file, new String( getBytes( SIZE_50K ) ), "UTF-8" );
        assertTrue( file.exists() );
        given().when().delete( "/api/archive/" + EXIST_BUILD ).then().statusCode( NO_CONTENT.getStatusCode() );
        assertFalse( file.exists() );
    }

    @Test
    public void testFailedDelete() throws IOException
    {
        given().when()
               .delete( "/api/archive/" + NOT_FOUND_BUILD )
               .then()
               .statusCode( INTERNAL_SERVER_ERROR.getStatusCode() );
    }

}