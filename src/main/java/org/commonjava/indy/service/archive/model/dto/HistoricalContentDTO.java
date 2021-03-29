/**
 * Copyright (C) 2011-2021 Red Hat, Inc. (https://github.com/Commonjava/indy)
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
package org.commonjava.indy.service.archive.model.dto;

public class HistoricalContentDTO
{
    private String buildConfigId;

    private String trackId;

    private HistoricalEntryDTO[] downloads;

    public HistoricalContentDTO()
    {
    }

    public HistoricalContentDTO( String buildConfigId, String trackId, HistoricalEntryDTO[] downloads ) {
        this.buildConfigId = buildConfigId;
        this.trackId = trackId;
        this.downloads = downloads;
    }

    public String getBuildConfigId() {
        return buildConfigId;
    }

    public void setBuildConfigId( String buildConfigId ) {
        this.buildConfigId = buildConfigId;
    }

    public String getTrackId() {
        return trackId;
    }

    public void setTrackId( String trackId ) {
        this.trackId = trackId;
    }

    public HistoricalEntryDTO[] getDownloads()
    {
        return downloads;
    }

    public void setDownloads( final HistoricalEntryDTO[] downloads )
    {
        this.downloads = downloads;
    }

    @Override
    public String toString() {
        String s1 = String.format( "%s, %s, %s", buildConfigId, trackId, downloads.length );
        String s2 = "";
        for ( HistoricalEntryDTO dto : downloads )
        {
            s2 = s2 + String.format( "[%s, %s, %s]", dto.getStoreKey(), dto.getPath(), dto.getLocalUrl() );
        }
        return s1 + s2;
    }
}
