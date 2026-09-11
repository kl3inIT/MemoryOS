package io.memoryos.provider.file;

import ai.docling.serve.api.convert.request.options.OcrEngine;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memoryos.extraction.docling")
public record DoclingProperties(URI endpoint, String engineRevision, Duration timeout, int maxPages,
                                OcrEngine ocrEngine, List<String> ocrLanguages, boolean forceOcr, String apiKey) {
    public DoclingProperties {
        apiKey = apiKey == null ? "" : apiKey;
        for (int i = 0; i < apiKey.length(); i++) {
            char character = apiKey.charAt(i);
            if ((character < 0x20 && character != '\t') || character == 0x7f || character > 0xff) {
                throw new IllegalArgumentException("invalid Docling API key header value");
            }
        }
        endpoint = endpoint == null ? URI.create("http://localhost:5001") : endpoint;
        engineRevision = engineRevision == null
                ? "sha256:576fc2074ac77bcfbf3fe27633aa0dd89b452a170b2cd31689c8751e94d60f7a" : engineRevision;
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        maxPages = maxPages == 0 ? 200 : maxPages;
        ocrEngine = ocrEngine == null ? OcrEngine.EASYOCR : ocrEngine;
        if (ocrEngine != OcrEngine.EASYOCR && ocrEngine != OcrEngine.RAPIDOCR && ocrEngine != OcrEngine.TESSERACT) {
            throw new IllegalArgumentException("unsupported Docling OCR engine");
        }
        if (ocrLanguages == null || ocrLanguages.isEmpty()) {
            ocrLanguages = switch (ocrEngine) {
                case TESSERACT -> List.of("vie", "eng");
                case RAPIDOCR -> List.of("vi");
                default -> List.of("vi", "en");
            };
        }
        for (String language : ocrLanguages) {
            if (language == null || language.isBlank()) {
                throw new IllegalArgumentException("invalid Docling OCR language");
            }
        }
        ocrLanguages = List.copyOf(ocrLanguages);
        if (!java.util.Set.of("http", "https").contains(endpoint.getScheme())
                || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofHours(1)) > 0
                || maxPages < 1 || maxPages > 200 || engineRevision.isBlank() || engineRevision.length() > 200) {
            throw new IllegalArgumentException("invalid Docling extraction configuration");
        }
    }

    String parserConfiguration() {
        return "memoryos-extraction-v2;docling-java=0.6.5;engine=" + engineRevision
                + ";ocr=" + ocrEngine.name().toLowerCase(Locale.ROOT) + ":" + String.join(",", ocrLanguages)
                + ";force=" + forceOcr + ";tables=accurate;images=embedded;maxPages=" + maxPages
                + ";timeoutSeconds=" + timeout.toSeconds() + ";maxInput=" + ObjectUploadSpecification.MAX_SIZE_BYTES
                + ";maxOutput=33554432;native=tika-4.0.0"
                + ";tableText=sparse-offsets-v1;financialChecks=cash-flow-v1";
    }

    @Override public String toString() { return "DoclingProperties[redacted]"; }
}
