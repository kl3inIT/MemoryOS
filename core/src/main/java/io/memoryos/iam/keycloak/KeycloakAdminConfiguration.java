package io.memoryos.iam.keycloak;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import jakarta.ws.rs.client.Client;

import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.JacksonProvider;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.spi.ResteasyClientClassicProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KeycloakAdminProperties.class)
class KeycloakAdminConfiguration {

    @Bean(destroyMethod = "close")
    @SuppressWarnings("resource")
    Keycloak keycloakAdminClient(KeycloakAdminProperties properties) {
        Client restClient = ResteasyClientClassicProvider.createClientBuilder()
                .connectTimeout(properties.connectTimeout().toMillis(), MILLISECONDS)
                .connectionCheckoutTimeout(properties.connectionRequestTimeout().toMillis(), MILLISECONDS)
                .readTimeout(properties.readTimeout().toMillis(), MILLISECONDS)
                .build()
                .register(JacksonProvider.class, 100);
        return KeycloakBuilder.builder()
                .serverUrl(properties.serverUrl())
                .realm(properties.realm())
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .clientId(properties.clientId())
                .clientSecret(properties.clientSecret())
                .resteasyClient(restClient)
                .build();
    }
}
