package io.memoryos.document;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.memoryos.document.application.StructuredDocumentChunker;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The passages the web citation-locating test runs through the reader's locator are this chunker's own output for
 * the extraction of each fixture original (`src/test/resources/citation-fixtures`), so a change to what a passage
 * reads like reaches that test. When this fails after an intended chunker change, regenerate the committed file
 * with {@code MEMORYOS_CITATION_FIXTURES_WRITE=true ./gradlew :memoryos-core:test --tests '*CitationFixtureChunksTest'}
 * and rerun {@code pnpm --dir web test:unit}.
 */
class CitationFixtureChunksTest {
    private static final String TITLE = "Quy chế công tác phí năm 2026";
    private static final List<String> FIXTURES = List.of("docx", "markdown", "text", "pdf", "xlsx");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void committedPassagesAreWhatTheChunkerWritesForEachFixture() throws IOException {
        var chunker = new StructuredDocumentChunker(mapper);
        ObjectNode chunks = mapper.createObjectNode();
        for (String fixture : FIXTURES) {
            var passages = chunks.putArray(fixture);
            for (var chunk : chunker.chunk(TITLE, resource(fixture + ".json"))) {
                passages.addObject().put("ordinal", chunk.ordinal()).put("content", chunk.content())
                        .put("provenanceJson", chunk.provenanceJson());
            }
        }
        String expected = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(chunks) + "\n";
        Path committed = repositoryRoot().resolve("web/src/features/chat/sources/citation-fixtures/chunks.json");
        if ("true".equals(System.getenv("MEMORYOS_CITATION_FIXTURES_WRITE"))) Files.writeString(committed, expected);
        assertEquals(expected, Files.readString(committed),
                "chunks.json is stale; rerun this test with MEMORYOS_CITATION_FIXTURES_WRITE=true");
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                CitationFixtureChunksTest.class.getResourceAsStream("/citation-fixtures/" + name), name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.exists(candidate.resolve("settings.gradle.kts"))) candidate = candidate.getParent();
        return Objects.requireNonNull(candidate, "repository root");
    }
}
