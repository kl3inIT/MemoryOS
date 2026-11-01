package io.memoryos.ai;

import io.memoryos.ai.persistence.ModelCatalogRepository;
import io.memoryos.iam.TenantBootstrapped;
import io.memoryos.shared.TenantId;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisions a Tenant's model catalog: the Chat default, not yet chosen, and one row per model flow. It runs in the
 * Tenant bootstrap transaction, so a Tenant never commits without them, and every step is insert-only: a restart
 * re-runs it for the published Tenant without touching what an administrator has since changed. It adds no provider
 * and no model; as an Onyx production install, the catalog holds only what an administrator added (MEM-211).
 */
@Component
class ModelCatalogProvisioner {
    private static final Logger LOG = LoggerFactory.getLogger(ModelCatalogProvisioner.class);

    private final ModelCatalogRepository catalog;

    ModelCatalogProvisioner(ModelCatalogRepository catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
    }

    /** Runs in the transaction that created or re-verified the Tenant. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void bootstrapped(TenantBootstrapped event) {
        provision(event.tenantId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void provision(TenantId tenantId) {
        UUID tenant = tenantId.value();
        if (!catalog.initialize(tenant)) return;
        catalog.initializeFlows(tenant);
        LOG.atInfo().addKeyValue("event", "chat.tenant.catalog.provisioned")
                .addKeyValue("tenant_id", tenant)
                .log("Provisioned the Tenant's Chat model catalog with no model");
    }
}
