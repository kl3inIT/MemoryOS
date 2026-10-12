package io.memoryos.worker;

import io.memoryos.iam.IamWorkerComponents;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

@Configuration(proxyBeanMethods = false)
// MEM-243: the AI catalog's persistence holds the OCR connections the Worker reads for each document.
@EntityScan({"io.memoryos.iam", "io.memoryos.ai.persistence"})
@EnableJpaRepositories(basePackages = {"io.memoryos.iam", "io.memoryos.ai.persistence"})
@Import(IamWorkerComponents.class)
class IamPersistenceConfiguration {

    @Bean
    EntityManager iamEntityManager(EntityManagerFactory entityManagerFactory) {
        return SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
    }
}
