package io.memoryos.channels;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/** The shared {@link EntityManager} constructors take; the composition names the entities and repositories. */
@Configuration(proxyBeanMethods = false)
class PersistenceConfiguration {
    @Bean
    EntityManager sharedEntityManager(EntityManagerFactory entityManagerFactory) {
        return SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
    }
}
