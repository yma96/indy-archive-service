package org.commonjava.indy.service.archive.config;

import io.quarkus.arc.config.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@ConfigProperties( prefix = "pre-seed" )
public class PreSeedConfig
{
    @ConfigProperty( name = "indy-server" )
    public Optional<String> indyServer;

    public Optional<String> storageDir;
}
