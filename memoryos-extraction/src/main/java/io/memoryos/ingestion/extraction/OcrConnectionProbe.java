package io.memoryos.ingestion.extraction;

import io.memoryos.ai.ocr.OcrConnectionService;
import io.memoryos.ai.ocr.OcrProbe;
import io.memoryos.document.ExtractionException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads a bundled picture of the line "MemoryOS OCR 2026" with a connection that is not kept. */
final class OcrConnectionProbe implements OcrProbe {
    private static final Logger LOG = LoggerFactory.getLogger(OcrConnectionProbe.class);
    private static final byte[] PICTURE = picture();
    private static final long MAX_INPUT = 1_048_576;
    private final OcrAdapterRegistry adapters;

    OcrConnectionProbe(OcrAdapterRegistry adapters) { this.adapters = adapters; }

    @Override public boolean reads(OcrConnectionService.Connection connection, String key) {
        try (var reader = adapters.adapter(connection.provider()).reader(connection, key)) {
            var content = reader.read(PaddleOcrVlClient.Input.of(PICTURE), null, "probe.png", "image/png", MAX_INPUT);
            String text = content == null ? "" : content.normalizedText().toLowerCase(Locale.ROOT);
            return text.contains("memoryos") && text.contains("2026");
        } catch (ExtractionException | RuntimeException failed) {
            LOG.atInfo().addKeyValue("event", "ocr.probe.failed").addKeyValue("provider", connection.provider().name())
                    .addKeyValue("error_type", failed.getClass().getName())
                    .addKeyValue("error_code", failed instanceof ExtractionException extraction
                            ? extraction.failure().name() : "INTERNAL")
                    .log("OCR connection test failed");
            return false;
        }
    }

    private static byte[] picture() {
        try (InputStream stream = Objects.requireNonNull(OcrConnectionProbe.class.getResourceAsStream("/ocr/probe.png"))) {
            return stream.readAllBytes();
        } catch (IOException missing) {
            throw new IllegalStateException("Missing OCR probe picture", missing);
        }
    }
}
