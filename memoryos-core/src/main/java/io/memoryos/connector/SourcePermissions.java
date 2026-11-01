package io.memoryos.connector;

/**
 * Read-side affordance map for one Source, projected from the same decisions the write guards enforce. It is a
 * browser hint only; every mutation keeps its own guard. State preconditions (deleting, replay, roots, pause) are
 * not encoded.
 */
public record SourcePermissions(
        boolean edit,
        boolean delete,
        boolean publish,
        boolean manageConfiguration,
        boolean removeItems
) {
    public static final SourcePermissions NONE = new SourcePermissions(false, false, false, false, false);

    /**
     * @param globalManage global {@code SOURCES_MANAGE}
     * @param globalDelete global {@code SOURCES_DELETE}
     * @param managedScope the scoped write decision ({@code SourceScopeSql.WRITE}) used by rename, Groups, upload,
     *                     reindex and Google Drive schedule/pause/synchronize guards
     * @param creatorGroupless the groupless-creator carve-out of the Source deletion guard
     */
    public static SourcePermissions of(boolean globalManage, boolean globalDelete, boolean managedScope,
            boolean creatorGroupless) {
        return new SourcePermissions(
                globalManage || managedScope,
                globalDelete || creatorGroupless,
                globalManage,
                globalManage,
                globalDelete
        );
    }
}
