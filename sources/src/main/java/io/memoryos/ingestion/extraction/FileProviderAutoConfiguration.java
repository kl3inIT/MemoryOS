package io.memoryos.ingestion.extraction;


import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

@AutoConfiguration
@EnableConfigurationProperties({DoclingProperties.class, PaddleOcrVlProperties.class})
public class FileProviderAutoConfiguration {

    @Bean(destroyMethod = "close")
    DoclingSourceContentExtractor fileSourceContentExtractor(DoclingProperties properties,
            PaddleOcrVlProperties paddle, ObjectMapper mapper) {
        return new DoclingSourceContentExtractor(properties, paddle, mapper);
    }
}
