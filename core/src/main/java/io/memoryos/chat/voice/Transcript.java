package io.memoryos.chat.voice;

/** One transcript update. {@code isFinal} marks the committed text of a finished utterance (Onyx is_final). */
public record Transcript(String text, boolean isFinal) {}
