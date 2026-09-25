package io.memoryos.provider;

import io.memoryos.ingestion.SourceContentExtractor;
import io.memoryos.provider.file.DoclingSourceContentExtractor;
import io.memoryos.provider.file.FileProviderAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

@AutoConfiguration(after = FileProviderAutoConfiguration.class)
@ConditionalOnBean(DoclingSourceContentExtractor.class)
public class SourceContentExtractorAutoConfiguration {
    @org.springframework.context.annotation.Bean
    io.memoryos.ingestion.ChatFileExtractor chatFileExtractor(DoclingSourceContentExtractor docling, ObjectMapper mapper) {
        return new io.memoryos.provider.file.BoundedChatFileExtractor(docling, mapper);
    }
    @Bean
    @ConditionalOnMissingBean(SourceContentExtractor.class)
    SourceContentExtractor sourceContentExtractor(DoclingSourceContentExtractor docling, ObjectMapper mapper) {
        return new SourceContentExtractorRouter(docling, mapper);
    }
}
