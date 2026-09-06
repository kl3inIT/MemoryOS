package io.memoryos.provider.file;


import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@org.springframework.boot.context.properties.EnableConfigurationProperties(DoclingProperties.class)
public class FileProviderAutoConfiguration {

    @Bean(destroyMethod = "close")
    DoclingSourceContentExtractor fileSourceContentExtractor(DoclingProperties properties,
            tools.jackson.databind.ObjectMapper mapper) {
        return new DoclingSourceContentExtractor(properties, mapper);
    }
}
