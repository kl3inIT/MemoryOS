package io.memoryos.connector.application;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.connector.*;
import io.memoryos.connector.GoogleDriveSourceService.*;
import io.memoryos.connector.persistence.*;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.application.DefaultIamAuthorization;
import io.memoryos.iam.persistence.IamAuthorizationRepository;
import io.memoryos.iam.persistence.IamLockRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker=true)
public class GoogleDriveSelectionOperationTest {
    private HikariDataSource dataSource;

    private Fixture fixture() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        return new Fixture(dataSource);
    }

    @AfterEach
    void closeDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void admissionReservesOnlyAReceiptAndRecoveryReusesAncestorCheckpoints() throws Exception {
        var fixture=fixture();
        var links=fixture.mixedRoots(80,40);
        var receipt=fixture.create(UUID.randomUUID(),links);
        assertEquals(0,fixture.calls);
        assertThrows(SourceException.class,() -> fixture.service.configuration(fixture.owner,receipt.sourceId()));
        fixture.batch(receipt);
        assertEquals(SourceOperationStatus.NOT_STARTED,fixture.operation(receipt).status());
        fixture.restartProcessor();
        assertEquals(SourceOperationStatus.SUCCEEDED,fixture.finish(receipt).status());
        assertEquals(120,fixture.calls);
        assertEquals(80,fixture.service.selectionDraft(fixture.owner,receipt.sourceId()).links().size());
        assertEquals(1,fixture.service.configuration(fixture.owner,receipt.sourceId()).revision());
        fixture.finish(receipt);
        assertEquals(120,fixture.calls);
        assertEquals(1,fixture.service.configuration(fixture.owner,receipt.sourceId()).revision());
    }

    @Test
    void requestIdentityConflictsAndSupersessionNeverReplaceTheActiveSelection() throws Exception {
        var fixture=fixture();
        var links=fixture.mixedRoots(25,1);
        UUID request=UUID.randomUUID();
        var created=fixture.create(request,links);
        assertEquals(created,fixture.create(request,links));
        assertThrows(SourceException.class,() -> fixture.create(request,List.of(links.getFirst())));
        fixture.finish(created);
        var first=fixture.service.replaceRoots(fixture.owner,UUID.randomUUID(),created.sourceId(),1,0,1,ScopeMode.SPECIFIC,
                List.of(links.getFirst()),List.of());
        var second=fixture.service.replaceRoots(fixture.owner,UUID.randomUUID(),created.sourceId(),1,0,1,ScopeMode.SPECIFIC,
                List.of(links.getLast()),List.of());
        assertEquals(SourceOperationStatus.SUPERSEDED,fixture.operation(first).status());
        assertEquals(25,fixture.service.selectionDraft(fixture.owner,created.sourceId()).links().size());
        assertEquals(SourceOperationStatus.SUCCEEDED,fixture.finish(second).status());
        assertEquals(List.of(links.getLast()),fixture.service.selectionDraft(fixture.owner,created.sourceId()).links());
        assertEquals(2,fixture.service.configuration(fixture.owner,created.sourceId()).revision());
    }

    @Test
    void pagedSearchDoesNotDropHiddenRootsOrApprovalsAndCursorsPinAuthority() throws Exception {
        var fixture=fixture();
        var links=fixture.mixedRoots(26,1);
        var created=fixture.create(UUID.randomUUID(),links);
        fixture.finish(created);
        fixture.discoverApproval(created.sourceId(),"remote");
        var initialDraft=fixture.service.selectionDraft(fixture.owner,created.sourceId());
        var approval=fixture.service.replaceRoots(fixture.owner,UUID.randomUUID(),created.sourceId(),1,
                initialDraft.discoveryRevision(),initialDraft.credentialRevision(),ScopeMode.SPECIFIC,links,List.of("remote"));
        fixture.finish(approval);
        var page=fixture.service.selection(fixture.owner,created.sourceId(),null,null,null,5);
        assertNotNull(page.nextCursor());
        var draft=fixture.service.selectionDraft(fixture.owner,created.sourceId());
        assertEquals(26,draft.links().size());
        assertEquals(List.of("remote"),draft.linkedDocumentIds());
        assertEquals(List.of("remote"),fixture.service.selection(fixture.owner,created.sourceId(),"remote",SelectionKind.LINKED,null,5)
                .items().stream().map(SelectionItem::id).toList());
        var saved=fixture.service.replaceRoots(fixture.owner,UUID.randomUUID(),created.sourceId(),draft.revision(),
                draft.discoveryRevision(),draft.credentialRevision(),ScopeMode.SPECIFIC,
                draft.links(),draft.linkedDocumentIds());
        fixture.finish(saved);
        assertEquals(List.of("remote"),fixture.service.selectionDraft(fixture.owner,created.sourceId()).linkedDocumentIds());
        assertThrows(SourceException.class,() -> fixture.service.selection(fixture.owner,created.sourceId(),null,null,page.nextCursor(),5));
    }

    @Test
    void directlySelectedTargetsAppearOnceWithAllOriginsAcrossCountsFiltersAndPages() throws Exception {
        var fixture=fixture();
        var created=fixture.create(UUID.randomUUID(),fixture.mixedRoots(2,1));
        fixture.finish(created);
        var source=created.sourceId();
        fixture.files.put("remote-b",Fixture.file("remote-b",false,List.of()));
        var other=fixture.create(UUID.randomUUID(),List.of(Fixture.link("remote-b")));
        fixture.finish(other);
        var origins=new ArrayList<LinkOrigin>();
        for (int i=1;i<=8;i++) origins.add(new LinkOrigin("root0","parent","Parent document","Sheet!B"+i));
        var candidates=List.of(
                new LinkedDocument("root1","Linked alias","text/plain",false,true,LinkedDocumentStatus.AVAILABLE,origins),
                new LinkedDocument("remote-a","remote-a","text/plain",false,true,LinkedDocumentStatus.AVAILABLE,
                        List.of(new LinkOrigin("root0","parent","Parent document","Sheet!C1"))),
                new LinkedDocument("remote-b","remote-b","text/plain",false,false,LinkedDocumentStatus.UNAVAILABLE,
                        List.of(new LinkOrigin("root0","parent","Parent document","Sheet!C2"))));
        fixture.transactions.executeWithoutResult(_ -> {
            fixture.roots.saveDiscovery(fixture.tenant,source,1,1,0,candidates,List.of());
            fixture.roots.replaceApprovals(fixture.tenant,source,List.of("root1","remote-a"),List.of(),false);
        });

        var expectedCounts=new SelectionCounts(1,1,2,1);
        assertEquals(expectedCounts,fixture.service.configuration(fixture.owner,source).counts());
        var items=new ArrayList<SelectionItem>();
        String cursor=null;
        for (int pageNumber=0;pageNumber<4;pageNumber++) {
            var page=fixture.service.selection(fixture.owner,source,null,null,cursor,1);
            assertEquals(expectedCounts,page.counts());
            assertEquals(1,page.items().size());
            items.addAll(page.items());
            cursor=page.nextCursor();
            if (pageNumber<3) assertNotNull(cursor);
        }
        assertNull(cursor);
        assertEquals(List.of("root0","root1","remote-a","remote-b"),items.stream().map(SelectionItem::id).toList());
        var direct=items.get(1);
        assertEquals(SelectionKind.FILE,direct.kind());
        assertTrue(direct.selected());
        assertEquals("root1",direct.name());
        assertEquals(origins,direct.origins());
        assertEquals(origins,fixture.service.selection(fixture.owner,source,"root1",SelectionKind.FILE,null,1)
                .items().getFirst().origins());
        assertTrue(fixture.service.selection(fixture.owner,source,"Linked alias",null,null,1).items().isEmpty());
        assertTrue(fixture.service.selection(fixture.owner,source,"root1",SelectionKind.LINKED,null,1).items().isEmpty());

        var first=fixture.service.selection(fixture.owner,source,null,SelectionKind.LINKED,null,1);
        assertEquals(List.of("remote-a"),first.items().stream().map(SelectionItem::id).toList());
        assertTrue(first.items().getFirst().coveredByRoots());
        assertTrue(first.items().getFirst().selected());
        assertEquals(candidates.get(1).origins(),first.items().getFirst().origins());
        assertEquals(expectedCounts,first.counts());
        assertNotNull(first.nextCursor());
        var last=fixture.service.selection(fixture.owner,source,null,SelectionKind.LINKED,first.nextCursor(),1);
        assertEquals(List.of("remote-b"),last.items().stream().map(SelectionItem::id).toList());
        assertEquals(LinkedDocumentStatus.UNAVAILABLE,last.items().getFirst().status());
        assertFalse(last.items().getFirst().selected());
        assertEquals(candidates.get(2).origins(),last.items().getFirst().origins());
        assertEquals(expectedCounts,last.counts());
        assertNull(last.nextCursor());
        assertEquals(List.of("remote-a","root1"),fixture.service.selectionDraft(fixture.owner,source).linkedDocumentIds());
        assertEquals(List.of("root1","remote-a","remote-b"),fixture.roots.linkedDocuments(fixture.tenant,source)
                .stream().map(LinkedDocument::id).toList());
        var otherPage=fixture.service.selection(fixture.owner,other.sourceId(),null,null,null,1);
        assertEquals(new SelectionCounts(0,1,0,0),otherPage.counts());
        assertEquals(List.of("remote-b"),otherPage.items().stream().map(SelectionItem::id).toList());
        assertTrue(otherPage.items().getFirst().origins().isEmpty());
        var wrongTenant=new TenantId(UUID.randomUUID());
        assertThrows(SourceException.class,() -> fixture.roots.counts(wrongTenant,source));
        assertThrows(SourceException.class,() -> fixture.roots.selection(wrongTenant,source,1,null,null,null,1));
    }

    @Test
    void staleDiscoveryAndCredentialDraftsCannotAdmitReplacementWork() throws Exception {
        var fixture=fixture();
        var links=fixture.mixedRoots(21,1);
        var created=fixture.create(UUID.randomUUID(),links);
        fixture.finish(created);
        var oldDraft=fixture.service.selectionDraft(fixture.owner,created.sourceId());
        fixture.discoverApproval(created.sourceId(),"remote");
        var staleDiscovery=assertThrows(SourceException.class,() -> fixture.service.replaceRoots(
                fixture.owner,UUID.randomUUID(),created.sourceId(),oldDraft.revision(),
                oldDraft.discoveryRevision(),oldDraft.credentialRevision(),ScopeMode.SPECIFIC,links,List.of()));
        assertEquals("SOURCE_GOOGLE_REVISION_CONFLICT",staleDiscovery.code());
        var currentDraft=fixture.service.selectionDraft(fixture.owner,created.sourceId());
        fixture.jdbc.sql("UPDATE google_drive_credentials SET credential_revision=credential_revision+1 WHERE credential_id=:id")
                .param("id",fixture.credential.value()).update();
        var staleCredential=assertThrows(SourceException.class,() -> fixture.service.replaceRoots(
                fixture.owner,UUID.randomUUID(),created.sourceId(),currentDraft.revision(),
                currentDraft.discoveryRevision(),currentDraft.credentialRevision(),ScopeMode.SPECIFIC,links,List.of()));
        assertEquals("SOURCE_GOOGLE_REVISION_CONFLICT",staleCredential.code());
        assertEquals(1,fixture.jdbc.sql("SELECT count(*) FROM google_drive_selection_operations").query(Integer.class).single());
        assertEquals(oldDraft.links(),fixture.service.selectionDraft(fixture.owner,created.sourceId()).links());
    }


    @Test
    void overlappingAndRevokedProposalsKeepTheSourceAbsentAndReceiptsRecoverable() throws Exception {
        var fixture=fixture();
        fixture.mixedRoots(2,1);
        fixture.files.put("child",Fixture.file("child",false,List.of("root0")));
        var overlap=fixture.create(UUID.randomUUID(),List.of(Fixture.link("root0"),Fixture.link("child")));
        assertEquals("SOURCE_GOOGLE_ROOTS_OVERLAP",fixture.finish(overlap).errorCode());
        assertThrows(SourceException.class,() -> fixture.service.configuration(fixture.owner,overlap.sourceId()));
        UUID request=UUID.randomUUID();
        var revoked=fixture.create(request,List.of(Fixture.link("root1")));
        fixture.transactions.executeWithoutResult(_ -> fixture.credentials.delete(fixture.tenant,fixture.credential,1));
        assertEquals(SourceOperationStatus.SUPERSEDED, fixture.operation(revoked).status());
        assertEquals(revoked.sourceId(),fixture.service.selectionRequest(fixture.owner,request).sourceId());
        assertThrows(SourceException.class,() -> fixture.service.configuration(fixture.owner,revoked.sourceId()));
    }

    @Test
    void staleClaimsAndCapabilityRevocationCannotPublishAPartiallyVerifiedCreate() throws Exception {
        var fixture=fixture();
        var receipt=fixture.create(UUID.randomUUID(),fixture.mixedRoots(50,1));
        UUID delivery=UUID.randomUUID();
        fixture.deliver(receipt,delivery);
        var old=fixture.processor.claim(fixture.tenant,receipt.operation().id(),delivery).orElseThrow();
        fixture.jdbc.sql("UPDATE google_drive_selection_operations SET lease_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=:id")
                .param("id",receipt.operation().id().value()).update();
        var recovered=fixture.processor.claim(fixture.tenant,receipt.operation().id(),delivery).orElseThrow();
        assertEquals(GoogleDriveSelectionProcessor.Result.SUPERSEDED,fixture.processor.execute(old));
        assertEquals(GoogleDriveSelectionProcessor.Result.CONTINUED,fixture.processor.execute(recovered));
        fixture.jdbc.sql("DELETE FROM iam_group_capability_grants WHERE tenant_id=:tenant")
                .param("tenant",fixture.tenant.value()).update();
        fixture.batch(receipt);
        assertEquals(SourceOperationStatus.SUPERSEDED, fixture.operation(receipt).status());
        assertEquals("IAM_ACCESS_DENIED", fixture.operation(receipt).errorCode());
        assertEquals(0,fixture.jdbc.sql("SELECT count(*) FROM connector_credential_pairs").query(Integer.class).single());
    }

    @Test
    void capabilityRevokedDuringProviderVerificationCannotActivateTheSource() throws Exception {
        var fixture=fixture();
        var receipt=fixture.create(UUID.randomUUID(),fixture.mixedRoots(1,1));
        fixture.afterProviderRead=() -> fixture.jdbc.sql(
                "DELETE FROM iam_group_capability_grants WHERE tenant_id=:tenant")
                .param("tenant",fixture.tenant.value()).update();
        var result=fixture.finish(receipt);
        assertEquals(SourceOperationStatus.SUPERSEDED,result.status());
        assertEquals("IAM_ACCESS_DENIED",result.errorCode());
        assertEquals(0,fixture.jdbc.sql("SELECT count(*) FROM connector_credential_pairs").query(Integer.class).single());
    }

    public static final class Fixture {
        public final JdbcClient jdbc;
        public final TransactionTemplate transactions;
        public final TenantId tenant=new TenantId(UUID.randomUUID());
        public final ActorId owner=new ActorId(UUID.randomUUID());
        public final JdbcGoogleDriveCredentialRepository credentials;
        public final JdbcGoogleDriveSelectionRepository selections;
        public final JdbcGoogleDriveSourceRepository roots;
        public final DefaultGoogleDriveSourceService service;
        public final GoogleDriveConnectionService connections;
        public final CredentialId credential;
        public final Map<String,GoogleDriveProvider.FileMetadata> files=new HashMap<>();
        public final Map<String,GoogleDriveProvider.FilePage> pages=new HashMap<>();
        public final List<String> listedParents=new ArrayList<>();
        public Runnable afterProviderRead=() -> {};
        public GoogleDriveSelectionProcessor processor;
        public int calls;
        private final DataSourceTransactionManager manager;

        public Fixture(DataSource dataSource) throws Exception {
            jdbc=JdbcClient.create(dataSource);
            manager=new DataSourceTransactionManager(dataSource);
            transactions=new TransactionTemplate(manager);
            jdbc.sql("INSERT INTO actors(id) VALUES(:id)").param("id",owner.value()).update();
            jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,'selection','Selection','ACTIVE','TEST')")
                    .param("id",tenant.value()).update();
            jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')")
                    .param("tenant",tenant.value()).param("actor",owner.value()).update();
            jdbc.sql("INSERT INTO iam_groups(tenant_id,id,name,system_key) VALUES (:tenant,:tenant,'Admin','ADMIN')")
                    .param("tenant", tenant.value()).update();
            jdbc.sql("INSERT INTO iam_group_capability_grants(tenant_id,group_id,capability) VALUES (:tenant,:tenant,'IAM_ADMIN')")
                    .param("tenant", tenant.value()).update();
            jdbc.sql("INSERT INTO iam_group_memberships(tenant_id,group_id,actor_id) VALUES (:tenant,:tenant,:actor)")
                    .param("tenant", tenant.value()).param("actor", owner.value()).update();
            var sources=new JdbcSourceRepository(jdbc);
            roots=new JdbcGoogleDriveSourceRepository(jdbc);
            var sync=new JdbcSourceSyncRepository(jdbc);
            var documents=new JdbcSourceDocumentRepository(jdbc);
            credentials=new JdbcGoogleDriveCredentialRepository(jdbc,sources,
                    new GoogleDriveCredentialConfiguration(Base64.getEncoder().encodeToString(new byte[32]),"fixture"),documents,sync);
            GoogleDriveProvider provider=_ -> new GoogleDriveProvider.Session() {
                @Override public GoogleDriveProvider.FileMetadata metadata(String id) {
                    if (TransactionSynchronizationManager.isActualTransactionActive()) throw new AssertionError("Provider call held a transaction");
                    calls++;
                    var file=files.get(id);
                    if (file==null) throw new GoogleDriveProviderException(GoogleDriveProviderException.Failure.NOT_FOUND);
                    afterProviderRead.run();
                    return file;
                }
                @Override public GoogleDriveProvider.FilePage listFiles(String parent,String cursor) {
                    if (TransactionSynchronizationManager.isActualTransactionActive()) throw new AssertionError("Provider call held a transaction");
                    listedParents.add(parent);
                    var page=pages.get(parent+"|"+(cursor==null?"":cursor));
                    if (page==null) throw new AssertionError("Unexpected folder enumeration: "+parent);
                    afterProviderRead.run();
                    return page;
                }
                @Override public GoogleDriveProvider.AcquiredContent acquire(GoogleDriveProvider.FileMetadata file) { throw new AssertionError("Selection cannot acquire content"); }
                @Override public byte[] rotatedRefreshToken() { return null; }
                @Override public void close() {}
            };
            connections=TestDatabase.transactionalProxy(new DefaultGoogleDriveConnectionService(credentials,provider,manager),GoogleDriveConnectionService.class,manager);
            selections=new JdbcGoogleDriveSelectionRepository(jdbc);
            var indexing=new JdbcIndexAttemptRepository(jdbc,sources,documents,connections);
            service=new DefaultGoogleDriveSourceService(new DefaultIamAuthorization(new IamAuthorizationRepository(jdbc), new IamLockRepository(jdbc)),connections,roots,sources,sync,indexing,
                    documents,content -> List.of(),manager,selections,credentials,new GoogleDriveSelectionPolicy(1000,3145728),new JdbcSourceGroupRepository(jdbc));
            try (var grant=new GoogleDriveAuthorizationService.Grant("subject","fixture@example.com",GoogleDriveAuthorizationService.REQUIRED_SCOPES,
                    "refresh".getBytes(StandardCharsets.UTF_8));
                 var client=new GoogleDriveOAuthClient("fixture.apps.googleusercontent.com","secret".getBytes(StandardCharsets.UTF_8))) {
                credential=java.util.Objects.requireNonNull(transactions.execute(_ -> credentials.create(tenant,"Fixture",grant,client)));
            }
            restartProcessor();
        }

        public void restartProcessor() { processor=new DefaultGoogleDriveSelectionProcessor(selections,service,connections,manager); }
        public List<String> mixedRoots(int count,int depth) {
            for (int i=0;i<depth;i++) files.put("ancestor"+i,file("ancestor"+i,true,i+1<depth?List.of("ancestor"+(i+1)):List.of()));
            var links=new ArrayList<String>();
            for (int i=0;i<count;i++) {
                String id="root"+i;
                files.put(id,file(id,i%2==0,List.of("ancestor0")));
                links.add(link(id));
            }
            return links;
        }
        public SelectionReceipt create(UUID request,List<String> links) {
            return service.create(owner,request,"Fixture",credential,ScopeMode.SPECIFIC,links);
        }
        public void discoverApproval(SourceId source,String id) {
            files.put(id,file(id,false,List.of("ancestor0")));
            transactions.executeWithoutResult(_ -> roots.saveDiscovery(tenant,source,1,1,0,
                    List.of(new LinkedDocument(id,id,"text/plain",false,false,LinkedDocumentStatus.AVAILABLE,
                            List.of(new LinkOrigin("root0","root0","Root","A1")))),List.of()));
        }
        public SourceOperationView operation(SelectionReceipt receipt) { return selections.find(tenant,receipt.operation().id()).orElseThrow(); }
        public void deliver(SelectionReceipt receipt,UUID delivery) {
            jdbc.sql("UPDATE google_drive_selection_operations SET delivery_id=:delivery WHERE id=:id")
                    .param("delivery",delivery).param("id",receipt.operation().id().value()).update();
        }
        public void batch(SelectionReceipt receipt) {
            UUID delivery=UUID.randomUUID();
            deliver(receipt,delivery);
            processor.execute(processor.claim(tenant,receipt.operation().id(),delivery).orElseThrow());
        }
        public SourceOperationView finish(SelectionReceipt receipt) {
            for (int batch=0;batch<500;batch++) {
                var operation=operation(receipt);
                if (operation.status()!=SourceOperationStatus.NOT_STARTED && operation.status()!=SourceOperationStatus.IN_PROGRESS) return operation;
                batch(receipt);
            }
            throw new AssertionError("Selection did not finish");
        }
        public static String link(String id) { return "https://drive.google.com/file/d/"+id+"/view"; }
        public static GoogleDriveProvider.FileMetadata file(String id,boolean folder,List<String> parents) {
            return new GoogleDriveProvider.FileMetadata(id,id,folder?"application/vnd.google-apps.folder":"text/plain","1",null,null,false,parents,null,null);
        }
    }
}
