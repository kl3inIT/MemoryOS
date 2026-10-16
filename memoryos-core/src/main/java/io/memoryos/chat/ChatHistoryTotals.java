package io.memoryos.chat;

/** The counts above the list: how much was asked in this period, and how it was rated. */
public record ChatHistoryTotals(long conversations, long positive, long negative) {}
