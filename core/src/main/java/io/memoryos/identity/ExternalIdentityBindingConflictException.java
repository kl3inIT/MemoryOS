package io.memoryos.identity;

public final class ExternalIdentityBindingConflictException extends IllegalStateException {

    public ExternalIdentityBindingConflictException() {
        super("external identity is already bound to another actor");
    }
}
