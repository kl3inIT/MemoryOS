package io.memoryos.organization.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.identity.ActorId;
import io.memoryos.organization.OrganizationId;
import io.memoryos.organization.OrganizationMembershipRole;
import io.memoryos.organization.OrganizationSessionAuthority;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class JdbcOrganizationAccessResolverTest {

    private JdbcClient jdbcClient;
    private Connection keepAlive;
    private JdbcOrganizationAccessResolver resolver;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        try {
            keepAlive = dataSource.getConnection();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to keep the in-memory database open", exception);
        }
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1__create_identity_tables.sql"),
                new ClassPathResource("db/migration/V2__create_initial_organization_and_sessions.sql"),
                new ClassPathResource("db/migration/V3__create_organization_invitations.sql"),
                new ClassPathResource("db/migration/V4__collapse_workspace_into_organization.sql")
        ).populate(keepAlive);
        jdbcClient = JdbcClient.create(dataSource);
        resolver = new JdbcOrganizationAccessResolver(jdbcClient);
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        keepAlive.close();
    }

    @Test
    void resolvesActiveOwnerAuthority() {
        var actorId = actor();
        var organizationId = organization();
        persistAuthority(actorId, organizationId, OrganizationMembershipRole.OWNER);

        assertEquals(
                new OrganizationSessionAuthority(
                        organizationId,
                        "Tasco",
                        OrganizationMembershipRole.OWNER
                ),
                resolver.findSessionAuthority(actorId).orElseThrow()
        );
    }

    @Test
    void resolvesActiveMemberAuthority() {
        var actorId = actor();
        var organizationId = organization();
        persistAuthority(actorId, organizationId, OrganizationMembershipRole.MEMBER);

        assertEquals(OrganizationMembershipRole.MEMBER, resolver.findSessionAuthority(actorId).orElseThrow().role());
    }

    @Test
    void excludesInactiveMembershipAndOrganization() {
        var actorId = actor();
        var organizationId = organization();
        persistAuthority(actorId, organizationId, OrganizationMembershipRole.OWNER);

        updateStatus("organization_memberships", "INACTIVE", "organization_id", organizationId.value());
        assertTrue(resolver.findSessionAuthority(actorId).isEmpty());
        updateStatus("organization_memberships", "ACTIVE", "organization_id", organizationId.value());

        updateStatus("organizations", "INACTIVE", "id", organizationId.value());
        assertTrue(resolver.findSessionAuthority(actorId).isEmpty());
        updateStatus("organizations", "ACTIVE", "id", organizationId.value());
    }

    @Test
    void rejectsAmbiguousActiveOrganizationAuthority() {
        var actorId = actor();
        persistAuthority(actorId, organization(), OrganizationMembershipRole.OWNER);
        persistAuthority(actorId, organization(), OrganizationMembershipRole.MEMBER);

        var failure = assertThrows(IllegalStateException.class, () -> resolver.findSessionAuthority(actorId));

        assertEquals("actor belongs to more than one active Organization", failure.getMessage());
    }

    private void persistAuthority(
            ActorId actorId,
            OrganizationId organizationId,
            OrganizationMembershipRole role
    ) {
        jdbcClient.sql("INSERT INTO actors (id) VALUES (:actorId) ON CONFLICT DO NOTHING")
                .param("actorId", actorId.value())
                .update();
        jdbcClient.sql("""
                        INSERT INTO organizations (id, slug, display_name, status, bootstrap_reference)
                        VALUES (:organizationId, :slug, 'Tasco', 'ACTIVE', :bootstrapReference)
                        """)
                .param("organizationId", organizationId.value())
                .param("slug", "organization-" + organizationId.value())
                .param("bootstrapReference", "test-" + organizationId.value())
                .update();
        jdbcClient.sql("""
                        INSERT INTO organization_memberships (organization_id, actor_id, role, status)
                        VALUES (:organizationId, :actorId, :role, 'ACTIVE')
                        """)
                .param("organizationId", organizationId.value())
                .param("actorId", actorId.value())
                .param("role", role.name())
                .update();
    }

    private void updateStatus(String table, String value, String idColumn, UUID id) {
        jdbcClient.sql("UPDATE " + table + " SET status = :value WHERE " + idColumn + " = :id")
                .param("value", value)
                .param("id", id)
                .update();
    }

    private static ActorId actor() {
        return new ActorId(UUID.randomUUID());
    }

    private static OrganizationId organization() {
        return new OrganizationId(UUID.randomUUID());
    }

}
