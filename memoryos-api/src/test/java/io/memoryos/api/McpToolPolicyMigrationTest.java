package io.memoryos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** V166 keeps what an administrator chose and gives every other tool the default of its risk (MEM-264). */
// SQL is exercised against the isolated, migrated Testcontainers database.
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
@Testcontainers
class McpToolPolicyMigrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:18.4-bookworm@sha256:882236b897e39051d2368c5ccc6cda944904723506b2dfc97f2a8f5bc9afa382")
            .asCompatibleSubstituteFor("postgres"));

    @Test
    void keepsChosenLevelsAndLetsToolsThatChangeDataAsk() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("165").load().migrate();
        var jdbc = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID tenant = UUID.randomUUID();
        UUID server = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference)
                VALUES(:tenant,'migration','Migration','ACTIVE','MEM-264')
                """).param("tenant", tenant).update();
        jdbc.sql("""
                INSERT INTO mcp_server(id,tenant_id,slug,name,url,auth_type,auth_performer,status,created_at,updated_at)
                VALUES(:id,:tenant,'linear','Linear','https://mcp.linear.app/mcp','API_TOKEN','ADMIN','CONNECTED',
                       CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """).param("id", server).param("tenant", tenant).update();
        // name, read_only, destructive, enabled, approval_required
        Object[][] tools = {
                {"list_issues", true, false, true, false},    // default read-only: on, no approval
                {"get_secret", true, false, true, true},      // an administrator asked first
                {"get_huge", true, false, false, true},       // an administrator turned it off
                {"save_comment", false, false, false, true},  // MEM-252 default for a write: off
                {"save_issue", false, true, true, true},      // turned on, asking
                {"create_label", false, false, true, false},  // turned on without approval
                {"delete_comment", false, true, true, false}, // destructive, turned on without approval
        };
        for (Object[] tool : tools) {
            jdbc.sql("""
                    INSERT INTO mcp_server_tool(id,tenant_id,server_id,name,input_schema,annotations,read_only,enabled,
                                                approval_required,snapshot_at)
                    VALUES(:id,:tenant,:server,:name,'{"type":"object"}'::jsonb,
                           jsonb_build_object('readOnlyHint',:readOnly,'destructiveHint',:destructive),
                           :readOnly,:enabled,:approval,CURRENT_TIMESTAMP)
                    """).param("id", UUID.randomUUID()).param("tenant", tenant).param("server", server)
                    .param("name", tool[0]).param("readOnly", tool[1]).param("destructive", tool[2])
                    .param("enabled", tool[3]).param("approval", tool[4]).update();
        }

        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("166").load().migrate();

        Map<String, String> policies = jdbc.sql("SELECT name, policy || ':' || policy_customized FROM mcp_server_tool")
                .query((row, index) -> Map.entry(row.getString(1), row.getString(2))).list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertEquals(Map.of(
                "list_issues", "ALWAYS:false",
                "get_secret", "ASK:true",
                "get_huge", "OFF:true",
                "save_comment", "ASK:false",
                "save_issue", "ASK:false",
                "create_label", "ALWAYS:true",
                "delete_comment", "ASK:true"), policies);
    }
}
