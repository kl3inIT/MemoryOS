package io.memoryos.iam;

import io.memoryos.iam.group.DefaultGroupScopeService;
import io.memoryos.iam.group.DefaultIamAuthorization;
import io.memoryos.iam.group.IamAuditReaders;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The IAM beans the Worker runs: IAM's persistence, with its entities and repositories, the authorization decision
 * ({@link IamAuthorization}), Group scopes ({@link GroupScopeService}) and who may read the audit stream. The Worker
 * imports this class; it is deliberately not a component, so the API, which scans all of {@code io.memoryos},
 * registers these beans only once.
 */
@ComponentScan({"io.memoryos.iam.group.persistence", "io.memoryos.iam.identity.persistence",
        "io.memoryos.iam.invitation.persistence", "io.memoryos.iam.tenant.persistence",
        "io.memoryos.iam.user.persistence"})
@EntityScan("io.memoryos.iam")
@EnableJpaRepositories("io.memoryos.iam")
@Import({DefaultIamAuthorization.class, DefaultGroupScopeService.class, IamAuditReaders.class})
public class IamWorkerComponents {
}
