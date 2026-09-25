package io.memoryos.ingestion.extraction;


import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@org.springframework.boot.context.properties.EnableConfigurationProperties({DoclingProperties.class, PaddleOcrVlProperties.class})
public class FileProviderAutoConfiguration {

    @Bean(destroyMethod = "close")
    DoclingSourceContentExtractor fileSourceContentExtractor(DoclingProperties properties,
            PaddleOcrVlProperties paddle, tools.jackson.databind.ObjectMapper mapper) {
        return new DoclingSourceContentExtractor(properties, paddle, mapper);
    }
}
