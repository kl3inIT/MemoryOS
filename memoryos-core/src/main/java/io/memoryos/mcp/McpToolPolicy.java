package io.memoryos.mcp;

/** What a tool may do in Chat: run when the model calls it, ask the person first, or never be offered (MEM-264). */
public enum McpToolPolicy {
    ALWAYS,
    ASK,
    OFF
}
