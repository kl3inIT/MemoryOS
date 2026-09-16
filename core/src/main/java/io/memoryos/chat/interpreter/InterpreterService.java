package io.memoryos.chat.interpreter;

import io.memoryos.chat.ChatException;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantId;
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
    private final JdbcInterpreterRepository repository;
    private final InterpreterProperties properties;
    private final IamAuthorization authorization;
    private final TenantAccessResolver tenants;
    private final ObjectWriteService writes;
    private final ObjectStorage storage;
    private final TransactionTemplate tx;

    public InterpreterService(JdbcInterpreterRepository repository, InterpreterProperties properties, IamAuthorization authorization,
                              TenantAccessResolver tenants, ObjectWriteService writes, ObjectStorage storage,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository; this.properties = properties; this.authorization = authorization;
        this.tenants = tenants; this.writes = writes; this.storage = storage;
        this.tx = new TransactionTemplate(transactionManager);
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

    /** Persists a generated file against the assistant message; returns the artifact id. */
    public UUID store(TenantId tenant, UUID messageId, String filename, String mediaType, byte[] bytes) {
        UUID id = UUID.randomUUID();
        var staged = writes.stage(tenant, new ObjectWriteService.Specification(filename, mediaType, false), bytes);
        boolean adopted = false;
        try {
            tx.executeWithoutResult(ignored -> {
                writes.adopt(tenant, staged);
                repository.insertArtifact(tenant, messageId, id, staged.object().id().value(), staged.object().key(),
                        filename, mediaType, bytes.length);
            });
            adopted = true;
            return id;
        } finally {
            if (!adopted) writes.discard(tenant, staged); // Never leave an unreferenced adopted object.
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
