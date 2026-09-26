/**
 * The file library: a person's uploads and their extraction work, the one library listing that also shows the files
 * and images Chat generated, trash, the storage limit, thumbnails, copies, published files and ZIP archives. Chat,
 * meetings and ingestion use it; what Chat attaches files to is asked through ports Chat implements.
 */
@ApplicationModule(displayName = "File library", type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"shared", "iam", "objectstorage", "document", "retrieval", "connector"})
@NullMarked
package io.memoryos.library;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
