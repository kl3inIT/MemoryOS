package io.memoryos.chat.tools;

import io.memoryos.iam.identity.ActorId;
import io.memoryos.retrieval.DocumentOriginalService;
import io.memoryos.retrieval.SearchHit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Per-turn record of the source files behind search hits, so the next {@code run_python} call stages them, as Onyx
 * {@code llm_loop.py} extends {@code chat_files} with {@code build_python_chat_files_from_search_docs}. Only the
 * stored original is recorded here; opening it rechecks the actor's Chat citation authority.
 */
public final class SandboxDocuments {
    /** Onyx {@code FILE_ASSOCIATED_GUIDANCE}, placed before a hit's excerpt when its file is in the sandbox. */
    static final String FILE_ASSOCIATED_GUIDANCE = "Only a short excerpt from this document is shown below. The complete "
            + "file is available in the sandbox as \"%s\" — prefer the Python code interpreter to read, parse, or analyze it\n\n"
            + "Excerpt: ";
    private static final Pattern UNSAFE = Pattern.compile("[\\x00-\\x1f/\\\\:*?\"<>|]+");
    private static final int NAME_LIMIT = 200;

    /** A staged source file: its Document identity, sandbox name and the stored object's size and checksum. */
    public record Document(UUID documentId, UUID generation, String name, long sizeBytes, String checksum, String mediaType) {}

    private final DocumentOriginalService originals;
    private final ActorId actor;
    private final Map<UUID, Document> documents = new LinkedHashMap<>();

    public SandboxDocuments(DocumentOriginalService originals, ActorId actor) {
        this.originals = originals;
        this.actor = actor;
    }

    /** Records the originals behind these hits and returns each recorded Document's sandbox name. */
    public synchronized Map<UUID, String> register(Collection<SearchHit> hits) {
        var byDocument = new LinkedHashMap<UUID, SearchHit>();
        for (var hit : hits) byDocument.putIfAbsent(hit.documentId(), hit);
        var names = new LinkedHashMap<UUID, String>();
        var found = originals.citationOriginals(actor, byDocument.keySet());
        for (var entry : byDocument.entrySet()) {
            var reference = found.get(entry.getKey());
            if (reference == null) continue;
            var hit = entry.getValue();
            var existing = documents.get(hit.documentId());
            // A newer generation of the same Document replaces the staged file.
            var document = existing != null && existing.generation().equals(hit.generation()) ? existing
                    : new Document(hit.documentId(), hit.generation(),
                            sandboxName(hit.title(), reference.filename(), reference.id().value().toString()),
                            reference.metadata().sizeBytes(), reference.metadata().checksum().value(),
                            reference.metadata().mediaType());
            documents.put(hit.documentId(), document);
            names.put(hit.documentId(), document.name());
        }
        return names;
    }

    public synchronized List<Document> documents() {
        return new ArrayList<>(documents.values());
    }

    public DocumentOriginalService.Original open(Document document) {
        return originals.citationOriginal(actor, document.documentId(), document.generation());
    }

    /**
     * Onyx {@code sandbox_filename_for_document(title, file_id)}: the sanitized title with the file id appended before
     * its extension. A title without an extension takes the stored file's, so the model can tell the file type.
     */
    static String sandboxName(String title, String filename, String fileId) {
        String sanitized = UNSAFE.matcher(title).replaceAll("_").strip().replaceAll("^\\.+|\\.+$", "");
        int dot = sanitized.lastIndexOf('.');
        String base = dot > 0 ? sanitized.substring(0, dot) : sanitized;
        String extension = dot > 0 ? sanitized.substring(dot) : "";
        if (extension.isEmpty()) {
            int original = filename.lastIndexOf('.');
            if (original > 0 && original < filename.length() - 1)
                extension = UNSAFE.matcher(filename.substring(original)).replaceAll("_");
        }
        if (base.isEmpty()) base = "document";
        String suffix = "_" + fileId + extension;
        int limit = Math.max(1, NAME_LIMIT - suffix.length());
        return (base.length() <= limit ? base : base.substring(0, limit)) + suffix;
    }
}
