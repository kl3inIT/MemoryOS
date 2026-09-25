package io.memoryos.ingestion.extraction;

import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractedDocument;
import io.memoryos.document.ExtractedDocument.Block;
import io.memoryos.document.ExtractedDocument.Cell;
import io.memoryos.document.ExtractedDocument.Kind;
import io.memoryos.document.ExtractedDocument.Page;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The tail every layout provider shares once its adapter has produced blocks: the document's text,
 * the blank-result refusal, the financial table checks and the bounded artifact. Docling and
 * PaddleOCR-VL publish through here so that the same blocks read the same way whoever parsed them.
 */
final class DocumentAssembly {
    private static final int MAX_TEXT_CHARACTERS = 2_000_000;
    private static final int MAX_ARTIFACT_BYTES = 33_554_432;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DocumentAssembly.class);

    private DocumentAssembly() {}

    /**
     * @param provider the event prefix of the provider's logs, such as {@code docling}
     * @throws ExtractionException {@code MALFORMED} when the blocks carry no text at all
     */
    static DocumentContent publish(ObjectMapper mapper, List<Block> blocks, List<Page> pages,
            @Nullable JsonNode orientation, String mediaType, String title, Map<String, String> metadata,
            String provider) throws ExtractionException {
        String text = semanticText(blocks);
        if (text.isBlank()) throw failure(ExtractionFailure.MALFORMED);
        var financialChecks = FinancialTableDiagnostics.assess(blocks, mapper);
        if (!financialChecks.isEmpty()) {
            int reviewChecks = 0;
            for (var check : financialChecks) {
                if (!"CONSISTENT".equals(check.path("status").asString())) reviewChecks++;
            }
            LOG.atInfo().addKeyValue("event", provider + ".financial_checks.completed")
                    .addKeyValue("check_count", financialChecks.size()).addKeyValue("review_count", reviewChecks)
                    .log("Scoped financial checks completed; source values unchanged");
        }
        String json = mapper.writeValueAsString(new ExtractedDocument(ExtractedDocument.SCHEMA, null, blocks,
                pages, orientation, financialChecks));
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_ARTIFACT_BYTES) {
            throw failure(ExtractionFailure.WRITE_LIMIT);
        }
        return new DocumentContent(mediaType, title, text, metadata, json, null);
    }

    /** Paragraph text joined by blank lines; a table as rows of tab-separated cells at their positions. */
    static String semanticText(List<Block> blocks) throws ExtractionException {
        var text = new StringBuilder();
        for (Block block : blocks) {
            if (block.kind() == Kind.IMAGE) continue;
            if (block.kind() == Kind.TABLE && block.table() != null) {
                appendTableText(text, block.table().cells());
            } else {
                String value = block.text().strip();
                if (!value.isEmpty()) appendText(text, text.isEmpty() ? "" : "\n\n", value);
            }
        }
        return text.toString();
    }

    private static void appendTableText(StringBuilder text, List<Cell> tableCells) throws ExtractionException {
        if (tableCells.size() > 100_000) throw failure(ExtractionFailure.WRITE_LIMIT);
        var cells = new ArrayList<>(tableCells);
        cells.sort(Comparator.comparingInt(Cell::row).thenComparingInt(Cell::column));
        int previousRow = -1;
        int previousColumn = 0;
        for (Cell cell : cells) {
            int row = cell.row();
            int column = cell.column();
            if (row < 0 || column < 0 || (row == previousRow && column <= previousColumn)) {
                throw failure(ExtractionFailure.MALFORMED);
            }
            if (previousRow < 0 && !text.isEmpty()) appendText(text, "\n\n", "");
            if (row != previousRow) {
                appendTablePadding(text, '\n', previousRow < 0 ? row : row - previousRow);
                previousColumn = 0;
            }
            appendTablePadding(text, '\t', column - previousColumn);
            appendText(text, "", cell.text().strip());
            previousRow = row;
            previousColumn = column;
        }
    }

    private static void appendTablePadding(StringBuilder text, char separator, int count) throws ExtractionException {
        if ((long) text.length() + count > MAX_TEXT_CHARACTERS) throw failure(ExtractionFailure.WRITE_LIMIT);
        text.repeat(separator, count);
    }

    private static void appendText(StringBuilder text, String separator, String value) throws ExtractionException {
        if ((long) text.length() + separator.length() + value.length() > MAX_TEXT_CHARACTERS) {
            throw failure(ExtractionFailure.WRITE_LIMIT);
        }
        text.append(separator).append(value);
    }

    static ExtractionException failure(ExtractionFailure failure) {
        return new ExtractionException(failure, "Document extraction failed: " + failure.name());
    }
}
