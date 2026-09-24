package io.memoryos.chat.interpreter;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.interpreter.persistence.JdbcInterpreterRepository;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectWriteService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Per-Tenant Code Interpreter switch and the files its runs produce (MEM-110). */
@Service
public class InterpreterService {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(InterpreterService.class);
    private final JdbcInterpreterRepository repository;
    private final InterpreterProperties properties;
    private final IamAuthorization authorization;
    private final TenantAccessResolver tenants;
    private final ObjectWriteService writes;
    private final ObjectStorage storage;
    private final TransactionTemplate tx;
    private final AuditTrail audit;

    private final io.memoryos.chat.ChatStorageQuotaService quotas;
    private final io.memoryos.chat.application.ChatRetentionProperties retention;

    public InterpreterService(JdbcInterpreterRepository repository, InterpreterProperties properties, IamAuthorization authorization,
                              TenantAccessResolver tenants, ObjectWriteService writes, ObjectStorage storage,
                              io.memoryos.chat.ChatStorageQuotaService quotas,
                              io.memoryos.chat.application.ChatRetentionProperties retention,
                              PlatformTransactionManager transactionManager, AuditTrail audit) {
        this.audit = audit;
        this.repository = repository; this.properties = properties; this.authorization = authorization;
        this.tenants = tenants; this.writes = writes; this.storage = storage; this.quotas = quotas;
        this.retention = retention; this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * A generated file belongs to the owner of the conversation that produced it, so it is their storage limit
     * that decides whether it can be kept. The refusal is named, and the tool reports it in the answer instead
     * of failing the whole turn.
     */
    private void requireRoom(TenantId tenant, UUID messageId, long bytes) {
        var owner = repository.owner(tenant, messageId).map(ActorId::new)
                .orElseThrow(() -> new IllegalStateException("generated file has no answer in this tenant"));
        quotas.requireRoom(tenant, owner, bytes);
    }

    /** {@code configured} reports whether this deployment has an interpreter; no row means disabled. */
    public record Settings(boolean configured, boolean enabled, long revision) {}
    public record Served(ObjectContent content, String filename, String mediaType) {}

    @Transactional(readOnly = true)
    public Settings settings(ActorId actor) {
        var tenant = authorization.require(actor, IamCapability.MODELS_MANAGE, false).tenantId();
        var setting = repository.setting(tenant);
        return new Settings(properties.configured(), setting.map(JdbcInterpreterRepository.Setting::enabled).orElse(false),
                setting.map(JdbcInterpreterRepository.Setting::revision).orElse(0L));
    }

    @Transactional
    public Settings update(ActorId actor, boolean enabled, long revision) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE).tenantId();
        long current = repository.setting(tenant).map(JdbcInterpreterRepository.Setting::revision).orElse(0L);
        if (current != revision) throw ChatException.conflict();
        if (enabled && !properties.configured()) throw ChatException.providerUnavailable();
        var saved = repository.save(tenant, enabled);
        audit.record(AuditRecord.of(AuditAction.INTERPRETER_CHANGE, tenant.value()).actor(actor.value()).resource("SETTING", "interpreter", "Code Interpreter").detail("enabled", enabled).build());
        return new Settings(properties.configured(), saved.enabled(), saved.revision());
    }

    /** Authorizes a live health check; the network call happens after this transaction returns. */
    @Transactional(readOnly = true)
    public void requireManager(ActorId actor) {
        authorization.require(actor, IamCapability.MODELS_MANAGE, false);
    }

    /** Runtime check for tool registration; the caller has already resolved the turn's Tenant. */
    public boolean enabled(TenantId tenant) {
        return properties.configured() && repository.setting(tenant).map(JdbcInterpreterRepository.Setting::enabled).orElse(false);
    }

    /**
     * Generated files for an already-authorized page of messages, keyed by message id. The caller has resolved these
     * message ids from an ownership-checked history read; results are scoped to the actor's active Tenant.
     */
    public java.util.Map<UUID, java.util.List<GeneratedFile>> forMessages(
            ActorId actor, java.util.Collection<UUID> messageIds) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(io.memoryos.chat.ChatException::unavailable);
        return repository.byMessages(tenant, messageIds);
    }

    /**
     * Hides a generated file the caller owns and leaves its bytes to the cleanup sweep. Deleting a file
     * already deleted succeeds, so a repeated request from the library is not an error.
     */
    public void delete(ActorId actor, UUID id) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        tx.executeWithoutResult(ignored -> {
            if (!repository.markArtifactDeleted(tenant, actor, id, retention.trashAfter())) throw ChatException.unavailable();
        });
        LOGGER.atInfo().addKeyValue("event", "chat.artifact.deleted").addKeyValue("artifact_kind", "GENERATED_FILE")
                .log("Generated file hidden; the cleanup sweep releases its bytes");
    }

    public UUID store(TenantId tenant, UUID messageId, String filename, String mediaType, byte[] bytes) {
        return store(tenant, messageId, filename, mediaType, bytes, null);
    }

    /** Persists a generated file with optional chart data (a JSON object) captured from its figure. */
    public UUID store(TenantId tenant, UUID messageId, String filename, String mediaType, byte[] bytes,
                      @org.jspecify.annotations.Nullable String chart) {
        requireRoom(tenant, messageId, bytes.length);
        UUID id = UUID.randomUUID();
        var staged = writes.stage(tenant, new ObjectWriteService.Specification(filename, mediaType, false), bytes);
        boolean adopted = false;
        try {
            tx.executeWithoutResult(ignored -> {
                writes.adopt(tenant, staged);
                repository.insertArtifact(tenant, messageId, id, staged.object().id().value(), staged.object().key(),
                        filename, mediaType, bytes.length, chart);
            });
            adopted = true;
            return id;
        } finally {
            if (!adopted) writes.discard(tenant, staged); // Never leave an unreferenced adopted object.
        }
    }

    /** Chart data of an owner-private generated file, as JSON text. */
    public String chart(ActorId actor, UUID id) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        return repository.ownedChart(tenant, actor, id).orElseThrow(ChatException::unavailable);
    }

    static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    /** The owner-private presentation to preview, with its cached PDF when one exists. */
    public InterpreterArtifact presentation(ActorId actor, UUID id) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        var artifact = repository.ownedArtifact(tenant, actor, id).orElseThrow(ChatException::unavailable);
        if (!PPTX.equals(artifact.mediaType())) throw ChatException.invalid("Only pptx files have a PDF preview");
        return artifact;
    }

    public TenantId activeTenant(ActorId actor) {
        return tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
    }

    public ObjectContent openObject(io.memoryos.objectstorage.ObjectKey key) {
        return storage.open(key);
    }

    /**
     * Stores a converted PDF preview. When another request stored one first, or the file was deleted while the
     * conversion ran, this copy is discarded rather than left adopted with nothing referencing it.
     */
    public void storePreview(TenantId tenant, UUID id, byte[] pdf) {
        var staged = writes.stage(tenant, new ObjectWriteService.Specification("preview.pdf", "application/pdf", false), pdf);
        boolean adopted = false;
        try {
            tx.executeWithoutResult(ignored -> {
                writes.adopt(tenant, staged);
                // Another request stored a preview first, or the file is gone: roll back this adoption.
                if (!repository.attachPreview(tenant, id, staged.object().id().value(), staged.object().key(), pdf.length))
                    throw new PreviewAlreadyStored();
            });
            adopted = true;
        } catch (PreviewAlreadyStored ignored) {
            // The caller reads the stored preview, or fails on the next owner check when the file is gone.
        } finally {
            if (!adopted) writes.discard(tenant, staged);
        }
    }

    private static final class PreviewAlreadyStored extends RuntimeException {
        PreviewAlreadyStored() { super(null, null, false, false); }
    }

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** Onyx {@code fetch_chat_file(parsed=true)}: an owner-private generated xlsx as CSV text per sheet. */
    public java.util.List<io.memoryos.document.SpreadsheetPreview.Sheet> spreadsheet(ActorId actor, UUID id) {
        var served = open(actor, id);
        try (var content = served.content()) {
            if (!XLSX.equals(served.mediaType())) throw ChatException.invalid("Only xlsx files have a spreadsheet preview");
            return io.memoryos.document.SpreadsheetPreview.parse(content.inputStream());
        } catch (java.io.IOException failed) {
            throw ChatException.invalid("The workbook cannot be previewed");
        }
    }

    /** Opens an owner-private generated file; the caller must close the returned content. */
    public Served open(ActorId actor, UUID id) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        var found = repository.ownedArtifact(tenant, actor, id).orElseThrow(ChatException::unavailable);
        var content = storage.open(found.key());
        try {
            if (tenants.findActiveTenant(actor).filter(tenant::equals).isEmpty()
                    || repository.ownedArtifact(tenant, actor, id).isEmpty()) throw ChatException.unavailable();
            return new Served(content, found.filename(), found.mediaType());
        } catch (RuntimeException failed) {
            content.close();
            throw failed;
        }
    }
}
