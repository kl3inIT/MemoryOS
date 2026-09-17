package io.memoryos.mcp;

/** Explicit change to a write-only secret; responses never return the stored value. */
public enum McpSecretAction { KEEP, REPLACE, REMOVE }
