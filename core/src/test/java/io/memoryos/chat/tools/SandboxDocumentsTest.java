package io.memoryos.chat.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.iam.identity.ActorId;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.retrieval.DocumentOriginalService;
import io.memoryos.retrieval.SearchHit;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SandboxDocumentsTest {
    private final DocumentOriginalService originals = mock(DocumentOriginalService.class);
    private final ActorId actor = new ActorId(UUID.randomUUID());

    @Test void namesFollowOnyxSandboxFilenameForDocument() {
        assertEquals("Q3 report_abc.xlsx", SandboxDocuments.sandboxName("Q3 report.xlsx", "other.csv", "abc"));
        // A title without an extension takes the stored file's, so the model can tell the type.
        assertEquals("Báo cáo_abc.pdf", SandboxDocuments.sandboxName("Báo cáo", "bao-cao.pdf", "abc"));
        assertEquals("a_b_abc", SandboxDocuments.sandboxName("a/b", "noext", "abc"));
        assertEquals("csv_abc", SandboxDocuments.sandboxName(" ..csv", "x", "abc"));
        assertEquals("document_abc", SandboxDocuments.sandboxName("...", "x", "abc"));
        String longName = SandboxDocuments.sandboxName("x".repeat(300) + ".docx", "", "abc");
        assertEquals(200, longName.length());
        assertTrue(longName.endsWith("_abc.docx"));
    }

    @Test void onlyHitsWithAReadableOriginalAreStagedAndANewGenerationReplacesTheOld() {
        UUID document = UUID.randomUUID(), missing = UUID.randomUUID();
        var stored = new StoredObjectId(UUID.randomUUID());
        when(originals.citationOriginals(eq(actor), any())).thenReturn(Map.of(document, new StoredObjectReference(stored,
                new ObjectKey("raw/a"), "sales.csv", new ObjectMetadata(7, "text/csv", new ContentSha256("b".repeat(64))))));
        var sandbox = new SandboxDocuments(originals, actor);

        var first = hit(document, UUID.randomUUID());
        var names = sandbox.register(List.of(first, hit(document, first.generation()), hit(missing, UUID.randomUUID())));
        assertEquals(Map.of(document, "Sales_" + stored.value() + ".csv"), names);
        assertEquals(1, sandbox.documents().size());

        var newer = hit(document, UUID.randomUUID());
        sandbox.register(List.of(newer));
        assertEquals(List.of(newer.generation()), sandbox.documents().stream().map(SandboxDocuments.Document::generation).toList());

        // Opening rechecks citation authority for the staged generation.
        sandbox.open(sandbox.documents().getFirst());
        verify(originals).citationOriginal(actor, document, newer.generation());
    }

    private static SearchHit hit(UUID document, UUID generation) {
        return new SearchHit(document, generation, 0, "Sales", "text/csv", "a,b", "[]", Instant.EPOCH, .5);
    }
}
