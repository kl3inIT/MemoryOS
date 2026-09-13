package io.memoryos.connector;

public enum SourceAction {
    UPLOAD("upload"),
    REINDEX("reindex"),
    REMOVE_ITEMS("remove_items"),
    DELETE("delete"),
    MANAGE_GROUPS("manage_groups"),
    RENAME("rename"),
    MANAGE_ACCESS("manage_access"),
    MANAGE_CONFIGURATION("manage_configuration"),
    SYNCHRONIZE("synchronize"),
    MANAGE_SCHEDULE("manage_schedule"),
    PAUSE_SYNC("pause_sync"),
    RESUME_SYNC("resume_sync");

    private final String token;

    SourceAction(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }
}
