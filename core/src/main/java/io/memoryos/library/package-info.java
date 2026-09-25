/**
 * The file library: a person's uploads and their extraction work, the one library listing that also shows the files
 * and images Chat generated, trash, the storage limit, thumbnails, copies, published files and ZIP archives. Chat,
 * meetings and ingestion use it; what Chat attaches files to is asked through ports Chat implements. Beside the owned
 * listing it shows what others let the person read (meetings, their agents' files, Source documents), each row
 * authorized at every read by the owning capability, through {@link io.memoryos.library.MeetingShelf},
 * {@link io.memoryos.library.FileAttachments} and Search's document shelf, plus the person's own stars and opens.
 */
@ApplicationModule(displayName = "File library", type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"shared", "iam", "objectstorage", "document", "retrieval", "connector"})
package io.memoryos.library;

import org.springframework.modulith.ApplicationModule;
