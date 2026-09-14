package io.memoryos.chat;

/** Turn progress published by tools and provider models; streamed, recorded and never containing raw tool payloads. */
public sealed interface ChatActivityEvent permits ChatToolEvent, ChatReasoningDelta {
}
