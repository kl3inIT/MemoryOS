package io.memoryos.iam.tenant.bootstrap;

public final class TenantBootstrapConflictException extends RuntimeException {

    public TenantBootstrapConflictException(String message) {
        super(message);
    }
}
