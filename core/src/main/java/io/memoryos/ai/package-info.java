/**
 * AI models: the Tenant's provider and model catalog, the provider adapters and their native clients, flow models,
 * and single model calls outside any conversation, such as a meeting's minutes and transcript corrections. Chat turns
 * run on these models; the catalog knows Chat's agents only by id.
 */
@ApplicationModule(displayName = "AI models", type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"shared", "iam :: tenant", "iam :: group", "audit", "usage"})
package io.memoryos.ai;

import org.springframework.modulith.ApplicationModule;
