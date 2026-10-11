package io.memoryos.ingestion.extraction;

import io.memoryos.ai.ocr.OcrProbe;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/**
 * The OCR connection test, which the API runs although it extracts nothing. A test waits a minute at most, where a
 * document's extraction waits as long as a 200-page scan takes.
 */
@AutoConfiguration
@EnableConfigurationProperties(PaddleOcrVlProperties.class)
public class OcrProbeAutoConfiguration {
    private static final Duration TEST_TIMEOUT = Duration.ofMinutes(1);

    @Bean
    OcrProbe ocrProbe(PaddleOcrVlProperties paddle, ObjectMapper mapper) {
        return new OcrConnectionProbe(OcrAdapterRegistry.installed(new PaddleOcrVlProperties(TEST_TIMEOUT,
                paddle.maxPages(), paddle.revision(), paddle.maxConcurrentRequests()), mapper));
    }
}
