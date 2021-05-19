package org.commonjava.indy.service.archive.model;

public enum ArchiveStatus
{

    inProgress( "In Progress" ), completed( "Completed" );

    private final String archiveStatus;

    ArchiveStatus( String archiveStatus )
    {
        this.archiveStatus = archiveStatus;
    }

    public String getArchiveStatus()
    {
        return this.archiveStatus;
    }
}
