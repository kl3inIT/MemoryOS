package io.memoryos.ingestion.extraction;

import io.memoryos.ai.ocr.OcrConnectionService;
import io.memoryos.ai.ocr.OcrPageReader;
import io.memoryos.ai.ocr.OcrServiceUsage;
import io.memoryos.connector.SourceSnapshotReader;
import io.memoryos.ingestion.ChatFileExtractor;
import io.memoryos.ingestion.SourceContentExtractor;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

@AutoConfiguration(after = FileProviderAutoConfiguration.class)
@ConditionalOnBean(DoclingSourceContentExtractor.class)
public class SourceContentExtractorAutoConfiguration {
    @Bean(destroyMethod = "close")
    OcrReaders ocrReaders(OcrConnectionService connections, PaddleOcrVlProperties paddle, OcrPageReader pages,
            OcrServiceUsage usage, ObjectMapper mapper) {
        return new OcrReaders(connections, OcrAdapterRegistry.installed(paddle, pages, usage, mapper));
    }
    @Bean
    ChatFileExtractor chatFileExtractor(DoclingSourceContentExtractor docling, OcrReaders ocr, ObjectMapper mapper) {
        return new BoundedChatFileExtractor(docling, ocr::forTenant, mapper);
    }
    @Bean
    @ConditionalOnMissingBean(SourceContentExtractor.class)
    SourceContentExtractor sourceContentExtractor(DoclingSourceContentExtractor docling, OcrReaders ocr,
            ObjectMapper mapper, List<SourceSnapshotReader> snapshotReaders) {
        return new SourceContentExtractorRouter(docling, ocr::forTenant, mapper, snapshotReaders).requireEveryFormat();
    }
}
