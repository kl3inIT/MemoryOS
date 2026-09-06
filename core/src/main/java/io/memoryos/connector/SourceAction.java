package io.memoryos.connector;

public enum SourceAction {
    UPLOAD("upload"),
    REINDEX("reindex"),
    REMOVE_ITEMS("remove_items"),
    DELETE("delete"),
    MANAGE_GROUPS("manage_groups");

    private final String token;

    SourceAction(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }
}
