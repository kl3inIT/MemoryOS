package io.memoryos.identity.persistence;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.identity.ActorId;
import io.memoryos.identity.ExternalIdentity;
import io.memoryos.identity.ExternalIdentityBindingConflictException;
import io.memoryos.identity.ExternalIdentityBindingProvisioner.ProvisioningResult;
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
    void resolvesSameSubjectSeparatelyForEachIssuer() {
        var identityA = new ExternalIdentity("https://issuer-a.example", "same-subject");
        var identityB = new ExternalIdentity("https://issuer-b.example", "same-subject");

        store.provision(identityA, ACTOR_A);
        store.provision(identityB, ACTOR_B);

        assertEquals(ACTOR_A, store.resolve(identityA).orElseThrow());
        assertEquals(ACTOR_B, store.resolve(identityB).orElseThrow());
    }

    @Test
    void doesNotResolveUnknownOrDifferentlyCasedIdentity() {
        store.provision(new ExternalIdentity("https://issuer.example", "Subject"), ACTOR_A);

        assertTrue(store.resolve(new ExternalIdentity("https://issuer.example", "unknown")).isEmpty());
        assertTrue(store.resolve(new ExternalIdentity("https://issuer.example", "subject")).isEmpty());
    }

    @Test
    void provisioningSameBindingTwiceIsIdempotent() throws SQLException {
        var identity = new ExternalIdentity("https://issuer.example", "subject");

        assertEquals(ProvisioningResult.CREATED, store.provision(identity, ACTOR_A));
        assertEquals(ProvisioningResult.UNCHANGED, store.provision(identity, ACTOR_A));
        assertEquals(1, countRows("actors"));
        assertEquals(1, countRows("external_identity_bindings"));
    }

    @Test
    void rebindingIdentityFailsAndRollsBackNewActor() throws SQLException {
        var identity = new ExternalIdentity("https://issuer.example", "subject");
        store.provision(identity, ACTOR_A);

        assertThrows(
                ExternalIdentityBindingConflictException.class,
                () -> store.provision(identity, ACTOR_B));

        assertEquals(ACTOR_A, store.resolve(identity).orElseThrow());
        assertEquals(1, countRows("actors"));
        assertEquals(1, countRows("external_identity_bindings"));
    }

    @Test
    void oneActorCanOwnMultipleExternalIdentities() {
        var identityA = new ExternalIdentity("https://issuer-a.example", "subject-a");
        var identityB = new ExternalIdentity("https://issuer-b.example", "subject-b");

        store.provision(identityA, ACTOR_A);
        store.provision(identityB, ACTOR_A);

        assertEquals(ACTOR_A, store.resolve(identityA).orElseThrow());
        assertEquals(ACTOR_A, store.resolve(identityB).orElseThrow());
    }

    @Test
    void actorWithBindingCannotBeDeleted() {
        var identity = new ExternalIdentity("https://issuer.example", "subject");
        store.provision(identity, ACTOR_A);

        assertThrows(SQLException.class, this::deleteActorA);
        assertEquals(ACTOR_A, store.resolve(identity).orElseThrow());
    }

    private int countRows(String table) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
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
