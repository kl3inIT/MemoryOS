package io.memoryos;

public enum FailureCategory {
    VALIDATION,
    NOT_PERMITTED,
    NOT_FOUND,
    CONFLICT,
    GONE,
    // A cap the Tenant configured, not a fault: the caller may try again once the budget frees (MEM-123).
    LIMIT_EXCEEDED,
    SERVICE_UNAVAILABLE
}
