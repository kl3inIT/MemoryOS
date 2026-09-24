package io.memoryos.worker;

import io.memoryos.iam.group.DefaultGroupScopeService;
import io.memoryos.iam.group.DefaultIamAuthorization;
import io.memoryos.iam.group.IamAuditReaders;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

@Configuration(proxyBeanMethods = false)
@EntityScan("io.memoryos.iam")
@EnableJpaRepositories(basePackages = "io.memoryos.iam")
@Import({DefaultIamAuthorization.class, DefaultGroupScopeService.class, IamAuditReaders.class})
class IamPersistenceConfiguration {

    @Bean
    EntityManager iamEntityManager(EntityManagerFactory entityManagerFactory) {
        return SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
    }
}
