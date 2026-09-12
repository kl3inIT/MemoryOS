package io.memoryos.api;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration(proxyBeanMethods = false)
@EntityScan({"io.memoryos.iam.persistence", "io.memoryos.chat.persistence"})
@EnableJpaRepositories(basePackages = {"io.memoryos.chat.persistence", "io.memoryos.iam.persistence"})
class IamPersistenceConfiguration {

    @Bean
    EntityManager iamEntityManager(EntityManagerFactory entityManagerFactory) {
        return SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
    }
}
