package io.memoryos;

/**
 * A capability's typed expected-failure catalogue entry: stable wire code, category, and safe message.
 */
public interface FailureReason {

    String code();

    FailureCategory category();

    String message();
}
