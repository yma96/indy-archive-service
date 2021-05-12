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
import static javax.ws.rs.core.Response.Status.ACCEPTED;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.anyOf;

@QuarkusTest
@TestProfile( MockTestProfile.class )
public class ArchiveGenerateTest
{
    private final String SUCCESS_TRACKED = "{\n" + "\"buildConfigId\":\"5555\",\n" + "\"downloads\":\n" + "[{\n"
                    + "    \"storeKey\" : \"maven:hosted:shared-imports\",\n"
                    + "    \"path\" : \"/org/apache/maven/maven-core/3.0/maven-core-3.0.jar\",\n"
                    + "    \"md5\" : \"9bd377874764a4fad7209021abfe7cf7\",\n"
                    + "    \"sha256\" : \"ba03294ee53e7ba31838e4950f280d033c7744c6c7b31253afc75aa351fbd989\",\n"
                    + "    \"sha1\" : \"73728ce32c9016c8bd05584301fa3ba3a6f5d20a\",\n" + "    \"size\" : 527040\n"
                    + "  }\n" + "]}";

    private final String FAILED_TRACKED = "{\n" + "\"buildConfigId\":\"8990\",\n" + "\"downloads\":\n" + "[{\n"
                    + "    \"storeKey\" : \"maven:hosted:shared-imports\",\n"
                    + "    \"path\" : \"/org/apache/maven/maven-core/3.0/maven-core-3.0.jar\",\n"
                    + "    \"md5\" : \"9bd377874764a4fad7209021abfe7cf7\",\n"
                    + "    \"sha256\" : \"ba03294ee53e7ba31838e4950f280d033c7744c6c7b31253afc75aa351fbd989\",\n"
                    + "    \"sha1\" : \"73728ce32c9016c8bd05584301fa3ba3a6f5d20a\",\n" + "    \"size\" : 527040\n"
                    + "  }\n" + "]}";

    @Test
    public void testSuccessGenerate()
    {
        given().when()
               .body( SUCCESS_TRACKED.getBytes() )
               .contentType( MediaType.APPLICATION_JSON )
               .post( "/api/archive/generate" )
               .then()
               .statusCode( anyOf( is( 202 ), is( 200 ) ) );
    }

    @Test
    public void testFailedGenerate()
    {
        given().when()
               .body( FAILED_TRACKED.getBytes() )
               .contentType( MediaType.APPLICATION_JSON )
               .post( "/api/archive/generate" )
               .then()
               .statusCode( ACCEPTED.getStatusCode() );
    }

}