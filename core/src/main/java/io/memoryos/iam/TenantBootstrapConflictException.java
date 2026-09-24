package io.memoryos.iam;

public final class TenantBootstrapConflictException extends RuntimeException {

    public TenantBootstrapConflictException(String message) {
        super(message);
    }
}
