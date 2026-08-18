package io.memoryos.identity.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.identity.ActorId;
import io.memoryos.identity.ExternalIdentity;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class JdbcExternalIdentityResolverTest {

    private static final ActorId ACTOR_A = actor("00000000-0000-0000-0000-000000000001");
    private static final ActorId ACTOR_B = actor("00000000-0000-0000-0000-000000000002");

    private JdbcClient jdbcClient;
    private JdbcExternalIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__create_identity_tables.sql"))
                .execute(dataSource);
        jdbcClient = JdbcClient.create(dataSource);
        resolver = new JdbcExternalIdentityResolver(jdbcClient);
    }

    @Test
    void resolvesSameSubjectSeparatelyForEachIssuer() {
        var identityA = new ExternalIdentity("https://issuer-a.example", "same-subject");
        var identityB = new ExternalIdentity("https://issuer-b.example", "same-subject");

        persistBinding(identityA, ACTOR_A);
        persistBinding(identityB, ACTOR_B);

        assertEquals(ACTOR_A, resolver.resolve(identityA).orElseThrow());
        assertEquals(ACTOR_B, resolver.resolve(identityB).orElseThrow());
    }

    @Test
    void doesNotResolveUnknownOrDifferentlyCasedIdentity() {
        persistBinding(new ExternalIdentity("https://issuer.example", "Subject"), ACTOR_A);

        assertTrue(resolver.resolve(new ExternalIdentity("https://issuer.example", "unknown")).isEmpty());
        assertTrue(resolver.resolve(new ExternalIdentity("https://issuer.example", "subject")).isEmpty());
    }

    @Test
    void exactIdentityCanOnlyBelongToOneActor() {
        var identity = new ExternalIdentity("https://issuer.example", "subject");
        persistBinding(identity, ACTOR_A);
        insertActor(ACTOR_B);

        assertThrows(DuplicateKeyException.class, () -> insertBinding(identity, ACTOR_B));
        assertEquals(ACTOR_A, resolver.resolve(identity).orElseThrow());
    }

    @Test
    void bindingRequiresExistingActor() {
        var identity = new ExternalIdentity("https://issuer.example", "subject");

        assertThrows(DataIntegrityViolationException.class, () -> insertBinding(identity, ACTOR_A));
    }

    @Test
    void oneActorCanOwnMultipleExternalIdentities() {
        var identityA = new ExternalIdentity("https://issuer-a.example", "subject-a");
        var identityB = new ExternalIdentity("https://issuer-b.example", "subject-b");

        persistBinding(identityA, ACTOR_A);
        persistBinding(identityB, ACTOR_A);

        assertEquals(ACTOR_A, resolver.resolve(identityA).orElseThrow());
        assertEquals(ACTOR_A, resolver.resolve(identityB).orElseThrow());
    }

    @Test
    void actorWithBindingCannotBeDeleted() {
        var identity = new ExternalIdentity("https://issuer.example", "subject");
        persistBinding(identity, ACTOR_A);

        assertThrows(DataIntegrityViolationException.class, this::deleteActorA);
        assertEquals(ACTOR_A, resolver.resolve(identity).orElseThrow());
    }

    private void persistBinding(ExternalIdentity identity, ActorId actorId) {
        insertActor(actorId);
        insertBinding(identity, actorId);
    }

    private void insertActor(ActorId actorId) {
        jdbcClient
                .sql("INSERT INTO actors (id) VALUES (:actorId) ON CONFLICT DO NOTHING")
                .param("actorId", actorId.value())
                .update();
    }

    private void insertBinding(ExternalIdentity identity, ActorId actorId) {
        jdbcClient
                .sql("""
                        INSERT INTO external_identity_bindings (issuer, subject, actor_id)
                        VALUES (:issuer, :subject, :actorId)
                        """)
                .param("issuer", identity.issuer())
                .param("subject", identity.subject())
                .param("actorId", actorId.value())
                .update();
    }

    private void deleteActorA() {
        jdbcClient
                .sql("DELETE FROM actors WHERE id = :actorId")
                .param("actorId", ACTOR_A.value())
                .update();
    }

    private static ActorId actor(String value) {
        return new ActorId(UUID.fromString(value));
    }
}
