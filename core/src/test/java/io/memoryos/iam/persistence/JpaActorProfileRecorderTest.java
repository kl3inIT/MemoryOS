package io.memoryos.iam.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.TestDatabase.JpaHarness;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.ExternalIdentity;
import jakarta.persistence.LockModeType;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

// SQL is exercised against the isolated, migrated Testcontainers database.
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
@Testcontainers
class JpaActorProfileRecorderTest {

    private static final ExternalIdentity IDENTITY_A = new ExternalIdentity(
            "https://issuer-a.example",
            "subject-a"
    );
    private static final ExternalIdentity IDENTITY_B = new ExternalIdentity(
            "https://issuer-b.example",
            "subject-b"
    );
    private static final Instant FIRST_OBSERVATION = Instant.parse("2026-09-06T10:00:00Z");
    private static final Instant SECOND_OBSERVATION = Instant.parse("2026-09-06T11:00:00Z");

    private JdbcClient jdbcClient;
    private JpaHarness jpa;
    private HikariDataSource dataSource;
    private JpaExternalIdentityRegistry identities;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = TestDatabase.freshPostgres();
        jdbcClient = JdbcClient.create(dataSource);
        jpa = TestDatabase.jpa(dataSource);
        identities = new JpaExternalIdentityRegistry(jpa.entityManager());
        transaction = new TransactionTemplate(jpa.transactionManager());
    }

    @AfterEach
    void closeDatabase() {
        try {
            if (jpa != null) {
                jpa.close();
            }
        } finally {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }

    @Test
    void recordsLatestTruthfulProfileWithExactBindingProvenance() {
        ActorId actorId = transaction.execute(_ -> identities.resolveOrCreate(IDENTITY_A));
        var actors = actorRepository();
        assertEquals("vi", transaction.execute(_ -> actors.findById(actorId.value()).orElseThrow().getUiLanguage()));
        transaction.executeWithoutResult(_ -> actors.refreshForUpdate(actorId.value()).setUiLanguage("en"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbcClient.sql("UPDATE actors SET ui_language = 'fr' WHERE id = :id").param("id", actorId.value()).update());
        transaction.executeWithoutResult(_ -> {
            ActorEntity actor = jpa.entityManager().find(ActorEntity.class, actorId.value());
            jpa.entityManager().persist(new ExternalIdentityBindingEntity(
                    new ExternalIdentityBindingId(IDENTITY_B.issuer(), IDENTITY_B.subject()),
                    actor
            ));
        });

        var firstRecorder = new JpaActorProfileRecorder(
                jpa.entityManager(),
                Clock.fixed(FIRST_OBSERVATION, ZoneOffset.UTC)
        );
        transaction.executeWithoutResult(_ -> firstRecorder.record(
                actorId,
                IDENTITY_A,
                "  Ada Lovelace  ",
                "  Ada@Example.COM  ",
                true
        ));
        Profile first = profile(actorId);
        assertEquals(IDENTITY_A.issuer(), first.issuer());
        assertEquals(IDENTITY_A.subject(), first.subject());
        assertEquals("Ada Lovelace", first.displayName());
        assertEquals("Ada@Example.COM", first.email());
        assertTrue(first.emailVerified());
        assertEquals(FIRST_OBSERVATION, first.observedAt());

        var secondRecorder = new JpaActorProfileRecorder(
                jpa.entityManager(),
                Clock.fixed(SECOND_OBSERVATION, ZoneOffset.UTC)
        );
        transaction.executeWithoutResult(_ -> secondRecorder.record(actorId, IDENTITY_B, " ", null, false));
        assertEquals("en", transaction.execute(_ -> actors.findById(actorId.value()).orElseThrow().getUiLanguage()), "IdP observations must not overwrite account language");
        Profile latest = profile(actorId);
        assertEquals(IDENTITY_B.issuer(), latest.issuer());
        assertEquals(IDENTITY_B.subject(), latest.subject());
        assertNull(latest.displayName());
        assertNull(latest.email());
        assertFalse(latest.emailVerified());
        assertEquals(SECOND_OBSERVATION, latest.observedAt());

        jdbcClient.sql("DELETE FROM external_identity_bindings WHERE issuer = :issuer AND subject = :subject")
                .param("issuer", IDENTITY_B.issuer())
                .param("subject", IDENTITY_B.subject())
                .update();
        assertEquals(0L, jdbcClient.sql("SELECT COUNT(*) FROM actor_profiles").query(Long.class).single());
    }

    @Test
    void rejectsAnActorAndBindingMismatchWithoutCreatingAProfile() {
        ActorId actorA = transaction.execute(_ -> identities.resolveOrCreate(IDENTITY_A));
        transaction.execute(_ -> identities.resolveOrCreate(IDENTITY_B));
        var recorder = new JpaActorProfileRecorder(
                jpa.entityManager(),
                Clock.fixed(FIRST_OBSERVATION, ZoneOffset.UTC)
        );

        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(_ -> recorder.record(
                actorA,
                IDENTITY_B,
                "Wrong Actor",
                "wrong@example.com",
                true
        )));
        assertEquals(0L, jdbcClient.sql("SELECT COUNT(*) FROM actor_profiles").query(Long.class).single());
    }

    @Test
    void refreshFragmentReloadsAnAlreadyManagedActorUnderWriteLock() {
        ActorId actorId = transaction.execute(_ -> identities.resolveOrCreate(IDENTITY_A));
        var actors = actorRepository();
        transaction.executeWithoutResult(_ -> {
            var managed = actors.findById(actorId.value()).orElseThrow();
            jdbcClient.sql("UPDATE actors SET ui_language = 'en' WHERE id = :id")
                    .param("id", actorId.value()).update();
            assertEquals("vi", managed.getUiLanguage(), "Persistence context still has the earlier snapshot");
            var refreshed = actors.refreshForUpdate(actorId.value());
            assertSame(managed, refreshed);
            assertEquals("en", refreshed.getUiLanguage());
            assertEquals(LockModeType.PESSIMISTIC_WRITE, jpa.entityManager().getLockMode(refreshed));
            refreshed.setUiLanguage("vi");
        });
        assertEquals("vi", transaction.execute(_ -> actors.findById(actorId.value()).orElseThrow().getUiLanguage()));
    }

    private JpaActorRepository actorRepository() {
        return jpa.repository(JpaActorRepository.class, RepositoryFragments.just(new ActorRefreshImpl(jpa.entityManager())));
    }

    private Profile profile(ActorId actorId) {
        return jdbcClient.sql("""
                        SELECT issuer, subject, display_name, email, email_verified, observed_at
                        FROM actor_profiles
                        WHERE actor_id = :actorId
                        """)
                .param("actorId", actorId.value())
                .query((resultSet, ignored) -> new Profile(
                        resultSet.getString("issuer"),
                        resultSet.getString("subject"),
                        resultSet.getString("display_name"),
                        resultSet.getString("email"),
                        resultSet.getBoolean("email_verified"),
                        resultSet.getTimestamp("observed_at").toInstant()
                ))
                .single();
    }

    private record Profile(
            String issuer,
            String subject,
            String displayName,
            String email,
            boolean emailVerified,
            Instant observedAt
    ) {
    }
}
