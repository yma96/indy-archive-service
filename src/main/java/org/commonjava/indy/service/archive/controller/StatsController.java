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

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

@ApplicationScoped
public class StatsController
{
    private static final String VERSIONING_PROPERTIES = "version.properties";

    final Properties info = new Properties();

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @PostConstruct
    void init()
    {
        ClassLoader cl = this.getClass().getClassLoader();

        try (InputStream is = cl.getResourceAsStream( VERSIONING_PROPERTIES ))
        {
            if ( is != null )
            {
                info.load( is );
            }
            else
            {
                logger.warn( "Resource not found, file: {}, loader: {}", VERSIONING_PROPERTIES, cl );
            }
        }
        catch ( final IOException e )
        {
            logger.error( "Failed to read version information from classpath resource: " + VERSIONING_PROPERTIES, e );
        }
    }

    public Uni<JsonObject> getStatsInfo()
    {
        return Uni.createFrom().item( JsonObject.mapFrom( info ) );
    }
}
