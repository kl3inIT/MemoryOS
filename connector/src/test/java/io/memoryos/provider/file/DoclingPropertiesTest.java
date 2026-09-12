package io.memoryos.provider.file;

import static org.junit.jupiter.api.Assertions.*;

import ai.docling.serve.api.convert.request.options.OcrEngine;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class DoclingPropertiesTest {
    @Test
    void bindsPrivateOcrConfigurationWithoutExposingTheKey() {
        var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(java.util.Map.of(
                "memoryos.extraction.docling.endpoint", "http://ocr.internal:5001",
                "memoryos.extraction.docling.api-key", "test-private-key",
                "memoryos.extraction.docling.ocr-engine", "TESSERACT",
                "memoryos.extraction.docling.ocr-languages", "vie,eng",
                "memoryos.extraction.docling.force-ocr", "true"));
        var bound = new org.springframework.boot.context.properties.bind.Binder(source)
                .bind("memoryos.extraction.docling", org.springframework.boot.context.properties.bind.Bindable.of(DoclingProperties.class)).get();
        assertEquals(ai.docling.serve.api.convert.request.options.OcrEngine.TESSERACT, bound.ocrEngine());
        assertEquals(java.util.List.of("vie", "eng"), bound.ocrLanguages());
        assertTrue(bound.forceOcr());
        assertEquals("test-private-key", bound.apiKey());
        assertFalse(bound.toString().contains(bound.apiKey()));
        assertFalse(bound.parserConfiguration(262_144_000).contains(bound.apiKey()));
        assertThrows(IllegalArgumentException.class,
                () -> new DoclingProperties(null, null, null, 0, null, null, false, "injected\r\nheader"));
    }

    @Test
    void rejectsPagesAboveServeLimit() {
        assertThrows(IllegalArgumentException.class, () -> new DoclingProperties(null, null, null, 201, null, null, false, null));
        assertThrows(IllegalArgumentException.class, () -> new DoclingProperties(null, null, null, -1, null, null, false, null));
    }

    @Test
    void rejectsTimeoutAboveApplicationLimitOrNonpositive() {
        for (var timeout : new Duration[]{Duration.ofHours(1).plusNanos(1), Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThrows(IllegalArgumentException.class, () -> new DoclingProperties(null, null, timeout, 200, null, null, false, null));
        }
    }

    @Test
    void rejectsUnsupportedOcrEngines() {
        for (var engine : new OcrEngine[]{OcrEngine.AUTO, OcrEngine.OCRMAC, OcrEngine.TESSEROCR}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DoclingProperties(null, null, null, 200, engine, null, false, null));
        }
    }

    @Test
    void rejectsBlankOrNullOcrLanguages() {
        for (var language : new String[]{null, "", " \t"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DoclingProperties(null, null, null, 200, OcrEngine.TESSERACT, Arrays.asList("vie", language), false, null));
        }
    }

    @Test
    void callerCannotMutateEffectiveLanguagesOrParserFingerprint() {
        var languages = new ArrayList<>(List.of("vie", "eng"));
        var properties = new DoclingProperties(null, null, null, 200, OcrEngine.TESSERACT, languages, false, null);
        var fingerprint = properties.parserConfiguration(ObjectUploadSpecification.MAX_SIZE_BYTES);

        languages.set(0, "deu");
        languages.add("fra");

        assertEquals(List.of("vie", "eng"), properties.ocrLanguages());
        assertEquals(fingerprint, properties.parserConfiguration(ObjectUploadSpecification.MAX_SIZE_BYTES));
    }

    @Test
    void credentialsAndEndpointStayOutOfDiagnosticsAndParserIdentity() {
        var endpoint = URI.create("http://docling.internal:5001");
        var original = new DoclingProperties(endpoint, null, null, 200, null, null, false, "first-test-api-key");
        var rotated = new DoclingProperties(URI.create("http://replacement.internal:5001"),
                null, null, 200, null, null, false, "rotated-test-api-key");
        var unauthenticated = new DoclingProperties(endpoint, null, null, 200, null, null, false, null);

        for (var properties : List.of(original, rotated)) {
            assertFalse(properties.toString().contains(properties.apiKey()));
            assertFalse(properties.parserConfiguration(ObjectUploadSpecification.MAX_SIZE_BYTES).contains(properties.apiKey()));
            assertFalse(properties.parserConfiguration(ObjectUploadSpecification.MAX_SIZE_BYTES).contains(properties.endpoint().toString()));
            assertFalse(properties.parserConfiguration(ObjectUploadSpecification.MAX_SIZE_BYTES).contains(properties.endpoint().getHost()));
        }
        assertEquals(original.parserConfiguration(ObjectUploadSpecification.MAX_SIZE_BYTES),
                rotated.parserConfiguration(ObjectUploadSpecification.MAX_SIZE_BYTES));
        assertEquals(original.parserConfiguration(ObjectUploadSpecification.MAX_SIZE_BYTES),
                unauthenticated.parserConfiguration(ObjectUploadSpecification.MAX_SIZE_BYTES));
    }

    @Test
    void invalidHeaderCharactersAreRejectedWithoutExposingTheKey() {
        for (char character : new char[]{0, 1, '\n', '\r', 0x1f, 0x7f, 0x100}) {
            var key = "sensitive-test-key" + character + "suffix";
            var error = assertThrows(IllegalArgumentException.class,
                    () -> new DoclingProperties(null, null, null, 200, null, null, false, key));

            assertFalse(error.toString().contains("sensitive-test-key"));
            assertFalse(error.toString().contains("suffix"));
            assertNull(error.getCause());
        }
    }
}
