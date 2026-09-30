package io.memoryos.connector;

public enum SourceType {
    FILE,
    GOOGLE_DRIVE,
    SHAREPOINT;

    /**
     * Whether the Source reads an external provider. Uploaded files have none: nothing to select, synchronize or
     * authorize, so no provider adapter serves them.
     */
    public boolean external() {
        return this != FILE;
    }
}
