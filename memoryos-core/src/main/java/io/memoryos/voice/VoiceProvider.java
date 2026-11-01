package io.memoryos.voice;

/**
 * The implemented speech protocols (Onyx voice_provider parity), persisted and in the API. Identity only: what each
 * needs, suggests and offers comes from its adapter through {@link VoiceAdapterRegistry}. A provider exists only
 * together with its adapter.
 */
public enum VoiceProvider {
    OPENAI, OPENAI_COMPATIBLE, ELEVENLABS, AZURE, SONIOX
}
