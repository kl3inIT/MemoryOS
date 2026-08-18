package io.memoryos.identity.persistence;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.identity.ActorId;
import io.memoryos.identity.ExternalIdentity;
import java.io.IOException;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class JdbcExternalIdentityStoreTest {

    private static final ActorId ACTOR_A = actor("00000000-0000-0000-0000-000000000001");
    private static final ActorId ACTOR_B = actor("00000000-0000-0000-0000-000000000002");

    private DataSource dataSource;
    private JdbcExternalIdentityStore store;

    @BeforeEach
    void setUp() throws Exception {
        var database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        database.setUser("sa");
        dataSource = database;
        applyIdentityMigration(dataSource);
        store = new JdbcExternalIdentityStore(dataSource);
    }

    @Test
    void resolvesSameSubjectSeparatelyForEachIssuer() throws SQLException {
        var identityA = new ExternalIdentity("https://issuer-a.example", "same-subject");
        var identityB = new ExternalIdentity("https://issuer-b.example", "same-subject");

        persistBinding(identityA, ACTOR_A);
        persistBinding(identityB, ACTOR_B);

        assertEquals(ACTOR_A, store.resolve(identityA).orElseThrow());
        assertEquals(ACTOR_B, store.resolve(identityB).orElseThrow());
    }

    @Test
    void doesNotResolveUnknownOrDifferentlyCasedIdentity() throws SQLException {
        persistBinding(new ExternalIdentity("https://issuer.example", "Subject"), ACTOR_A);

        assertTrue(store.resolve(new ExternalIdentity("https://issuer.example", "unknown")).isEmpty());
        assertTrue(store.resolve(new ExternalIdentity("https://issuer.example", "subject")).isEmpty());
    }

    @Test
    void exactIdentityCanOnlyBelongToOneActor() throws SQLException {
        var identity = new ExternalIdentity("https://issuer.example", "subject");
        persistBinding(identity, ACTOR_A);
        insertActor(ACTOR_B);

        assertThrows(SQLException.class, () -> insertBinding(identity, ACTOR_B));
        assertEquals(ACTOR_A, store.resolve(identity).orElseThrow());
    }

    @Test
    void bindingRequiresExistingActor() {
        var identity = new ExternalIdentity("https://issuer.example", "subject");

        assertThrows(SQLException.class, () -> insertBinding(identity, ACTOR_A));
    }

    @Test
    void oneActorCanOwnMultipleExternalIdentities() throws SQLException {
        var identityA = new ExternalIdentity("https://issuer-a.example", "subject-a");
        var identityB = new ExternalIdentity("https://issuer-b.example", "subject-b");

        persistBinding(identityA, ACTOR_A);
        persistBinding(identityB, ACTOR_A);

        assertEquals(ACTOR_A, store.resolve(identityA).orElseThrow());
        assertEquals(ACTOR_A, store.resolve(identityB).orElseThrow());
    }

    @Test
    void actorWithBindingCannotBeDeleted() throws SQLException {
        var identity = new ExternalIdentity("https://issuer.example", "subject");
        persistBinding(identity, ACTOR_A);

        assertThrows(SQLException.class, this::deleteActorA);
        assertEquals(ACTOR_A, store.resolve(identity).orElseThrow());
    }

    private void persistBinding(ExternalIdentity identity, ActorId actorId) throws SQLException {
        insertActor(actorId);
        insertBinding(identity, actorId);
    }

    private void insertActor(ActorId actorId) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "INSERT INTO actors (id) VALUES (?) ON CONFLICT DO NOTHING")) {
            statement.setObject(1, actorId.value());
            statement.executeUpdate();
        }
    }

    private void insertBinding(ExternalIdentity identity, ActorId actorId) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "INSERT INTO external_identity_bindings (issuer, subject, actor_id) VALUES (?, ?, ?)")) {
            statement.setString(1, identity.issuer());
            statement.setString(2, identity.subject());
            statement.setObject(3, actorId.value());
            statement.executeUpdate();
        }
    }

    private void deleteActorA() throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("DELETE FROM actors WHERE id = ?")) {
            statement.setObject(1, ACTOR_A.value());
            statement.executeUpdate();
        }
    }

    private static void applyIdentityMigration(DataSource dataSource) throws SQLException, IOException {
        try (var stream = JdbcExternalIdentityStoreTest.class.getResourceAsStream(
                        "/db/migration/V1__create_identity_tables.sql")) {
            if (stream == null) {
                throw new IllegalStateException("identity migration resource is missing");
            }
            var migration = new String(stream.readAllBytes(), UTF_8);
            try (var connection = dataSource.getConnection();
                    var statement = connection.createStatement()) {
                for (var sql : migration.split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
            }
        }
    }

    private static ActorId actor(String value) {
        return new ActorId(UUID.fromString(value));
    }
}
