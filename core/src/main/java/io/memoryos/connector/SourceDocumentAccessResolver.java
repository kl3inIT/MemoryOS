package io.memoryos.connector;

import io.memoryos.document.DocumentId;
import io.memoryos.identity.ActorId;

public interface SourceDocumentAccessResolver {

    boolean canRead(ActorId actorId, DocumentId documentId);
}
