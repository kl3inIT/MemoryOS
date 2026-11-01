package io.memoryos.voice;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Dictation over the provider's realtime socket; {@code batch} transcribes whatever the socket could not. */
interface RealtimeTranscriptionAdapter extends VoiceAdapter {
    /** Empty when this connection has no realtime socket; a failure to open falls back to chunked transcription. */
    Optional<TranscriptionSession> openRealtime(VoiceConnectionService.Connection connection, String key,
                                                @Nullable String language, String user, Function<byte[], String> batch,
                                                Consumer<Transcript> listener, Runnable release, MeterRegistry meters);
}
