package io.memoryos.iam.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import io.memoryos.TestDatabase.JpaHarness;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.ExternalIdentity;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
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
    private JpaExternalIdentityRegistry identities;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() throws SQLException {
        var dataSource = TestDatabase.freshPostgres();
        jdbcClient = JdbcClient.create(dataSource);
        jpa = TestDatabase.jpa(dataSource);
        identities = new JpaExternalIdentityRegistry(jpa.entityManager());
        transaction = new TransactionTemplate(jpa.transactionManager());
    }

    @AfterEach
    void closeJpa() {
        jpa.close();
    }

    @Test
    void recordsLatestTruthfulProfileWithExactBindingProvenance() {
        ActorId actorId = transaction.execute(_ -> identities.resolveOrCreate(IDENTITY_A));
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
