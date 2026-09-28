package io.memoryos.ingestion.extraction;

import io.memoryos.ingestion.ChatFileExtractor;
import io.memoryos.ingestion.SourceContentExtractor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

@AutoConfiguration(after = FileProviderAutoConfiguration.class)
@ConditionalOnBean(DoclingSourceContentExtractor.class)
public class SourceContentExtractorAutoConfiguration {
    @Bean
    ChatFileExtractor chatFileExtractor(DoclingSourceContentExtractor docling, ObjectMapper mapper) {
        return new BoundedChatFileExtractor(docling, mapper);
    }
    @Bean
    @ConditionalOnMissingBean(SourceContentExtractor.class)
    SourceContentExtractor sourceContentExtractor(DoclingSourceContentExtractor docling, ObjectMapper mapper) {
        return new SourceContentExtractorRouter(docling, mapper);
    }
}
