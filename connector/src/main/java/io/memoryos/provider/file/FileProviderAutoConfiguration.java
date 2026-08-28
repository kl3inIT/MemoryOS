package io.memoryos.provider.file;


import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class FileProviderAutoConfiguration {

    @Bean(destroyMethod = "close")
    TikaSourceContentExtractor fileSourceContentExtractor() {
        return new TikaSourceContentExtractor();
    }
}
