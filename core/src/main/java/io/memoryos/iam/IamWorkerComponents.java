package io.memoryos.iam;

import io.memoryos.iam.group.DefaultGroupScopeService;
import io.memoryos.iam.group.DefaultIamAuthorization;
import io.memoryos.iam.group.IamAuditReaders;
import org.springframework.context.annotation.Import;

/**
 * The IAM beans the Worker runs, for a Worker that scans only IAM's persistence packages: the authorization decision
 * ({@link IamAuthorization}), Group scopes ({@link GroupScopeService}) and who may read the audit stream. The Worker
 * imports this class; it is deliberately not a component, so the API, which scans all of {@code io.memoryos},
 * registers these beans only once.
 */
@Import({DefaultIamAuthorization.class, DefaultGroupScopeService.class, IamAuditReaders.class})
public class IamWorkerComponents {
}
