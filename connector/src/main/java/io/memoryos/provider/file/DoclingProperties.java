package io.memoryos.provider.file;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memoryos.extraction.docling")
public record DoclingProperties(URI endpoint, String engineRevision, Duration timeout, int maxPages,
        String apiKey, ai.docling.serve.api.convert.request.options.OcrEngine ocrEngine, java.util.List<String> ocrLanguages) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public DoclingProperties {
        endpoint = endpoint == null ? URI.create("http://localhost:5001") : endpoint;
        engineRevision = engineRevision == null
                ? "sha256:576fc2074ac77bcfbf3fe27633aa0dd89b452a170b2cd31689c8751e94d60f7a" : engineRevision;
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        maxPages = maxPages == 0 ? 200 : maxPages;
        apiKey = apiKey == null ? "" : apiKey;
        ocrEngine = ocrEngine == null ? ai.docling.serve.api.convert.request.options.OcrEngine.EASYOCR : ocrEngine;
        ocrLanguages = ocrLanguages == null || ocrLanguages.isEmpty() ? java.util.List.of("vi", "en") : java.util.List.copyOf(ocrLanguages);
        if (!java.util.Set.of("http", "https").contains(endpoint.getScheme())
                || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(15)) > 0
                || maxPages < 1 || maxPages > 200 || engineRevision.isBlank() || engineRevision.length() > 200
                || apiKey.length() > 4096 || apiKey.chars().anyMatch(c -> c < 32 || c > 126)
                || ocrLanguages.size() > 16 || ocrLanguages.stream().anyMatch(lang -> !lang.matches("[A-Za-z0-9_-]{1,32}"))) {
            throw new IllegalArgumentException("invalid Docling extraction configuration");
        }
    }

    public DoclingProperties(URI endpoint, String engineRevision, Duration timeout, int maxPages) {
        this(endpoint, engineRevision, timeout, maxPages, null, null, null);
    }

    ai.docling.serve.api.convert.request.options.ConvertDocumentOptions options() {
        return ai.docling.serve.api.convert.request.options.ConvertDocumentOptions.builder()
                .toFormat(ai.docling.serve.api.convert.request.options.OutputFormat.JSON)
                .toFormat(ai.docling.serve.api.convert.request.options.OutputFormat.TEXT)
                .doOcr(true).forceOcr(false).ocrEngine(ocrEngine).ocrLang(ocrLanguages)
                .doTableStructure(true).tableMode(ai.docling.serve.api.convert.request.options.TableFormerMode.ACCURATE)
                .includeImages(true).imageExportMode(ai.docling.serve.api.convert.request.options.ImageRefMode.EMBEDDED)
                .documentTimeout(timeout).abortOnError(true).build();
    }

    String parserConfiguration(long maxInput) {
        return "memoryos-extraction-v1;docling-java=0.6.5;engine=" + engineRevision
                + ";ocr=" + ocrEngine.name().toLowerCase(java.util.Locale.ROOT) + ":" + String.join(",", ocrLanguages)
                + ";force=false;tables=accurate;images=embedded;maxPages=" + maxPages
                + ";timeoutSeconds=" + timeout.toSeconds() + ";maxInput=" + maxInput + ";maxOutput=33554432;native=tika-4.0.0";
    }

    @Override public @org.jspecify.annotations.NonNull String toString() {
        return "DoclingProperties[endpoint=" + endpoint + ", engineRevision=" + engineRevision
                + ", timeout=" + timeout + ", maxPages=" + maxPages + ", apiKey=[redacted], ocrEngine="
                + ocrEngine + ", ocrLanguages=" + ocrLanguages + "]";
    }
}
