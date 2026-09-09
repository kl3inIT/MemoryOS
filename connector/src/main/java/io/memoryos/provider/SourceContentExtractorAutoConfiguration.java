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
    @Bean
    @ConditionalOnMissingBean(SourceContentExtractor.class)
    SourceContentExtractor sourceContentExtractor(DoclingSourceContentExtractor docling, ObjectMapper mapper) {
        return new SourceContentExtractorRouter(docling, mapper);
    }
}
