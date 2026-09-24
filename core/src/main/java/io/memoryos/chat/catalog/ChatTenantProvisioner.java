package io.memoryos.chat.catalog;

import io.memoryos.chat.application.PersonaProperties;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.ModelCatalogRepository;
import io.memoryos.iam.tenant.TenantBootstrapped;
import io.memoryos.shared.TenantId;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisions a Tenant's Chat defaults: the deployment's OpenAI provider and model as the Chat default, one row per
 * model flow, and the built-in default agent. It runs in the Tenant bootstrap transaction, so a Tenant never commits
 * without them, and every step is insert-only: a restart re-runs it for the published Tenant without touching what
 * an administrator has since changed.
 */
@Component
public class ChatTenantProvisioner {
    private static final Logger LOG = LoggerFactory.getLogger(ChatTenantProvisioner.class);

    private final ModelCatalogRepository catalog;
    private final JdbcChatRepository chats;
    private final ChatProviderAdapters adapters;
    private final PersonaProperties persona;
    private final ModelCatalogService.Deployment deployment;

    public ChatTenantProvisioner(ModelCatalogRepository catalog, JdbcChatRepository chats, ChatProviderAdapters adapters,
            PersonaProperties persona, ModelCatalogService.Deployment deployment) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.chats = Objects.requireNonNull(chats, "chats must not be null");
        this.adapters = Objects.requireNonNull(adapters, "adapters must not be null");
        this.persona = Objects.requireNonNull(persona, "persona must not be null");
        this.deployment = Objects.requireNonNull(deployment, "deployment must not be null");
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
        if (catalog.initialize(tenant)) {
            UUID providerId = UUID.randomUUID();
            var provider = new LlmProvider(providerId, tenant, "OpenAI", "openai", deployment.baseUrl(), true, true,
                    ProviderCredentials.DEPLOYMENT, 1, Set.of(), Set.of(), DataBoundary.EXTERNAL);
            ModelCatalogService.validateEndpoint(provider.baseUrl());
            ModelCatalogService.validateModel(adapters, provider, deployment.modelName(), deployment.settings());
            catalog.insertProvider(provider, "deployment");
            var model = new ModelConfiguration(UUID.randomUUID(), tenant, providerId, deployment.modelName(), deployment.modelName(), true,
                    deployment.settings(), 1);
            catalog.insertModel(model);
            catalog.setDefault(tenant, model.id(), 1);
            catalog.initializeFlows(tenant);
            LOG.atInfo().addKeyValue("event", "chat.tenant.catalog.provisioned")
                    .addKeyValue("tenant_id", tenant)
                    .addKeyValue("model_name", deployment.modelName())
                    .log("Provisioned the Tenant's Chat model catalog from the deployment model");
        }
        chats.provisionPersona(tenantId, persona.getName(), persona.getInstructions(), persona.getModel());
    }
}
