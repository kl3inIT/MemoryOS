package io.memoryos.usage;

/**
 * Who a limit caps. {@code PERSON} is one budget applied to each person separately, as Onyx's {@code user} scope is,
 * not a budget for one named person.
 */
public enum AiUsageLimitScope {
    TENANT, GROUP, PERSON
}
