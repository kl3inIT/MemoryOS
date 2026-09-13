package io.memoryos.iam.tenant.bootstrap;

public interface InitialTenantBootstrapper {

    InitialTenantBootstrapResult bootstrap(InitialTenantBootstrapRequest request);
}
