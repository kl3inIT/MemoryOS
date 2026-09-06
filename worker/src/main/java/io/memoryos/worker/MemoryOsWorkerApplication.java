package io.memoryos.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "io.memoryos.worker",
        "io.memoryos.connector",
        "io.memoryos.objectstorage",
        "io.memoryos.document",
        "io.memoryos.ingestion",
        "io.memoryos.iam.persistence"
})
public class MemoryOsWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemoryOsWorkerApplication.class, args);
    }
}
