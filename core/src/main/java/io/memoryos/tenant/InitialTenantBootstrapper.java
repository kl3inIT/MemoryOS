package io.memoryos.tenant;

public interface InitialTenantBootstrapper {

    InitialTenantBootstrapResult bootstrap(InitialTenantBootstrapRequest request);
}
