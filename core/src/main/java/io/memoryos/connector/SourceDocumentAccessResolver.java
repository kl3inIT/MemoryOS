package io.memoryos.connector;

import io.memoryos.document.DocumentId;
import io.memoryos.iam.ActorId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface SourceDocumentAccessResolver {

    boolean canRead(ActorId actorId, DocumentId documentId);

    Set<UUID> readableDocuments(ActorId actorId, List<UUID> documentIds);
}
