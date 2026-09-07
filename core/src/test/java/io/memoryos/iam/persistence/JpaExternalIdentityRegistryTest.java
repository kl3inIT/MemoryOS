package io.memoryos.iam.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import io.memoryos.TestDatabase.JpaHarness;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.ExternalIdentity;

import java.sql.SQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JpaExternalIdentityRegistryTest {

    private JdbcClient jdbcClient;
    private JpaHarness jpa;
    private JpaExternalIdentityRegistry registry;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() throws SQLException {
        var dataSource = TestDatabase.freshPostgres();
        jdbcClient = JdbcClient.create(dataSource);
        jpa = TestDatabase.jpa(dataSource);
        registry = new JpaExternalIdentityRegistry(jpa.entityManager());
        transaction = new TransactionTemplate(jpa.transactionManager());
    }

    @AfterEach
    void closeJpa() {
        jpa.close();
    }

    @Test
    void resolvesExactIssuerAndSubjectWithoutCaseOrIssuerConflation() {
        ExternalIdentity firstIdentity = new ExternalIdentity("https://issuer-a.example", "same-subject");
        ExternalIdentity secondIdentity = new ExternalIdentity("https://issuer-b.example", "same-subject");
        ActorId first = transaction.execute(_ -> registry.resolveOrCreate(firstIdentity));
        ActorId second = transaction.execute(_ -> registry.resolveOrCreate(secondIdentity));

        assertNotEquals(first, second);
        assertEquals(first, transaction.execute(_ -> registry.resolve(firstIdentity).orElseThrow()));
        assertEquals(second, transaction.execute(_ -> registry.resolve(secondIdentity).orElseThrow()));
        transaction.executeWithoutResult(_ -> assertTrue(registry.resolve(
                new ExternalIdentity("https://issuer-a.example", "Same-Subject")
        ).isEmpty()));
    }

    @Test
    void resolveOrCreateReusesTheStableActorAndKeepsStandardAccountType() {
        ExternalIdentity identity = new ExternalIdentity("https://issuer.example", "subject");

        ActorId first = transaction.execute(_ -> registry.resolveOrCreateLocked(identity));
        ActorId second = transaction.execute(_ -> registry.resolveOrCreateLocked(identity));

        assertEquals(first, second);
        assertEquals(1L, jdbcClient.sql("SELECT COUNT(*) FROM actors").query(Long.class).single());
        assertEquals(1L, jdbcClient.sql("SELECT COUNT(*) FROM external_identity_bindings").query(Long.class).single());
        assertEquals("STANDARD", jdbcClient.sql("SELECT account_type FROM actors").query(String.class).single());
    }

    @Test
    void databasePreventsBindingDeletionFromOrphaningAnActorRelationship() {
        ExternalIdentity identity = new ExternalIdentity("https://issuer.example", "subject");
        ActorId actorId = transaction.execute(_ -> registry.resolveOrCreate(identity));

        assertThrows(DataIntegrityViolationException.class, () -> jdbcClient.sql("DELETE FROM actors WHERE id = :actorId")
                .param("actorId", actorId.value())
                .update());
    }
}
