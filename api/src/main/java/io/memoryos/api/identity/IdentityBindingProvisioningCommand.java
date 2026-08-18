package io.memoryos.api.identity;

import io.memoryos.identity.ActorId;
import io.memoryos.identity.ExternalIdentity;
import io.memoryos.identity.ExternalIdentityBindingProvisioner;
import io.memoryos.identity.persistence.JdbcExternalIdentityStore;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

public final class IdentityBindingProvisioningCommand {

    private static final String PROFILE = "identity-binding-provisioning";

    private IdentityBindingProvisioningCommand() {
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(ProvisioningConfiguration.class)
                .profiles(PROFILE)
                .properties("spring.config.name=identity-binding-provisioning")
                .web(WebApplicationType.NONE)
                .run(args);
    }

    @Profile(PROFILE)
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableConfigurationProperties(ProvisioningProperties.class)
    static class ProvisioningConfiguration {

        private static final Logger LOGGER = LoggerFactory.getLogger(ProvisioningConfiguration.class);

        @Bean
        JdbcExternalIdentityStore externalIdentityStore(DataSource dataSource) {
            return new JdbcExternalIdentityStore(dataSource);
        }

        @Bean
        ApplicationRunner provisionIdentityBinding(
                ExternalIdentityBindingProvisioner provisioner,
                ProvisioningProperties properties,
                ConfigurableApplicationContext context) {
            return ignored -> {
                var result = provisioner.provision(
                        new ExternalIdentity(properties.issuer(), properties.subject()),
                        new ActorId(properties.actorId()));
                LOGGER.info("Identity binding provisioning completed: action={}", result.name().toLowerCase());
                context.close();
            };
        }
    }

    @ConfigurationProperties("memoryos.identity.provision")
    record ProvisioningProperties(String issuer, String subject, UUID actorId) {

        ProvisioningProperties {
            requireText(issuer, "issuer");
            requireText(subject, "subject");
            Objects.requireNonNull(actorId, "actorId must not be null");
        }

        private static void requireText(String value, String field) {
            Objects.requireNonNull(value, field + " must not be null");
            if (value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
        }
    }
}
