package io.memoryos.provider;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractedDocument;
import io.memoryos.document.ExtractedDocument.Block;
import io.memoryos.document.ExtractedDocument.Kind;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Canonical output and resource bounds shared by the offline structural readers. */
public final class StructuredContent {
    public static final int MAX_TEXT = 2_000_000;
    public static final int MAX_BYTES = 33_554_432;
    public static final int MAX_CELLS = 200_000;
    public static final int MAX_TABS = 100;
    public static final int MAX_BLOCKS = 100_000;
    private final ObjectMapper mapper;
    private final JsonNode source;
    private final List<Block> blocks = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();
    private final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(120).toNanos();
    private int cells;

    public StructuredContent(ObjectMapper mapper, SourceInputDescriptor input) {
        this.mapper = mapper;
        source = mapper.valueToTree(input);
    }

    /** The index the next {@link #add(Block)} must carry, after the block bound and deadline. */
    public int nextIndex() throws ExtractionException {
        checkTime();
        if (blocks.size() >= MAX_BLOCKS) throw failure(ExtractionFailure.WRITE_LIMIT);
        return blocks.size();
    }

    public void add(Block block) {
        blocks.add(block);
    }

    /** A text block without a recorded location. */
    public void add(Kind kind, String value) throws ExtractionException {
        add(Block.text(nextIndex(), kind, value, List.of()));
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
        byte[] encoded = mapper.writeValueAsBytes(new ExtractedDocument(ExtractedDocument.SCHEMA, source, blocks,
                List.of(), null, null));
        if (encoded.length > MAX_BYTES) throw failure(ExtractionFailure.WRITE_LIMIT);
        String json = new String(encoded, java.nio.charset.StandardCharsets.UTF_8);
        return new DocumentContent(mediaType, title, text.toString().strip(),
                Map.of("parser", parser, "parser_configuration", ExtractedDocument.SCHEMA + ";offline;formulas=inert"), json, null);
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
