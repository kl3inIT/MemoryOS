package io.memoryos.tenant;

public final class TenantBootstrapConflictException extends RuntimeException {

    public TenantBootstrapConflictException(String message) {
        super(message);
    }
}
