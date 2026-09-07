package io.memoryos.iam;

public enum GroupAction {
    RENAME("rename"),
    DELETE("delete"),
    MANAGE_MEMBERS("manage_members"),
    MANAGE_MANAGERS("manage_managers"),
    MANAGE_GRANTS("manage_grants"),
    MANAGE_SOURCES("manage_sources");

    private final String token;

    GroupAction(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }
}
