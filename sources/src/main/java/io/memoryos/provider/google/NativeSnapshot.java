package io.memoryos.provider.google;

import io.memoryos.connector.GoogleDriveProvider.FileMetadata;
import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import io.memoryos.provider.StructuredContent;
import java.io.InputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

final class NativeSnapshot {
    static final String SCHEMA = "memoryos-google-native-v1";
    static final int MAX_RAW_TEXT = 8_000_000;

    private NativeSnapshot() {}

    static ObjectNode envelope(ObjectMapper mapper, FileMetadata file, String kind, JsonNode content) {
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("schema", SCHEMA);
        snapshot.put("kind", kind);
        ObjectNode source = snapshot.putObject("source");
        source.put("id", file.id());
        source.put("name", file.name());
        source.put("mimeType", file.mimeType());
        source.put("version", file.version());
        if (file.modifiedAt() != null) source.put("modifiedTime", file.modifiedAt().toString());
        if (file.checksum() != null) source.put("checksum", file.checksum());
        snapshot.set("content", content);
        return snapshot;
    }

    static JsonNode read(ObjectMapper mapper, InputStream input, long size,
                         SourceInputDescriptor descriptor, String kind) throws ExtractionException {
        byte[] bytes = StructuredContent.read(input, size, StructuredContent.MAX_BYTES);
        try {
            JsonNode snapshot = mapper.readTree(bytes);
            if (snapshot == null || !SCHEMA.equals(snapshot.path("schema").asString())
                    || !kind.equals(snapshot.path("kind").asString())
                    || !snapshot.path("content").isObject()
                    || descriptor.providerFileId() == null || descriptor.providerVersion() == null
                    || !descriptor.providerFileId().equals(snapshot.path("source").path("id").asString())
                    || !descriptor.providerVersion().equals(snapshot.path("source").path("version").asString())) {
                throw StructuredContent.failure(ExtractionFailure.MALFORMED);
            }
            checkTree(snapshot, 0, new long[3]);
            return snapshot;
        } catch (tools.jackson.core.JacksonException exception) {
            throw StructuredContent.failure(ExtractionFailure.MALFORMED);
        }
    }

    static void checkTree(JsonNode node, int depth, long[] count) throws ExtractionException {
        if (depth > 100 || ++count[0] > 2_000_000) throw StructuredContent.failure(ExtractionFailure.WRITE_LIMIT);
        if (node.isString() && (count[1] += node.asString().length()) > MAX_RAW_TEXT) {
            throw StructuredContent.failure(ExtractionFailure.WRITE_LIMIT);
        }
        if (node.has("tableRows")) {
            long rows = node.path("rows").asLong(-1);
            long columns = node.path("columns").asLong(-1);
            if (rows < 1 || columns < 1 || rows > StructuredContent.MAX_CELLS
                    || columns > StructuredContent.MAX_CELLS
                    || (count[2] += rows * columns) > StructuredContent.MAX_CELLS) {
                throw StructuredContent.failure(ExtractionFailure.WRITE_LIMIT);
            }
        }
        if (node.isArray() || node.isObject()) for (JsonNode child : node) checkTree(child, depth + 1, count);
    }
}
