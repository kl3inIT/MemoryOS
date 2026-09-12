package io.memoryos.iam;

import io.memoryos.iam.persistence.JpaActorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActorLanguageService {
    private final JpaActorRepository actors;
    private final TenantAccessResolver tenants;

    public ActorLanguageService(JpaActorRepository actors, TenantAccessResolver tenants) {
        this.actors = actors;
        this.tenants = tenants;
    }

    @Transactional(readOnly = true)
    public String read(ActorId actor) {
        return actors.findById(actor.value()).orElseThrow(() -> new IllegalStateException("Actor must exist"))
                .getUiLanguage();
    }

    @Transactional
    public String save(ActorId actor, String language) {
        if (!"vi".equals(language) && !"en".equals(language)) {
            throw new IamException(IamFailureReason.LANGUAGE_INVALID, "Unsupported account language");
        }
        tenants.lockActiveMembership(actor).orElseThrow(() ->
                new IamException(IamFailureReason.ACCESS_DENIED, "Active membership required for preferences"));
        actors.refreshForUpdate(actor.value()).setUiLanguage(language);
        return language;
    }
}
