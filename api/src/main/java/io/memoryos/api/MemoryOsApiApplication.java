package io.memoryos.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.memoryos")
public class MemoryOsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemoryOsApiApplication.class, args);
    }
}
