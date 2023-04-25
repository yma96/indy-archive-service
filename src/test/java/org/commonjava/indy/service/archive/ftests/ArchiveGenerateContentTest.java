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
package org.commonjava.indy.service.archive.ftests;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.commonjava.indy.service.archive.ftests.profile.ArchiveFunctionProfile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.ws.rs.core.MediaType;
import java.io.File;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile( ArchiveFunctionProfile.class )
@Tag( "function" )
public class ArchiveGenerateContentTest
                extends AbstractArchiveFuncTest
{
    /**
     * <b>GIVEN:</b>
     * <ul>
     *     <li>Input a json file with correct artifact content</li>
     * </ul>
     *
     * <br/>
     * <b>WHEN:</b>
     * <ul>
     *     <li>Request generating api of archive</li>
     * </ul>
     *
     * <br/>
     * <b>THEN:</b>
     * <ul>
     *     <li>The archive can be generated correctly</li>
     * </ul>
     */
    @Test
    public void testSuccessGenerateContent()
    {
        File file = new File( "data/archive", SUCCESS_BUILD_ARCHIVE );
        assertFalse( file.exists() );
        assertThat( file.length(), equalTo( 0L ) );

        given().when()
               .body( SUCCESS_TRACKED.getBytes() )
               .contentType( MediaType.APPLICATION_JSON )
               .post( "/api/archive/generate" )
               .then()
               .statusCode( anyOf( is( 202 ), is( 200 ) ) );

        assertTrue( file.exists() );
        assertTrue( file.length() > 0L );
    }

    /**
     * <b>GIVEN:</b>
     * <ul>
     *     <li>Input a json file with missing artifact content</li>
     * </ul>
     *
     * <br/>
     * <b>WHEN:</b>
     * <ul>
     *     <li>Request generating api of archive</li>
     * </ul>
     *
     * <br/>
     * <b>THEN:</b>
     * <ul>
     *     <li>The archive can not be generated</li>
     * </ul>
     */
    @Test
    public void testMissingGenerateContent()
    {
        File file = new File( "data/archive", MISSING_BUILD_ARCHIVE );
        assertFalse( file.exists() );
        assertThat( file.length(), equalTo( 0L ) );

        given().when()
               .body( MISSING_TRACKED.getBytes() )
               .contentType( MediaType.APPLICATION_JSON )
               .post( "/api/archive/generate" )
               .then()
               .statusCode( anyOf( is( 202 ), is( 200 ) ) );

        assertThat( file.length(), equalTo( 0L ) );
    }
}
