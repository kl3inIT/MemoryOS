package io.memoryos.identity;

import io.memoryos.identity.persistence.JdbcExternalIdentityRegistrar;
import io.memoryos.identity.persistence.JdbcExternalIdentityResolver;

import java.util.Objects;

import org.springframework.jdbc.core.simple.JdbcClient;

public final class IdentityPersistence {

    private IdentityPersistence() {
    }

    public static ExternalIdentityResolver resolver(JdbcClient jdbcClient) {
        return new JdbcExternalIdentityResolver(Objects.requireNonNull(jdbcClient, "jdbcClient must not be null"));
    }

    public static ExternalIdentityRegistrar registrar(
            JdbcClient jdbcClient,
            ExternalIdentityResolver resolver
    ) {
        return new JdbcExternalIdentityRegistrar(
                Objects.requireNonNull(jdbcClient, "jdbcClient must not be null"),
                Objects.requireNonNull(resolver, "resolver must not be null")
        );
    }
}
