package io.memoryos.chat.persona;

import io.memoryos.chat.AssistantTemplate;
import io.memoryos.chat.AssistantTemplates;
import io.memoryos.chat.persona.persistence.JdbcAgentRepository;
import io.memoryos.chat.persona.persistence.JpaPersonaRepository;
import io.memoryos.chat.persona.persistence.PersonaEntity;
import io.memoryos.chat.persona.persistence.PersonaRevisions;
import io.memoryos.iam.TenantBootstrapped;
import io.memoryos.shared.TenantId;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a Tenant's rows of MemoryOS's assistant templates (MEM-281) from the catalogue. It runs in the Tenant
 * bootstrap transaction, which a restart re-runs, so each release brings every Tenant up to date: a missing template
 * is inserted, a row whose content differs from the catalogue is rewritten and a template the catalogue no longer has
 * is soft-deleted. Unchanged rows are not written. Rewriting is safe because nobody in the Tenant edits a template row;
 * its listing switch is the administrator's and is kept.
 */
@Component
public class AssistantTemplateProvisioner {
    private static final Logger LOGGER = LoggerFactory.getLogger(AssistantTemplateProvisioner.class);

    private final JpaPersonaRepository personas;
    private final JdbcAgentRepository agents;
    private final PersonaRevisions revisions;

    public AssistantTemplateProvisioner(JpaPersonaRepository personas, JdbcAgentRepository agents, PersonaRevisions revisions) {
        this.personas = personas; this.agents = agents; this.revisions = revisions;
    }

    /** Runs in the transaction that created or re-verified the Tenant. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void bootstrapped(TenantBootstrapped event) {
        provision(event.tenantId(), AssistantTemplates.all());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void provision(TenantId tenant, List<AssistantTemplate> catalogue) {
        UUID tenantId = tenant.value();
        var rows = new HashMap<String, PersonaEntity>();
        for (var row : personas.findByTenantIdAndTemplateKeyIsNotNull(tenantId))
            // A live row wins over a deleted one of the same template.
            rows.merge(Objects.requireNonNull(row.templateKey()), row, (kept, other) -> kept.deleted() ? other : kept);
        var keys = new LinkedHashSet<String>();
        int inserted = 0, rewritten = 0, removed = 0;
        for (var template : catalogue) {
            keys.add(template.key());
            var row = rows.get(template.key());
            if (row == null) {
                row = PersonaEntity.template(UUID.randomUUID(), tenantId, template.key());
                row.update(settings(template));
                row = personas.saveAndFlush(row);
                agents.replaceTools(tenantId, row.id(), new TreeSet<>(template.tools()), List.of());
                inserted++;
                continue;
            }
            boolean changed = false;
            if (row.deleted()) { row.restore(); changed = true; }
            if (differs(row, template)) { row.update(settings(template)); changed = true; }
            if (!agents.tools(tenantId, row.id()).equals(template.tools())) {
                agents.replaceTools(tenantId, row.id(), new TreeSet<>(template.tools()), List.of());
                revisions.advance(row);
                changed = true;
            }
            if (changed) rewritten++;
        }
        for (var row : rows.values()) {
            if (row.deleted() || keys.contains(row.templateKey())) continue;
            row.delete();
            removed++;
        }
        personas.flush();
        if (inserted + rewritten + removed > 0)
            LOGGER.atInfo().addKeyValue("event", "chat.template.provisioned").addKeyValue("tenant_id", tenantId)
                    .addKeyValue("inserted", inserted).addKeyValue("rewritten", rewritten).addKeyValue("removed", removed)
                    .log("Brought the organisation's assistant templates up to date");
    }

    /** The row stores the Vietnamese text; readers see their own language from the catalogue. */
    private static PersonaEntity.Settings settings(AssistantTemplate template) {
        var text = template.vi();
        return new PersonaEntity.Settings(text.name(), text.title(), text.description(), template.instructions(),
                text.greeting(), text.starters(), List.of(), null, null, template.avatarSeed(), null, template.grounded());
    }

    private static boolean differs(PersonaEntity row, AssistantTemplate template) {
        var text = template.vi();
        return !row.name().equals(text.name()) || !Objects.equals(row.title(), text.title())
                || !row.description().equals(text.description()) || !row.instructions().equals(template.instructions())
                || !Objects.equals(row.greeting(), text.greeting()) || !row.starterPrompts().equals(text.starters())
                || !row.avatarSeed().equals(template.avatarSeed()) || row.grounded() != template.grounded()
                || !row.sourceIds().isEmpty() || !row.fileIds().isEmpty() || row.modelConfigurationId() != null
                || row.reasoningEffort() != null || row.avatarFileId() != null;
    }
}
