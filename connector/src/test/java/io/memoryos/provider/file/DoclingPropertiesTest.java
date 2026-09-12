package io.memoryos.provider.file;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DoclingPropertiesTest {
    @Test
    void bindsPrivateOcrConfigurationWithoutChangingDefaultsOrExposingTheKey() {
        var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(java.util.Map.of(
                "memoryos.extraction.docling.endpoint", "http://ocr.internal:5001",
                "memoryos.extraction.docling.api-key", "test-private-key",
                "memoryos.extraction.docling.ocr-engine", "TESSERACT",
                "memoryos.extraction.docling.ocr-languages", "vie,eng"));
        var bound = new org.springframework.boot.context.properties.bind.Binder(source)
                .bind("memoryos.extraction.docling", org.springframework.boot.context.properties.bind.Bindable.of(DoclingProperties.class)).get();
        assertEquals(ai.docling.serve.api.convert.request.options.OcrEngine.TESSERACT, bound.ocrEngine());
        assertEquals(java.util.List.of("vie", "eng"), bound.ocrLanguages());
        assertEquals("test-private-key", bound.apiKey());
        assertFalse(bound.toString().contains(bound.apiKey()));
        assertFalse(bound.parserConfiguration(262_144_000).contains(bound.apiKey()));
        var defaults = new DoclingProperties(null, null, null, 0);
        assertEquals(ai.docling.serve.api.convert.request.options.OcrEngine.EASYOCR, defaults.ocrEngine());
        assertEquals(java.util.List.of("vi", "en"), defaults.ocrLanguages());
        assertThrows(IllegalArgumentException.class,
                () -> new DoclingProperties(null, null, null, 0, "injected\r\nheader", null, null));
    }

    @Test
    void rejectsPagesAboveServeLimit() {
        assertThrows(IllegalArgumentException.class, () -> new DoclingProperties(null, null, null, 201));
        assertThrows(IllegalArgumentException.class, () -> new DoclingProperties(null, null, null, -1));
    }

    @Test
    void rejectsTimeoutAboveApplicationLimitOrNonpositive() {
        for (var timeout : new Duration[]{Duration.ofMinutes(15).plusNanos(1), Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThrows(IllegalArgumentException.class, () -> new DoclingProperties(null, null, timeout, 200));
        }
    }
}
