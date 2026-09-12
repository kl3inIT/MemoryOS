package io.memoryos.provider;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.document.DocumentContent;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Canonical output and resource bounds shared by the offline structural readers. */
public final class StructuredContent {
    public static final int MAX_TEXT = 2_000_000;
    public static final int MAX_BYTES = 33_554_432;
    public static final int MAX_CELLS = 200_000;
    public static final int MAX_TABS = 100;
    private final ObjectMapper mapper;
    private final ObjectNode canonical;
    private final ArrayNode blocks;
    private final StringBuilder text = new StringBuilder();
    private final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(120).toNanos();
    private int cells;

    public StructuredContent(ObjectMapper mapper, SourceInputDescriptor input) {
        this.mapper = mapper;
        canonical = mapper.createObjectNode();
        canonical.put("schema", "memoryos-extraction-v1");
        canonical.set("source", mapper.valueToTree(input));
        blocks = canonical.putArray("blocks");
    }

    public ObjectNode canonical() { return canonical; }

    public ObjectNode block(String kind) throws ExtractionException {
        checkTime();
        if (blocks.size() >= 100_000) throw failure(ExtractionFailure.WRITE_LIMIT);
        ObjectNode block = blocks.addObject();
        block.put("index", blocks.size() - 1);
        block.put("kind", kind);
        return block;
    }

    public void cell() throws ExtractionException {
        checkTime();
        if (++cells > MAX_CELLS) throw failure(ExtractionFailure.WRITE_LIMIT);
    }

    public void append(String value) throws ExtractionException {
        checkTime();
        if ((long) text.length() + value.length() > MAX_TEXT) throw failure(ExtractionFailure.WRITE_LIMIT);
        text.append(value);
    }

    public void checkTime() throws ExtractionException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0) throw failure(ExtractionFailure.TIMEOUT);
    }

    public DocumentContent finish(String mediaType, String title, String parser) throws ExtractionException {
        checkTime();
        byte[] encoded = mapper.writeValueAsBytes(canonical);
        if (encoded.length > MAX_BYTES) throw failure(ExtractionFailure.WRITE_LIMIT);
        String json = new String(encoded, java.nio.charset.StandardCharsets.UTF_8);
        return new DocumentContent(mediaType, title, text.toString().strip(),
                Map.of("parser", parser, "parser_configuration", "memoryos-extraction-v1;offline;formulas=inert"), json, null);
    }

    public static byte[] read(InputStream input, long size, int limit) throws ExtractionException {
        if (size < 1 || size > limit) throw failure(ExtractionFailure.WRITE_LIMIT);
        try {
            byte[] bytes = input.readNBytes((int) size + 1);
            if (bytes.length != size) throw failure(ExtractionFailure.MALFORMED);
            return bytes;
        } catch (IOException exception) {
            throw failure(ExtractionFailure.MALFORMED);
        }
    }

    public static ExtractionException failure(ExtractionFailure failure) {
        return new ExtractionException(failure, "Structured extraction failed: " + failure);
    }
}
