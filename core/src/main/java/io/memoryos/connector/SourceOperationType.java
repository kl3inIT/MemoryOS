package io.memoryos.connector;

public enum SourceOperationType {
    INDEX,
    SYNC_SOURCE,
    VALIDATE_GOOGLE_DRIVE_SELECTION,
    VALIDATE_SHAREPOINT_SELECTION,
    REMOVE_ITEM,
    DELETE_SOURCE;

    /**
     * The operation that verifies a selection of {@code sourceType}. Its name is {@code VALIDATE_<type>_SELECTION},
     * the rule the operation query applies in SQL, so a new provider adds a constant and no mapping.
     */
    public static SourceOperationType selectionValidation(SourceType sourceType) {
        return valueOf("VALIDATE_" + sourceType.name() + "_SELECTION");
    }
}
