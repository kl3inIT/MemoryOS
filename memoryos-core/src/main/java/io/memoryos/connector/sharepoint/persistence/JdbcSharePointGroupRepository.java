package io.memoryos.connector.sharepoint.persistence;

import io.memoryos.connector.SourceId;
import io.memoryos.connector.sync.persistence.WorkLeases;
import io.memoryos.shared.TenantId;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Who the groups named in a SYNC Source's permissions contain, as generations (MEM-136, ADR 0013). A read lists
 * what it still has to ask Microsoft as work rows, so it continues across the slices of a synchronization run; only
 * a read that finished all of them becomes the generation readers are checked against.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSharePointGroupRepository {
    private final JdbcClient jdbc;

    public JdbcSharePointGroupRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public Optional<Run> running(TenantId tenant, SourceId source) {
        return jdbc.sql("""
                SELECT generation FROM sharepoint_group_runs
                WHERE tenant_id = :tenant AND source_id = :source AND status = 'RUNNING'
                """).param("tenant", tenant.value()).param("source", source.value())
                .query((r, _) -> new Run(tenant, source, r.getLong("generation"))).optional();
    }

    /** Whether no read started within {@code interval}. */
    public boolean due(TenantId tenant, SourceId source, Duration interval) {
        return !jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM sharepoint_group_runs
                    WHERE tenant_id = :tenant AND source_id = :source
                      AND started_at > statement_timestamp() - :seconds * INTERVAL '1 second')
                """).param("tenant", tenant.value()).param("source", source.value())
                .param("seconds", interval.toSeconds()).query(Boolean.class).single();
    }

    /**
     * Starts a read of every site the Source holds an item of; a site whose address is longer than a work key is
     * left out, which admits nobody through its groups. Earlier reads that are not the active generation are removed
     * with what they collected. {@code directory} also asks for every group of the directory.
     */
    public Run start(TenantId tenant, SourceId source, boolean directory) {
        jdbc.sql("""
                DELETE FROM sharepoint_group_runs r USING sharepoint_sources s
                WHERE r.tenant_id = :tenant AND r.source_id = :source
                  AND s.tenant_id = r.tenant_id AND s.source_id = r.source_id
                  AND r.generation IS DISTINCT FROM s.active_group_generation
                """).param("tenant", tenant.value()).param("source", source.value()).update();
        long generation = jdbc.sql("""
                INSERT INTO sharepoint_group_runs (tenant_id, source_id, generation, status)
                SELECT :tenant, :source, COALESCE(MAX(generation), 0) + 1, 'RUNNING' FROM sharepoint_group_runs
                WHERE tenant_id = :tenant AND source_id = :source
                RETURNING generation
                """).param("tenant", tenant.value()).param("source", source.value()).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO sharepoint_group_work (tenant_id, source_id, generation, kind, work_key)
                SELECT DISTINCT tenant_id, source_id, :generation, 'WEB', web_url FROM sharepoint_items
                WHERE tenant_id = :tenant AND source_id = :source AND web_url IS NOT NULL
                  AND CHAR_LENGTH(web_url) BETWEEN 1 AND 512
                """).param("generation", generation).param("tenant", tenant.value()).param("source", source.value())
                .update();
        if (directory) {
            jdbc.sql("""
                    INSERT INTO sharepoint_group_work (tenant_id, source_id, generation, kind, work_key)
                    VALUES (:tenant, :source, :generation, 'DIRECTORY', '*')
                    """).param("tenant", tenant.value()).param("source", source.value())
                    .param("generation", generation).update();
        }
        return new Run(tenant, source, generation);
    }

    /** What the read asks Microsoft next: sites first, so the groups they name are known before any is asked. */
    public Optional<Task> next(Run run) {
        return jdbc.sql("""
                SELECT kind, work_key, web_url, provider_ref, page_link FROM sharepoint_group_work
                WHERE tenant_id = :tenant AND source_id = :source AND generation = :generation AND NOT done
                ORDER BY CASE kind WHEN 'WEB' THEN 0 WHEN 'DIRECTORY' THEN 1 ELSE 2 END, kind, work_key LIMIT 1
                """).param("tenant", run.tenantId().value()).param("source", run.sourceId().value())
                .param("generation", run.generation())
                .query((r, _) -> new Task(Kind.valueOf(r.getString("kind")), r.getString("work_key"),
                        r.getString("web_url"), r.getString("provider_ref"), r.getString("page_link"))).optional();
    }

    /** A group to ask for its members; one already listed in this read is not asked twice. */
    public void group(Run run, Kind kind, String groupId, @Nullable String webUrl, String providerRef) {
        jdbc.sql("""
                INSERT INTO sharepoint_group_work (tenant_id, source_id, generation, kind, work_key, web_url,
                    provider_ref)
                VALUES (:tenant, :source, :generation, :kind, :key, :web, :ref)
                ON CONFLICT DO NOTHING
                """).param("tenant", run.tenantId().value()).param("source", run.sourceId().value())
                .param("generation", run.generation()).param("kind", kind.name()).param("key", groupId)
                .param("web", webUrl).param("ref", providerRef).update();
    }

    public long groups(Run run) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM sharepoint_group_work
                WHERE tenant_id = :tenant AND source_id = :source AND generation = :generation
                  AND kind IN ('SITE_GROUP', 'ENTRA_GROUP')
                """).param("tenant", run.tenantId().value()).param("source", run.sourceId().value())
                .param("generation", run.generation()).query(Long.class).single();
    }

    public void members(Run run, String groupId, Collection<String> emails) {
        for (String email : emails) {
            jdbc.sql("""
                    INSERT INTO sharepoint_group_members (tenant_id, source_id, generation, group_id, member_email)
                    VALUES (:tenant, :source, :generation, :group, :email)
                    ON CONFLICT DO NOTHING
                    """).param("tenant", run.tenantId().value()).param("source", run.sourceId().value())
                    .param("generation", run.generation()).param("group", groupId).param("email", email).update();
        }
    }

    /** The task has more to list at {@code pageLink}, or is done when that is null. */
    public void advanced(Run run, Task task, @Nullable String pageLink) {
        jdbc.sql("""
                UPDATE sharepoint_group_work SET page_link = :link, done = :done
                WHERE tenant_id = :tenant AND source_id = :source AND generation = :generation
                  AND kind = :kind AND work_key = :key
                """).param("link", pageLink).param("done", pageLink == null)
                .param("tenant", run.tenantId().value()).param("source", run.sourceId().value())
                .param("generation", run.generation()).param("kind", task.kind().name()).param("key", task.key())
                .update();
    }

    /** Everything was asked: this generation is the one readers are checked against, and the earlier ones go. */
    public void complete(Run run) {
        jdbc.sql("""
                UPDATE sharepoint_group_runs SET status = 'COMPLETED', finished_at = statement_timestamp()
                WHERE tenant_id = :tenant AND source_id = :source AND generation = :generation AND status = 'RUNNING'
                """).param("tenant", run.tenantId().value()).param("source", run.sourceId().value())
                .param("generation", run.generation()).update();
        jdbc.sql("""
                UPDATE sharepoint_sources SET active_group_generation = :generation
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("generation", run.generation()).param("tenant", run.tenantId().value())
                .param("source", run.sourceId().value()).update();
        jdbc.sql("""
                DELETE FROM sharepoint_group_runs
                WHERE tenant_id = :tenant AND source_id = :source AND generation <> :generation
                """).param("tenant", run.tenantId().value()).param("source", run.sourceId().value())
                .param("generation", run.generation()).update();
        jdbc.sql("""
                DELETE FROM sharepoint_group_work
                WHERE tenant_id = :tenant AND source_id = :source AND generation = :generation
                """).param("tenant", run.tenantId().value()).param("source", run.sourceId().value())
                .param("generation", run.generation()).update();
    }

    /** The read ended without an answer for every site; the active generation stays in force. */
    public void fail(Run run, String errorCode) {
        jdbc.sql("""
                UPDATE sharepoint_group_runs SET status = 'FAILED', error_code = :code,
                    finished_at = statement_timestamp()
                WHERE tenant_id = :tenant AND source_id = :source AND generation = :generation AND status = 'RUNNING'
                """).param("code", WorkLeases.safeErrorCode(errorCode)).param("tenant", run.tenantId().value())
                .param("source", run.sourceId().value()).param("generation", run.generation()).update();
    }

    public enum Kind { WEB, SITE_GROUP, ENTRA_GROUP, DIRECTORY }

    public record Run(TenantId tenantId, SourceId sourceId, long generation) {}

    /**
     * {@code key} is the site's address for {@link Kind#WEB} and the group's name in membership for a group;
     * {@code providerRef} is how Microsoft is asked for a group.
     */
    public record Task(Kind kind, String key, @Nullable String webUrl, @Nullable String providerRef,
                       @Nullable String pageLink) {}
}
