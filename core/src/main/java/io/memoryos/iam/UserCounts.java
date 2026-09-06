package io.memoryos.iam;

public record UserCounts(long active, long inactive, long invited) {

    public UserCounts {
        if (active < 0 || inactive < 0 || invited < 0) {
            throw new IllegalArgumentException("user counts must not be negative");
        }
    }
}
