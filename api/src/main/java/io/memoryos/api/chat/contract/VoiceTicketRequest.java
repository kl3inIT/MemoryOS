package io.memoryos.api.chat.contract;

import org.jspecify.annotations.Nullable;

/** An absent purpose issues a transcription ticket. */
public record VoiceTicketRequest(@Nullable VoiceTicketPurpose purpose) {}
