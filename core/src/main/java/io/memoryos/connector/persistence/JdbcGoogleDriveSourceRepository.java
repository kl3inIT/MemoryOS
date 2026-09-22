package io.memoryos.connector.persistence;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveSourceService.DiscoveryError;
import io.memoryos.connector.GoogleDriveSourceService.LinkedDocument;
import io.memoryos.connector.GoogleDriveSourceService.LinkedDocumentStatus;
import io.memoryos.connector.GoogleDriveSourceService.LinkOrigin;
import io.memoryos.connector.GoogleDriveSourceService.Root;
import io.memoryos.connector.GoogleDriveSourceService.ScopeMode;
import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.GoogleDriveSourceService.SelectionCounts;
import io.memoryos.connector.GoogleDriveSourceService.SelectionItem;
import io.memoryos.connector.GoogleDriveSourceService.SelectionKind;
import io.memoryos.connector.GoogleDriveSourceService.SelectionPage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import io.memoryos.connector.SourceId;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.iam.identity.ActorId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGoogleDriveSourceRepository {
    private final JdbcClient jdbc;

    public JdbcGoogleDriveSourceRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public SelectionCounts counts(TenantId tenant, SourceId source) {
        return jdbc.sql("""
                SELECT
                  (SELECT count(*) FROM google_drive_roots WHERE tenant_id=:tenant AND source_id=:source
                    AND mime_type='application/vnd.google-apps.folder' AND :specific) AS folders,
                  (SELECT count(*) FROM google_drive_roots WHERE tenant_id=:tenant AND source_id=:source
                    AND mime_type<>'application/vnd.google-apps.folder' AND :specific) AS files,
                  (SELECT count(*) FROM google_drive_linked_documents d WHERE d.tenant_id=:tenant AND d.source_id=:source
                    AND NOT EXISTS (SELECT 1 FROM google_drive_roots r
                      WHERE r.tenant_id=d.tenant_id AND r.source_id=d.source_id AND r.file_id=d.file_id)) AS linked,
                  (SELECT count(*) FROM google_drive_link_approvals a WHERE a.tenant_id=:tenant AND a.source_id=:source
                    AND NOT EXISTS (SELECT 1 FROM google_drive_roots r
                      WHERE r.tenant_id=a.tenant_id AND r.source_id=a.source_id AND r.file_id=a.file_id)) AS approved
                """).param("tenant",tenant.value()).param("source",source.value())
                .param("specific",scopeMode(tenant,source)==ScopeMode.SPECIFIC).query((r,n) -> new SelectionCounts(
                        r.getLong("folders"),r.getLong("files"),r.getLong("linked"),r.getLong("approved"))).single();
    }

    public SelectionPage selection(TenantId tenant, SourceId source, long credentialRevision, @Nullable String search,
            @Nullable SelectionKind kind, @Nullable String cursor, int size) {
        if (size<1 || size>100 || search!=null && search.length()>255 || cursor!=null && cursor.length()>4096)
            throw SourceException.invalid("Invalid selection page.", "selection page bounds exceeded");
        var config=configuration(tenant,source);
        String query=search==null?"":search.strip();
        String prefix=tenant.value()+"|"+source.value()+"|"+config.revision()+"|"+config.discoveryRevision()+"|"
                +credentialRevision+"|"+encode(query)+"|"+(kind==null?"":kind.name())+"|";
        int afterKind=-1;
        String afterName="";
        String afterId="";
        if (cursor!=null) {
            try {
                String decoded=decode(cursor);
                if (!decoded.startsWith(prefix)) throw SourceException.staleConfiguration();
                String[] parts=decoded.substring(prefix.length()).split("\\|",-1);
                if (parts.length!=3) throw new IllegalArgumentException();
                afterKind=Integer.parseInt(parts[0]);
                afterName=decode(parts[1]);
                afterId=parts[2];
                if (afterKind<0 || afterKind>2 || !afterId.matches("[A-Za-z0-9_-]{1,256}")) throw new IllegalArgumentException();
            } catch (IllegalArgumentException exception) {
                throw SourceException.invalid("Invalid selection cursor.", "malformed selection cursor");
            }
        }
        var page=jdbc.sql("""
                WITH entries AS (
                  SELECT file_id,name,mime_type,CASE WHEN mime_type='application/vnd.google-apps.folder' THEN 0 ELSE 1 END AS sort_kind,
                    TRUE AS selected,FALSE AS covered_by_roots,'AVAILABLE' AS status
                  FROM google_drive_roots WHERE tenant_id=:tenant AND source_id=:source AND :specific
                  UNION ALL
                  SELECT d.file_id,d.name,d.mime_type,2,a.file_id IS NOT NULL,d.covered_by_roots,d.status
                  FROM google_drive_linked_documents d LEFT JOIN google_drive_link_approvals a USING(tenant_id,source_id,file_id)
                  WHERE d.tenant_id=:tenant AND d.source_id=:source
                    AND NOT EXISTS (SELECT 1 FROM google_drive_roots r
                      WHERE r.tenant_id=d.tenant_id AND r.source_id=d.source_id AND r.file_id=d.file_id)
                  UNION ALL
                  SELECT m.file_id,v.filename,COALESCE(o.declared_media_type,'application/octet-stream'),1,
                    FALSE,TRUE,'AVAILABLE'
                  FROM google_drive_membership m
                  JOIN connector_credential_pairs p ON p.tenant_id=m.tenant_id AND p.id=m.source_id
                  JOIN connector_items i ON i.tenant_id=p.tenant_id AND i.connector_id=p.connector_id
                    AND i.provider_file_id=m.file_id AND i.status<>'DELETING'
                  JOIN connector_item_versions v ON v.tenant_id=i.tenant_id AND v.id=i.current_version_id
                  LEFT JOIN stored_objects o ON o.tenant_id=v.tenant_id AND o.id=v.stored_object_id
                  WHERE m.tenant_id=:tenant AND m.source_id=:source AND m.eligible AND NOT m.excluded
                    AND m.root_id IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM google_drive_roots r
                      WHERE r.tenant_id=m.tenant_id AND r.source_id=m.source_id AND r.file_id=m.file_id)
                    AND NOT EXISTS (SELECT 1 FROM google_drive_linked_documents d
                      WHERE d.tenant_id=m.tenant_id AND d.source_id=m.source_id AND d.file_id=m.file_id)
                )
                SELECT * FROM entries WHERE (:kind=-1 OR sort_kind=:kind)
                    AND position(lower(:search) in lower(name))>0
                    AND (sort_kind,name,file_id)>(:afterKind,:afterName,:afterId)
                ORDER BY sort_kind,name,file_id LIMIT :limit
                """).param("tenant",tenant.value()).param("source",source.value())
                .param("specific",config.scopeMode()==ScopeMode.SPECIFIC).param("kind",kind==null?-1:kind.ordinal())
                .param("search",query).param("afterKind",afterKind).param("afterName",afterName).param("afterId",afterId)
                .param("limit",size+1).query((r,n) -> new SelectionItem(r.getString("file_id"),r.getString("name"),r.getString("mime_type"),
                        SelectionKind.values()[r.getInt("sort_kind")],r.getBoolean("selected"),r.getBoolean("covered_by_roots"),
                        LinkedDocumentStatus.valueOf(r.getString("status")),List.of())).list();
        boolean more=page.size()>size;
        var visible=page.subList(0,Math.min(size,page.size()));
        var ids=visible.stream().map(SelectionItem::id).toList();
        var origins=new HashMap<String,List<LinkOrigin>>();
        if (!ids.isEmpty()) jdbc.sql("""
                SELECT * FROM google_drive_link_origins WHERE tenant_id=:tenant AND source_id=:source AND file_id IN (:ids)
                ORDER BY file_id,root_id,parent_id,location
                """).param("tenant",tenant.value()).param("source",source.value()).param("ids",ids).query((r,n) -> {
                    origins.computeIfAbsent(r.getString("file_id"),_ -> new ArrayList<>()).add(new LinkOrigin(
                            r.getString("root_id"),r.getString("parent_id"),r.getString("parent_name"),r.getString("location")));
                    return true;
                }).list();
        var items=visible.stream().map(item -> new SelectionItem(item.id(),item.name(),item.mimeType(),item.kind(),
                item.selected(),item.coveredByRoots(),item.status(),origins.getOrDefault(item.id(),List.of()))).toList();
        String next=null;
        if (more) {
            var last=items.getLast();
            next=encode(prefix+last.kind().ordinal()+"|"+encode(last.name())+"|"+last.id());
        }
        return new SelectionPage(config.revision(),config.discoveryRevision(),credentialRevision,items,next,counts(tenant,source));
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8);
    }

    public TreeAuthority treeAuthority(TenantId tenant, SourceId source, String id) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM google_drive_roots
                  WHERE tenant_id=:tenant AND source_id=:source AND file_id=:id) AS root,
                  EXISTS (SELECT 1 FROM google_drive_link_approvals
                  WHERE tenant_id=:tenant AND source_id=:source AND file_id=:id) AS approved
                """).param("tenant",tenant.value()).param("source",source.value()).param("id",id)
                .query((r,n) -> new TreeAuthority(r.getBoolean("root"),r.getBoolean("approved"))).single();
    }

    public List<TreeEntry> treeEntries(TenantId tenant, SourceId source, @Nullable String parent,
            boolean currentDiscovery, int afterKind, String afterName, String afterId, int size) {
        var page=jdbc.sql("""
                WITH RECURSIVE reachable(file_id) AS (
                  SELECT o.file_id FROM google_drive_link_origins o
                  JOIN google_drive_roots r ON r.tenant_id=o.tenant_id AND r.source_id=o.source_id AND r.file_id=o.root_id
                  WHERE o.tenant_id=:tenant AND o.source_id=:source AND :current
                    AND (r.mime_type='application/vnd.google-apps.folder' OR o.parent_id=r.file_id)
                  UNION
                  SELECT o.file_id FROM reachable path
                  JOIN google_drive_linked_documents d ON d.file_id=path.file_id
                    AND d.tenant_id=:tenant AND d.source_id=:source
                  JOIN google_drive_link_origins o ON o.parent_id=d.file_id
                    AND o.tenant_id=d.tenant_id AND o.source_id=d.source_id
                  WHERE d.status='AVAILABLE' AND d.mime_type<>'application/vnd.google-apps.folder'
                    AND (d.covered_by_roots OR EXISTS (SELECT 1 FROM google_drive_link_approvals a
                      WHERE a.tenant_id=d.tenant_id AND a.source_id=d.source_id AND a.file_id=d.file_id))
                ), entries AS (
                  SELECT file_id,name,mime_type,CASE WHEN mime_type='application/vnd.google-apps.folder' THEN 0 ELSE 1 END AS sort_kind,
                    TRUE AS selected,TRUE AS covered_by_roots,'AVAILABLE' AS status,TRUE AS root,FALSE AS approved
                  FROM google_drive_roots WHERE tenant_id=:tenant AND source_id=:source AND :top
                  UNION ALL
                  SELECT d.file_id,COALESCE(r.name,d.name),COALESCE(r.mime_type,d.mime_type),2,
                    r.file_id IS NOT NULL OR a.file_id IS NOT NULL,r.file_id IS NOT NULL OR (d.covered_by_roots AND :current),
                    CASE WHEN r.file_id IS NOT NULL THEN 'AVAILABLE' ELSE d.status END,
                    r.file_id IS NOT NULL,a.file_id IS NOT NULL
                  FROM google_drive_linked_documents d
                  LEFT JOIN google_drive_link_approvals a USING(tenant_id,source_id,file_id)
                  LEFT JOIN google_drive_roots r USING(tenant_id,source_id,file_id)
                  WHERE d.tenant_id=:tenant AND d.source_id=:source
                    AND ((:top AND r.file_id IS NULL AND NOT EXISTS (SELECT 1 FROM reachable path WHERE path.file_id=d.file_id))
                      OR (NOT :top AND EXISTS (SELECT 1 FROM google_drive_link_origins o
                        WHERE o.tenant_id=d.tenant_id AND o.source_id=d.source_id AND o.file_id=d.file_id AND o.parent_id=:parent)))
                )
                SELECT entries.*,EXISTS (SELECT 1 FROM google_drive_link_origins o
                  WHERE o.tenant_id=:tenant AND o.source_id=:source AND o.parent_id=entries.file_id) AS outgoing
                FROM entries WHERE (sort_kind,name,file_id)>(:afterKind,:afterName,:afterId)
                ORDER BY sort_kind,name,file_id LIMIT :limit
                """).param("tenant",tenant.value()).param("source",source.value()).param("parent",parent==null?"":parent)
                .param("top",parent==null).param("current",currentDiscovery)
                .param("afterKind",afterKind).param("afterName",afterName).param("afterId",afterId)
                .param("limit",size+1).query((r,n) -> new TreeEntry(
                        new SelectionItem(r.getString("file_id"),r.getString("name"),r.getString("mime_type"),
                                SelectionKind.values()[r.getInt("sort_kind")],r.getBoolean("selected"),
                                r.getBoolean("covered_by_roots"),LinkedDocumentStatus.valueOf(r.getString("status")),List.of()),
                        r.getBoolean("root"),r.getBoolean("approved"),r.getBoolean("outgoing"))).list();
        var ids=page.stream().map(entry -> entry.item().id()).toList();
        if (ids.isEmpty()) return page;
        var origins=new HashMap<String,List<LinkOrigin>>();
        jdbc.sql("""
                SELECT file_id,root_id,parent_id,parent_name,location FROM google_drive_link_origins
                WHERE tenant_id=:tenant AND source_id=:source AND file_id IN (:ids) AND (:top OR parent_id=:parent)
                ORDER BY file_id,root_id,parent_id,location
                """).param("tenant",tenant.value()).param("source",source.value()).param("ids",ids)
                .param("top",parent==null).param("parent",parent==null?"":parent).query((r,n) -> {
                    origins.computeIfAbsent(r.getString("file_id"),_ -> new ArrayList<>()).add(
                            new LinkOrigin(r.getString("root_id"),r.getString("parent_id"),r.getString("parent_name"),r.getString("location")));
                    return true;
                }).list();
        return page.stream().map(entry -> {
            var item=entry.item();
            return new TreeEntry(new SelectionItem(item.id(),item.name(),item.mimeType(),item.kind(),item.selected(),
                    item.coveredByRoots(),item.status(),origins.getOrDefault(item.id(),List.of())),
                    entry.root(),entry.approved(),entry.outgoing());
        }).toList();
    }

    public Set<String> treeOutgoingParents(TenantId tenant, SourceId source, List<String> ids) {
        if (ids.isEmpty()) return Set.of();
        return Set.copyOf(jdbc.sql("""
                SELECT DISTINCT parent_id FROM google_drive_link_origins
                WHERE tenant_id=:tenant AND source_id=:source AND parent_id IN (:ids)
                """).param("tenant",tenant.value()).param("source",source.value()).param("ids",ids).query(String.class).list());
    }

    public record TreeAuthority(boolean root, boolean approved) {}
    public record TreeEntry(SelectionItem item, boolean root, boolean approved, boolean outgoing) {}

    public void requireGoogle(TenantId tenant, SourceId source) {
        if (jdbc.sql("""
                SELECT COUNT(*) FROM connector_credential_pairs p
                JOIN connectors c ON c.tenant_id = p.tenant_id AND c.id = p.connector_id
                WHERE p.tenant_id = :tenant AND p.id = :source AND c.connector_type = 'GOOGLE_DRIVE'
                AND p.status <> 'DELETING'
                """).param("tenant", tenant.value()).param("source", source.value())
                .query(Integer.class).single() != 1) throw SourceException.notFound();
    }

    public SourceId create(TenantId tenant, SourceId source, ActorId actor, String name, CredentialId credential, ScopeMode scopeMode,
            List<Root> roots, SourceAccess access, @Nullable ActorId managerActor) {
        UUID connector = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO connectors (id, tenant_id, name, connector_type, status)
                VALUES (:id, :tenant, :name, 'GOOGLE_DRIVE', 'ACTIVE')
                """).param("id", connector).param("tenant", tenant.value()).param("name", name).update();
        jdbc.sql("""
                INSERT INTO connector_credential_pairs (id, tenant_id, connector_id, credential_id, access_type, status, created_by_actor_id, manager_actor_id)
                VALUES (:id, :tenant, :connector, :credential, :access, 'NOT_STARTED', :actor, :managerActor)
                """).param("id", source.value()).param("tenant", tenant.value()).param("access", access.name())
                .param("connector", connector).param("credential", credential.value()).param("actor", actor.value())
                .param("managerActor", managerActor == null ? null : managerActor.value()).update();
        initialize(tenant, source, scopeMode);
        insertRoots(tenant, source, roots);
        return source;
    }

    private void initialize(TenantId tenant, SourceId source, ScopeMode scopeMode) {
        jdbc.sql("""
                INSERT INTO google_drive_sources (tenant_id, source_id, scope_mode) VALUES (:tenant, :source, :scopeMode)
                ON CONFLICT DO NOTHING
                """).param("tenant", tenant.value()).param("source", source.value())
                .param("scopeMode", scopeMode.name()).update();
    }

    public ScopeMode scopeMode(TenantId tenant, SourceId source) {
        return jdbc.sql("SELECT scope_mode FROM google_drive_sources WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", tenant.value()).param("source", source.value())
                .query(String.class).optional().map(ScopeMode::valueOf).orElseThrow(SourceException::notFound);
    }

    public ConfigurationRow configuration(TenantId tenant, SourceId source) {
        return jdbc.sql("""
                SELECT s.*, EXISTS (SELECT 1 FROM source_sync_attempts a
                  WHERE a.tenant_id = s.tenant_id AND a.source_id = s.source_id
                  AND a.status IN ('NOT_STARTED','IN_PROGRESS')) OR EXISTS (
                  SELECT 1 FROM index_attempts a
                  JOIN connector_item_versions v ON v.tenant_id = a.tenant_id AND v.id = a.connector_item_version_id
                  JOIN google_drive_membership m ON m.tenant_id = s.tenant_id AND m.source_id = s.source_id
                    AND m.file_id = v.provider_file_id
                  WHERE a.tenant_id = s.tenant_id AND a.connector_credential_pair_id = s.source_id
                    AND a.status IN ('NOT_STARTED','IN_PROGRESS') AND v.scope_revision = s.revision
                    AND m.eligible AND NOT m.excluded) AS pending
                FROM google_drive_sources s WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", tenant.value()).param("source", source.value())
                .query((r, _) -> new ConfigurationRow(r.getLong("revision"), r.getInt("sync_interval_minutes"),
                        r.getLong("schedule_revision"), r.getBoolean("sync_paused"), ScopeMode.valueOf(r.getString("scope_mode")),
                        r.getLong("discovery_revision"), JdbcSourceRepository.instant(r, "discovered_at"),
                        r.getLong("discovery_scope_revision"), r.getLong("discovery_credential_revision"),
                        JdbcSourceRepository.instant(r, "last_synced_at"), r.getBoolean("pending"),
                        r.getString("error_code"))).optional()
                .orElseThrow(SourceException::notFound);
    }

    public List<Root> roots(TenantId tenant, SourceId source) {
        return jdbc.sql("""
                SELECT file_id, name, mime_type FROM google_drive_roots
                WHERE tenant_id = :tenant AND source_id = :source ORDER BY file_id
                """).param("tenant", tenant.value()).param("source", source.value())
                .query((r, _) -> new Root(r.getString("file_id"), r.getString("name"), r.getString("mime_type"))).list();
    }
    public List<LinkedDocument> linkedDocuments(TenantId tenant, SourceId source) {
        var origins = new HashMap<String, List<LinkOrigin>>();
        jdbc.sql("""
                SELECT file_id, root_id, parent_id, parent_name, location FROM google_drive_link_origins
                WHERE tenant_id = :tenant AND source_id = :source
                ORDER BY file_id, root_id, parent_id, location
                """).param("tenant", tenant.value()).param("source", source.value())
                .query((r, _) -> {
                    origins.computeIfAbsent(r.getString("file_id"), _ -> new ArrayList<>()).add(
                            new LinkOrigin(r.getString("root_id"), r.getString("parent_id"),
                                    r.getString("parent_name"), r.getString("location")));
                    return true;
                }).list();
        return jdbc.sql("""
                SELECT d.*, a.file_id IS NOT NULL AS selected FROM google_drive_linked_documents d
                LEFT JOIN google_drive_link_approvals a USING (tenant_id, source_id, file_id)
                WHERE d.tenant_id = :tenant AND d.source_id = :source ORDER BY d.name, d.file_id
                """).param("tenant", tenant.value()).param("source", source.value())
                .query((r, _) -> new LinkedDocument(r.getString("file_id"), r.getString("name"),
                        r.getString("mime_type"), r.getBoolean("selected"), r.getBoolean("covered_by_roots"),
                        LinkedDocumentStatus.valueOf(r.getString("status")),
                        origins.getOrDefault(r.getString("file_id"), List.of()))).list();
    }

    public List<String> approvedIds(TenantId tenant, SourceId source) {
        return jdbc.sql("""
                SELECT file_id FROM google_drive_link_approvals
                WHERE tenant_id = :tenant AND source_id = :source ORDER BY file_id
                """).param("tenant", tenant.value()).param("source", source.value()).query(String.class).list();
    }

    public List<DiscoveryError> discoveryErrors(TenantId tenant, SourceId source) {
        return jdbc.sql("""
                SELECT file_id, file_name, code FROM google_drive_discovery_errors
                WHERE tenant_id = :tenant AND source_id = :source ORDER BY file_id, code
                """).param("tenant", tenant.value()).param("source", source.value())
                .query((r, _) -> new DiscoveryError(r.getString("file_id"), r.getString("file_name"), r.getString("code"))).list();
    }

    public void saveDiscovery(TenantId tenant, SourceId source, long scopeRevision, long credentialRevision,
            long discoveryRevision, List<LinkedDocument> documents, List<DiscoveryError> errors) {
        if (jdbc.sql("""
                UPDATE google_drive_sources SET discovery_revision = discovery_revision + 1,
                  discovered_at = clock_timestamp(), discovery_scope_revision = :scope,
                  discovery_credential_revision = :credential
                WHERE tenant_id = :tenant AND source_id = :source AND revision = :scope
                  AND discovery_revision = :discovery AND scope_mode = 'SPECIFIC'
                """).param("tenant", tenant.value()).param("source", source.value()).param("scope", scopeRevision)
                .param("credential", credentialRevision).param("discovery", discoveryRevision).update() != 1)
            throw SourceException.staleConfiguration();
        clearOriginsAndErrors(tenant, source);
        removeUnapproved(tenant, source);
        for (var document : documents) {
            upsertDocument(tenant, source, document);
            for (var origin : document.origins()) jdbc.sql("""
                    INSERT INTO google_drive_link_origins
                      (tenant_id, source_id, file_id, root_id, parent_id, parent_name, location)
                    VALUES (:tenant, :source, :file, :root, :parent, :name, :location)
                    ON CONFLICT DO NOTHING
                    """).param("tenant", tenant.value()).param("source", source.value()).param("file", document.id())
                    .param("root", origin.rootId()).param("parent", origin.parentId()).param("name", origin.parentName())
                    .param("location", origin.location()).update();
        }
        for (var error : errors) jdbc.sql("""
                INSERT INTO google_drive_discovery_errors (tenant_id, source_id, file_id, file_name, code)
                VALUES (:tenant, :source, :file, :name, :code) ON CONFLICT DO NOTHING
                """).param("tenant", tenant.value()).param("source", source.value()).param("file", error.fileId())
                .param("name", error.fileName()).param("code", WorkLeases.safeErrorCode(error.code())).update();
    }

    public void replaceApprovals(TenantId tenant, SourceId source, List<String> ids, List<LinkedDocument> refreshed,
            boolean rootsChanged) {
        refreshed.forEach(document -> upsertDocument(tenant, source, document));
        jdbc.sql("DELETE FROM google_drive_link_approvals WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", tenant.value()).param("source", source.value()).update();
        for (String id : ids) jdbc.sql("""
                INSERT INTO google_drive_link_approvals (tenant_id, source_id, file_id) VALUES (:tenant, :source, :file)
                """).param("tenant", tenant.value()).param("source", source.value()).param("file", id).update();
        jdbc.sql("""
                UPDATE google_drive_sources SET discovery_revision = discovery_revision + 1,
                  discovered_at = CASE WHEN :changed THEN NULL ELSE discovered_at END,
                  discovery_scope_revision = CASE WHEN :changed THEN NULL ELSE revision END,
                  discovery_credential_revision = CASE WHEN :changed THEN NULL ELSE discovery_credential_revision END
                WHERE tenant_id = :tenant AND source_id = :source AND scope_mode = 'SPECIFIC'
                """).param("tenant", tenant.value()).param("source", source.value()).param("changed", rootsChanged).update();
        if (rootsChanged) {
            clearOriginsAndErrors(tenant, source);
            removeUnapproved(tenant, source);
        }
    }

    private void upsertDocument(TenantId tenant, SourceId source, LinkedDocument document) {
        jdbc.sql("""
                INSERT INTO google_drive_linked_documents (tenant_id, source_id, file_id, name, mime_type, status, covered_by_roots)
                VALUES (:tenant, :source, :file, :name, :mime, :status, :covered)
                ON CONFLICT (tenant_id, source_id, file_id) DO UPDATE SET name = EXCLUDED.name,
                  mime_type = EXCLUDED.mime_type, status = EXCLUDED.status, covered_by_roots = EXCLUDED.covered_by_roots
                """).param("tenant", tenant.value()).param("source", source.value()).param("file", document.id())
                .param("name", document.name()).param("mime", document.mimeType()).param("status", document.status().name())
                .param("covered", document.coveredByRoots()).update();
    }

    private void clearOriginsAndErrors(TenantId tenant, SourceId source) {
        jdbc.sql("DELETE FROM google_drive_link_origins WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", tenant.value()).param("source", source.value()).update();
        jdbc.sql("DELETE FROM google_drive_discovery_errors WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", tenant.value()).param("source", source.value()).update();
    }

    private void removeUnapproved(TenantId tenant, SourceId source) {
        jdbc.sql("""
                DELETE FROM google_drive_linked_documents d WHERE d.tenant_id = :tenant AND d.source_id = :source
                AND NOT EXISTS (SELECT 1 FROM google_drive_link_approvals a
                  WHERE a.tenant_id = d.tenant_id AND a.source_id = d.source_id AND a.file_id = d.file_id)
                """).param("tenant", tenant.value()).param("source", source.value()).update();
    }


    public void replace(TenantId tenant, SourceId source, long expected, long credentialRevision, ScopeMode scopeMode,
            List<Root> roots, List<LinkedDocument> approvals) {
        if (scopeMode(tenant, source) != scopeMode) {
            throw SourceException.invalid("Google Drive scope mode is chosen when the Source is created and cannot be changed.",
                    "attempt to change creation-only Drive scope mode");
        }
        var rootIds = roots.stream().map(Root::id).toList();
        var approvedIds = approvals.stream().map(LinkedDocument::id).toList();
        boolean rootsChanged = !Set.copyOf(roots(tenant, source).stream().map(Root::id).toList()).equals(Set.copyOf(rootIds));
        if (jdbc.sql("""
                UPDATE google_drive_sources SET revision = revision + 1,
                  error_code = NULL, next_sync_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND source_id = :source AND revision = :revision AND scope_mode = :scopeMode
                """).param("tenant", tenant.value()).param("source", source.value())
                .param("scopeMode", scopeMode.name()).param("revision", expected).update() != 1) throw SourceException.staleConfiguration();
        retainSelectionMembership(tenant, source, expected, credentialRevision, rootIds, approvedIds, rootsChanged);
        carryForwardIndexedVersions(tenant, source, expected, credentialRevision);
        jdbc.sql("DELETE FROM google_drive_roots WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", tenant.value()).param("source", source.value()).update();

        insertRoots(tenant, source, roots);
        replaceApprovals(tenant, source, approvedIds, approvals, rootsChanged);
        revokeUncoveredMappings(tenant, source);
    }

    /** Mappings follow retained membership: only files still covered keep their Document retrieval eligibility. */
    private void revokeUncoveredMappings(TenantId tenant, SourceId source) {
        jdbc.sql("""
                UPDATE documents_by_connector_credential_pair map
                SET retrieval_eligible = FALSE, last_indexed_at = CURRENT_TIMESTAMP
                WHERE map.tenant_id = :tenant AND map.connector_credential_pair_id = :source
                  AND map.retrieval_eligible
                  AND NOT EXISTS (SELECT 1 FROM connector_items i
                    JOIN google_drive_membership m ON m.tenant_id = i.tenant_id AND m.source_id = :source
                      AND m.file_id = i.provider_file_id AND m.eligible AND NOT m.excluded
                    WHERE i.tenant_id = map.tenant_id AND i.id = map.connector_item_id AND i.status <> 'DELETING')
                """).param("tenant", tenant.value()).param("source", source.value()).update();
    }

    private void retainSelectionMembership(TenantId tenant, SourceId source, long scopeRevision, long credentialRevision,
            List<String> roots, List<String> approvals, boolean rootsChanged) {
        // Reconciliation temporarily clears eligibility; a still-readable publication remains authority evidence.
        var update = jdbc.sql("""
                UPDATE google_drive_membership m SET eligible = NOT m.excluded AND m.root_id IS NOT NULL
                  AND EXISTS (
                    SELECT 1 FROM connector_credential_pairs p
                    JOIN connector_items i ON i.tenant_id=p.tenant_id AND i.connector_id=p.connector_id
                    JOIN connector_item_versions v ON v.tenant_id=i.tenant_id AND v.id=i.current_version_id
                    WHERE p.tenant_id=m.tenant_id AND p.id=m.source_id AND i.provider_file_id=m.file_id
                      AND i.status<>'DELETING' AND v.scope_revision=:scope AND v.credential_revision=:credential
                      AND (m.eligible OR EXISTS (
                        SELECT 1 FROM documents_by_connector_credential_pair mapping
                        JOIN documents d ON d.tenant_id=mapping.tenant_id AND d.id=mapping.document_id
                        WHERE mapping.tenant_id=i.tenant_id AND mapping.connector_credential_pair_id=p.id
                          AND mapping.connector_item_id=i.id AND mapping.retrieval_eligible AND d.status='ELIGIBLE')))
                  AND (
                    EXISTS (SELECT 1 FROM google_drive_roots r
                      WHERE r.tenant_id=m.tenant_id AND r.source_id=m.source_id AND r.file_id=m.root_id AND %s)
                    OR EXISTS (SELECT 1 FROM google_drive_link_approvals a
                      WHERE a.tenant_id=m.tenant_id AND a.source_id=m.source_id AND a.file_id=m.file_id AND %s)
                    OR (NOT :rootsChanged AND m.root_id=m.file_id AND EXISTS (
                      SELECT 1 FROM google_drive_linked_documents d
                      JOIN google_drive_sources s ON s.tenant_id=d.tenant_id AND s.source_id=d.source_id
                      WHERE d.tenant_id=m.tenant_id AND d.source_id=m.source_id AND d.file_id=m.file_id
                        AND d.covered_by_roots AND d.status='AVAILABLE'
                        AND s.discovery_scope_revision=:scope AND s.discovery_credential_revision=:credential)))
                WHERE m.tenant_id=:tenant AND m.source_id=:source
                """.formatted(roots.isEmpty() ? "FALSE" : "r.file_id IN (:roots)",
                        approvals.isEmpty() ? "FALSE" : "a.file_id IN (:approvals)"))
                .param("tenant", tenant.value()).param("source", source.value())
                .param("scope", scopeRevision).param("credential", credentialRevision).param("rootsChanged", rootsChanged);
        if (!roots.isEmpty()) update.param("roots", roots);
        if (!approvals.isEmpty()) update.param("approvals", approvals);
        update.update();
    }

    private void carryForwardIndexedVersions(TenantId tenant, SourceId source, long scopeRevision, long credentialRevision) {
        // A previous Document may survive a failed newer input, but that unfinished input must be reacquired.
        jdbc.sql("""
                UPDATE connector_item_versions v SET scope_revision=:scope+1
                FROM connector_items i
                JOIN connector_credential_pairs p ON p.tenant_id=i.tenant_id AND p.connector_id=i.connector_id
                JOIN google_drive_membership m ON m.tenant_id=p.tenant_id AND m.source_id=p.id AND m.file_id=i.provider_file_id
                JOIN documents_by_connector_credential_pair mapping ON mapping.tenant_id=i.tenant_id
                  AND mapping.connector_credential_pair_id=p.id AND mapping.connector_item_id=i.id
                JOIN documents d ON d.tenant_id=mapping.tenant_id AND d.id=mapping.document_id
                WHERE p.tenant_id=:tenant AND p.id=:source AND i.status='INDEXED'
                  AND v.tenant_id=i.tenant_id AND v.id=i.current_version_id
                  AND v.scope_revision=:scope AND v.credential_revision=:credential
                  AND m.eligible AND NOT m.excluded AND mapping.retrieval_eligible
                  AND d.status='ELIGIBLE' AND d.source_content_sha256=v.content_sha256
                  AND EXISTS (SELECT 1 FROM index_attempts a
                    WHERE a.tenant_id=i.tenant_id AND a.connector_credential_pair_id=p.id
                      AND a.connector_item_id=i.id AND a.connector_item_version_id=v.id AND a.status='SUCCEEDED')
                """).param("tenant", tenant.value()).param("source", source.value())
                .param("scope", scopeRevision).param("credential", credentialRevision).update();
    }

    public void updateSchedule(TenantId tenant, SourceId source, long expectedRevision, int syncIntervalMinutes) {
        if (jdbc.sql("""
                UPDATE google_drive_sources SET sync_interval_minutes = :minutes,
                  schedule_revision = schedule_revision + 1,
                  next_sync_at = statement_timestamp() + :minutes * INTERVAL '1 minute'
                WHERE tenant_id = :tenant AND source_id = :source AND schedule_revision = :revision
                """).param("minutes", syncIntervalMinutes).param("tenant", tenant.value())
                .param("source", source.value()).param("revision", expectedRevision).update() != 1) {
            throw SourceException.staleConfiguration();
        }
    }

    public void setPaused(TenantId tenant, SourceId source, long expectedRevision, boolean paused) {
        if (jdbc.sql("""
                UPDATE google_drive_sources SET sync_paused = :paused, schedule_revision = schedule_revision + 1
                WHERE tenant_id = :tenant AND source_id = :source AND schedule_revision = :revision
                """).param("tenant", tenant.value()).param("source", source.value())
                .param("revision", expectedRevision).param("paused", paused).update() != 1)
            throw SourceException.staleConfiguration();
    }

    public boolean automaticSyncEnabled(TenantId tenant, SourceId source) {
        return jdbc.sql("SELECT NOT sync_paused FROM google_drive_sources WHERE tenant_id=:tenant AND source_id=:source")
                .param("tenant", tenant.value()).param("source", source.value()).query(Boolean.class).optional().orElse(false);
    }

    private void insertRoots(TenantId tenant, SourceId source, List<Root> roots) {
        for (Root root : roots) {
            jdbc.sql("""
                    INSERT INTO google_drive_roots (tenant_id, source_id, file_id, name, mime_type)
                    VALUES (:tenant, :source, :file, :name, :mime)
                    """).param("tenant", tenant.value()).param("source", source.value())
                    .param("file", root.id()).param("name", root.name()).param("mime", root.mimeType()).update();
        }
    }

    public record ConfigurationRow(long revision, int syncIntervalMinutes, long scheduleRevision, boolean syncPaused, ScopeMode scopeMode,
                                   long discoveryRevision, @Nullable Instant discoveredAt,
                                   long discoveryScopeRevision, long discoveryCredentialRevision,
                                   @Nullable Instant lastSyncedAt, boolean pending,
                                   @Nullable String errorCode) {}
}
