package org.commonjava.indy.service.archive.util;

import org.commonjava.indy.service.archive.config.PreSeedConfig;

import java.util.Optional;

public class TestPreSeedConfig
        implements PreSeedConfig
{
    Optional<String> mainIndy;
    public TestPreSeedConfig(Optional<String> mainIndy)
    {
        this.mainIndy = mainIndy;
    }

    @Override
    public Optional<String> mainIndy()
    {
        return mainIndy;
    }

    @Override
    public Optional<String> storageDir()
    {
        return Optional.of( "data" );
    }

    @Override
    public Optional<Long> notUsedDaysCleanup()
    {
        return Optional.of( 365l );
    }

}
