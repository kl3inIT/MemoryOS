package io.memoryos.connector.persistence;

import io.memoryos.connector.*;
import io.memoryos.connector.GoogleDriveSelectionProcessor.Work;
import io.memoryos.connector.GoogleDriveSourceService.*;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGoogleDriveSelectionRepository {
    private final JdbcClient jdbc;
    public JdbcGoogleDriveSelectionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<Boolean> ancestorCoverage(Work work,String id) {
        return jdbc.sql("""
                SELECT reaches_root FROM google_drive_selection_metadata
                WHERE tenant_id=:tenant AND operation_id=:operation AND lookup_id=:id AND reaches_root IS NOT NULL
                """).param("tenant",work.tenantId().value()).param("operation",work.operationId().value())
                .param("id",id).query(Boolean.class).optional();
    }

    public void uncoveredAncestors(Work work,Entry entry) {
        jdbc.sql("""
                UPDATE google_drive_selection_metadata m SET reaches_root=FALSE
                FROM google_drive_selection_ancestors a
                WHERE a.tenant_id=:tenant AND a.operation_id=:operation AND a.file_id=:file AND a.kind=:kind
                    AND m.tenant_id=a.tenant_id AND m.operation_id=a.operation_id AND m.lookup_id=a.ancestor_id
                """).param("tenant",work.tenantId().value()).param("operation",work.operationId().value())
                .param("file",entry.id()).param("kind",entry.kind()).update();
    }


    public void enqueueAncestors(Work work, Entry entry, List<String> parents) {
        if (parents.isEmpty()) return;
        int added=0;
        for (String parent : parents) added+=jdbc.sql("""
                INSERT INTO google_drive_selection_ancestors(tenant_id,operation_id,file_id,kind,ancestor_id)
                VALUES(:tenant,:operation,:file,:kind,:parent) ON CONFLICT DO NOTHING
                """).param("tenant",work.tenantId().value()).param("operation",work.operationId().value())
                .param("file",entry.id()).param("kind",entry.kind()).param("parent",parent).update();
        if (added>0 && jdbc.sql("""
                UPDATE google_drive_selection_operations SET ancestor_count=ancestor_count+:added
                WHERE tenant_id=:tenant AND id=:operation AND ancestor_count+:added<=200000
                """).param("tenant",work.tenantId().value()).param("operation",work.operationId().value()).param("added",added).update()!=1)
            throw SourceException.invalid("The selection exceeds the ancestor verification budget.",
                    "selection ancestor checkpoint budget exceeded");
    }

    public Optional<String> nextAncestor(Work work, Entry entry) {
        return jdbc.sql("""
                SELECT ancestor_id FROM google_drive_selection_ancestors
                WHERE tenant_id=:tenant AND operation_id=:operation AND file_id=:file AND kind=:kind AND NOT visited
                ORDER BY ancestor_id LIMIT 1
                """).param("tenant",work.tenantId().value()).param("operation",work.operationId().value())
                .param("file",entry.id()).param("kind",entry.kind()).query(String.class).optional();
    }

    public void visitAncestor(Work work, Entry entry, String ancestor) {
        jdbc.sql("""
                UPDATE google_drive_selection_ancestors SET visited=TRUE
                WHERE tenant_id=:tenant AND operation_id=:operation AND file_id=:file AND kind=:kind AND ancestor_id=:ancestor
                """).param("tenant",work.tenantId().value()).param("operation",work.operationId().value())
                .param("file",entry.id()).param("kind",entry.kind()).param("ancestor",ancestor).update();
    }

    public void cancelForSource(TenantId tenant, SourceId source) {
        jdbc.sql("""
                UPDATE google_drive_selection_operations SET status='CANCELLED',error_code='SOURCE_DELETING',
                    completed_at=CURRENT_TIMESTAMP,claim_token=NULL,lease_expires_at=NULL
                WHERE tenant_id=:tenant AND source_id=:source AND status IN ('NOT_STARTED','IN_PROGRESS')
                """).param("tenant",tenant.value()).param("source",source.value()).update();
    }

    public Optional<SourceOperationView> find(TenantId tenant, SourceOperationId operation) {
        return jdbc.sql("SELECT * FROM google_drive_selection_operations WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", tenant.value()).param("id", operation.value()).query(this::operation).optional();
    }

    public Optional<SelectionReceipt> receipt(TenantId tenant, ActorId actor, UUID request, @Nullable String hash) {
        return jdbc.sql("SELECT * FROM google_drive_selection_operations WHERE tenant_id=:tenant AND actor_id=:actor AND request_id=:request")
                .param("tenant", tenant.value()).param("actor", actor.value()).param("request", request)
                .query((r, n) -> {
                    if (hash != null && !hash.equals(r.getString("request_hash")))
                        throw SourceException.conflict("Selection request ID was already used with different content");
                    return new SelectionReceipt(new SourceId(r.getObject("source_id", UUID.class)), operation(r,n));
                }).optional();
    }

    public @Nullable SourceOperationView pending(TenantId tenant, SourceId source) {
        return jdbc.sql("SELECT * FROM google_drive_selection_operations WHERE tenant_id=:tenant AND source_id=:source AND status IN ('NOT_STARTED','IN_PROGRESS')")
                .param("tenant", tenant.value()).param("source", source.value()).query(this::operation).optional().orElse(null);
    }

    public SelectionReceipt submit(TenantId tenant, ActorId actor, UUID request, String hash, SourceId source,
            CredentialId credential, long credentialRevision, long scopeRevision, long discoveryRevision,
            ScopeMode mode, @Nullable String name, List<String> roots, List<LinkedDocument> approvals, SelectionPolicy policy) {
        jdbc.sql("""
                UPDATE google_drive_selection_operations SET status='SUPERSEDED', completed_at=CURRENT_TIMESTAMP,
                    claim_token=NULL, lease_expires_at=NULL,error_code='SELECTION_SUPERSEDED'
                WHERE tenant_id=:tenant AND source_id=:source AND status IN ('NOT_STARTED','IN_PROGRESS')
                """).param("tenant",tenant.value()).param("source",source.value()).update();
        UUID id = UUID.randomUUID();
        var trace = SourceOperationTraceContext.current();
        jdbc.sql("""
                INSERT INTO google_drive_selection_operations(id,tenant_id,source_id,actor_id,request_id,request_hash,
                    credential_id,credential_revision,scope_revision,discovery_revision,scope_mode,source_name,
                    max_requests,max_metadata,max_roots,max_request_bytes,origin_trace_id,origin_span_id)
                VALUES(:id,:tenant,:source,:actor,:request,:hash,:credential,:credentialRevision,:scope,:discovery,
                    :mode,:name,:requests,:metadata,:roots,:bytes,:trace,:span)
                """).param("id",id).param("tenant",tenant.value()).param("source",source.value()).param("actor",actor.value())
                .param("request",request).param("hash",hash).param("credential",credential.value())
                .param("credentialRevision",credentialRevision).param("scope",scopeRevision).param("discovery",discoveryRevision)
                .param("mode",mode.name()).param("name",name).param("requests",Math.min(100000,policy.maxExplicitRootsPerSource()*64+4096))
                .param("metadata",Math.min(50000,policy.maxExplicitRootsPerSource()*32+4096))
                .param("roots",policy.maxExplicitRootsPerSource()).param("bytes",policy.maxRequestBytes())
                .param("trace",trace == null ? null : trace.traceId()).param("span",trace == null ? null : trace.spanId()).update();
        for (String root : mode == ScopeMode.GENERAL ? List.of("root") : roots)
            entry(tenant,id,root,"ROOT",false,null,null);
        for (var approval : approvals) entry(tenant,id,approval.id(),"APPROVAL",approval.selected(),approval.name(),approval.mimeType());
        return new SelectionReceipt(source,find(tenant,new SourceOperationId(id)).orElseThrow());
    }

    private void entry(TenantId tenant,UUID operation,String file,String kind,boolean selected,@Nullable String name,@Nullable String mime) {
        jdbc.sql("""
                INSERT INTO google_drive_selection_entries(tenant_id,operation_id,file_id,kind,was_selected,name,mime_type)
                VALUES(:tenant,:operation,:file,:kind,:selected,:name,:mime)
                """).param("tenant",tenant.value()).param("operation",operation).param("file",file).param("kind",kind)
                .param("selected",selected).param("name",name).param("mime",mime).update();
    }

    public Optional<Work> claim(TenantId tenant, SourceOperationId id, UUID delivery) {
        // Serialize metadata verification for all selections sharing one credential, without holding a provider transaction.
        var credential = jdbc.sql("SELECT credential_id FROM google_drive_selection_operations WHERE tenant_id=:tenant AND id=:id")
                .param("tenant",tenant.value()).param("id",id.value()).query(UUID.class).optional();
        if (credential.isPresent()) {
            jdbc.sql("SELECT id FROM credentials WHERE tenant_id=:tenant AND id=:id FOR UPDATE")
                    .param("tenant",tenant.value()).param("id",credential.get()).query(UUID.class).optional();
            boolean busy = jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM google_drive_selection_operations WHERE tenant_id=:tenant AND credential_id=:credential
                        AND id<>:id AND status='IN_PROGRESS' AND lease_expires_at>CURRENT_TIMESTAMP)
                    """).param("tenant",tenant.value()).param("credential",credential.get()).param("id",id.value()).query(Boolean.class).single();
            if (busy) {
                jdbc.sql("""
                        UPDATE google_drive_selection_operations SET status='NOT_STARTED',claim_token=NULL,lease_expires_at=NULL,
                            delivery_id=NULL,dispatch_token=NULL,dispatch_lease_expires_at=NULL,redis_message_id=NULL,dispatched_at=NULL,
                            next_dispatch_at=CURRENT_TIMESTAMP+INTERVAL '5 seconds'
                        WHERE tenant_id=:tenant AND id=:id AND delivery_id=:delivery
                            AND (status='NOT_STARTED' OR status='IN_PROGRESS' AND lease_expires_at<=CURRENT_TIMESTAMP)
                        """).param("tenant",tenant.value()).param("id",id.value()).param("delivery",delivery).update();
                return Optional.empty();
            }
        }
        return WorkLeases.claim(jdbc,"google_drive_selection_operations",tenant.value(),id.value(),delivery,(operation,token) ->
                jdbc.sql("SELECT * FROM google_drive_selection_operations WHERE tenant_id=:tenant AND id=:id")
                        .param("tenant",tenant.value()).param("id",operation).query((r,n) -> new Work(tenant,
                                new SourceId(r.getObject("source_id",UUID.class)),id,token,WorkLeases.initialQueueWait(r))).single());
    }

    public boolean renew(Work work) {
        return WorkLeases.renew(jdbc,"google_drive_selection_operations",work.tenantId().value(),work.operationId().value(),work.claimToken());
    }

    public boolean current(Work work) {
        return jdbc.sql("""
                SELECT id FROM google_drive_selection_operations WHERE tenant_id=:tenant AND id=:id AND claim_token=:token
                    AND status='IN_PROGRESS' AND lease_expires_at>CURRENT_TIMESTAMP FOR UPDATE
                """).param("tenant",work.tenantId().value()).param("id",work.operationId().value()).param("token",work.claimToken())
                .query(UUID.class).optional().isPresent();
    }

    public Intent intent(Work work) {
        return jdbc.sql("SELECT * FROM google_drive_selection_operations WHERE tenant_id=:tenant AND id=:id")
                .param("tenant",work.tenantId().value()).param("id",work.operationId().value()).query((r,n) -> new Intent(
                        new ActorId(r.getObject("actor_id",UUID.class)),r.getObject("credential_id",UUID.class),
                        r.getLong("credential_revision"),r.getLong("scope_revision"),r.getLong("discovery_revision"),
                        ScopeMode.valueOf(r.getString("scope_mode")),r.getString("source_name"))).single();
    }

    public List<Entry> entries(Work work) {
        return jdbc.sql("SELECT * FROM google_drive_selection_entries WHERE tenant_id=:tenant AND operation_id=:id ORDER BY kind DESC,file_id")
                .param("tenant",work.tenantId().value()).param("id",work.operationId().value()).query((r,n) -> new Entry(
                        r.getString("file_id"),r.getString("kind"),r.getBoolean("was_selected"),r.getBoolean("verified"),
                        r.getBoolean("covered"),r.getString("status"),r.getString("name"),r.getString("mime_type"))).list();
    }

    public Optional<GoogleDriveProvider.FileMetadata> metadata(Work work,String id) {
        return jdbc.sql("SELECT * FROM google_drive_selection_metadata WHERE tenant_id=:tenant AND operation_id=:operation AND lookup_id=:id")
                .param("tenant",work.tenantId().value()).param("operation",work.operationId().value()).param("id",id)
                .query((r,n) -> new GoogleDriveProvider.FileMetadata(r.getString("file_id"),r.getString("name"),r.getString("mime_type"),
                        r.getString("version"),null,null,r.getBoolean("trashed"),Arrays.asList((String[])r.getArray("parents").getArray()),
                        r.getString("drive_id"),r.getString("shortcut_target_id"))).optional();
    }

    public void reserveRequest(Work work) {
        if (jdbc.sql("""
                UPDATE google_drive_selection_operations SET request_count=request_count+1
                WHERE tenant_id=:tenant AND id=:id AND claim_token=:token AND status='IN_PROGRESS'
                    AND lease_expires_at>CURRENT_TIMESTAMP AND request_count<max_requests AND elapsed_millis<3600000
                    AND metadata_count<max_metadata
                """).param("tenant",work.tenantId().value()).param("id",work.operationId().value()).param("token",work.claimToken()).update()!=1)
            throw SourceException.invalid("The selection exceeds the verification budget.","selection operation budget exceeded");
    }

    public void cache(Work work,String lookup,GoogleDriveProvider.FileMetadata file) {
        int added=jdbc.sql("""
                INSERT INTO google_drive_selection_metadata(tenant_id,operation_id,lookup_id,file_id,name,mime_type,version,
                    trashed,drive_id,shortcut_target_id,parents)
                VALUES(:tenant,:operation,:lookup,:file,:name,:mime,:version,:trashed,:drive,:shortcut,CAST(:parents AS TEXT[]))
                ON CONFLICT DO NOTHING
                """).param("tenant",work.tenantId().value()).param("operation",work.operationId().value()).param("lookup",lookup)
                .param("file",file.id()).param("name",file.name()).param("mime",file.mimeType()).param("version",file.version())
                .param("trashed",file.trashed()).param("drive",file.driveId()).param("shortcut",file.shortcutTargetId())
                .param("parents","{"+String.join(",",file.parents())+"}").update();
        if (added>0) jdbc.sql("""
                UPDATE google_drive_selection_operations SET metadata_count=metadata_count+1
                WHERE tenant_id=:tenant AND id=:id
                """).param("tenant",work.tenantId().value()).param("id",work.operationId().value()).update();
    }

    public void verified(Work work,Entry entry,String name,String mime,boolean covered,LinkedDocumentStatus status) {
        jdbc.sql("""
                UPDATE google_drive_selection_entries SET verified=TRUE,name=:name,mime_type=:mime,covered=:covered,status=:status
                WHERE tenant_id=:tenant AND operation_id=:operation AND file_id=:file AND kind=:kind
                """).param("tenant",work.tenantId().value()).param("operation",work.operationId().value()).param("file",entry.id())
                .param("kind",entry.kind()).param("name",name).param("mime",mime).param("covered",covered).param("status",status.name()).update();
    }

    public void finish(Work work,String status,@Nullable String code) {
        jdbc.sql("""
                UPDATE google_drive_selection_operations SET status=:status,error_code=:code,completed_at=CURRENT_TIMESTAMP,
                    claim_token=NULL,lease_expires_at=NULL WHERE tenant_id=:tenant AND id=:id AND claim_token=:token
                    AND status='IN_PROGRESS' AND lease_expires_at>CURRENT_TIMESTAMP
                """).param("status",status).param("code",code).param("tenant",work.tenantId().value()).param("id",work.operationId().value())
                .param("token",work.claimToken()).update();
    }

    public void continueLater(Work work,long elapsed,@Nullable String error) {
        jdbc.sql("""
                UPDATE google_drive_selection_operations SET status=CASE WHEN failure_attempts+:failure>=5 THEN 'FAILED' ELSE 'NOT_STARTED' END,
                    completed_at=CASE WHEN failure_attempts+:failure>=5 THEN CURRENT_TIMESTAMP ELSE NULL END,
                    elapsed_millis=elapsed_millis+:elapsed,failure_attempts=failure_attempts+:failure,error_code=:error,
                    claim_token=NULL,lease_expires_at=NULL,delivery_id=NULL,redis_message_id=NULL,dispatched_at=NULL,
                    dispatch_token=NULL,dispatch_lease_expires_at=NULL,next_dispatch_at=CURRENT_TIMESTAMP+:seconds*INTERVAL '1 second'
                WHERE tenant_id=:tenant AND id=:id AND claim_token=:token AND status='IN_PROGRESS' AND lease_expires_at>CURRENT_TIMESTAMP
                """).param("failure",error==null?0:1).param("elapsed",elapsed).param("error",error).param("seconds",error==null?0:30)
                .param("tenant",work.tenantId().value()).param("id",work.operationId().value()).param("token",work.claimToken()).update();
    }

    private SourceOperationView operation(ResultSet r,int ignored) throws SQLException {
        return new SourceOperationView(new SourceOperationId(r.getObject("id",UUID.class)),SourceOperationType.VALIDATE_GOOGLE_DRIVE_SELECTION,
                JdbcSourceRepository.operationStatus(r.getString("status")),r.getTimestamp("created_at").toInstant(),
                JdbcSourceRepository.instant(r,"completed_at"),r.getString("error_code"));
    }
    public record Intent(ActorId actorId,@Nullable UUID credentialId,long credentialRevision,long scopeRevision,
            long discoveryRevision,ScopeMode scopeMode,@Nullable String name) {}
    public record Entry(String id,String kind,boolean wasSelected,boolean verified,boolean covered,String status,
            @Nullable String name,@Nullable String mimeType) {}
}
