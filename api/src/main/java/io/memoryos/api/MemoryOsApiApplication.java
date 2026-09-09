package io.memoryos.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
        scanBasePackages = "io.memoryos",
        excludeName = {
                "io.memoryos.provider.file.FileProviderAutoConfiguration",
                "io.memoryos.provider.SourceContentExtractorAutoConfiguration"
        }
)
public class MemoryOsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemoryOsApiApplication.class, args);
    }
}
