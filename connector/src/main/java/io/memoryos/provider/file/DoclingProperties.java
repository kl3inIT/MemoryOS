package io.memoryos.provider.file;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memoryos.extraction.docling")
public record DoclingProperties(URI endpoint, String engineRevision, Duration timeout, int maxPages) {
    public DoclingProperties {
        endpoint = endpoint == null ? URI.create("http://localhost:5001") : endpoint;
        engineRevision = engineRevision == null
                ? "sha256:576fc2074ac77bcfbf3fe27633aa0dd89b452a170b2cd31689c8751e94d60f7a" : engineRevision;
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        maxPages = maxPages == 0 ? 200 : maxPages;
        if (!java.util.Set.of("http", "https").contains(endpoint.getScheme())
                || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(300)) > 0
                || maxPages < 1 || maxPages > 200 || engineRevision.isBlank() || engineRevision.length() > 200) {
            throw new IllegalArgumentException("invalid Docling extraction configuration");
        }
    }

    String parserConfiguration() {
        return "memoryos-extraction-v1;docling-java=0.6.5;engine=" + engineRevision
                + ";ocr=easyocr:vi,en;force=false;tables=accurate;images=embedded;maxPages=" + maxPages
                + ";timeoutSeconds=" + timeout.toSeconds() + ";maxInput=10485760;maxOutput=33554432;native=tika-4.0.0";
    }
}
