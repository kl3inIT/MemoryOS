package io.memoryos.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
public class MemoryOsWorkerApplication {

    static void main(String[] args) {
        SpringApplication.run(MemoryOsWorkerApplication.class, args);
    }
}
