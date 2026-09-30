package io.memoryos.voice;

import com.microsoft.cognitiveservices.speech.AudioDataStream;
import com.microsoft.cognitiveservices.speech.AutoDetectSourceLanguageConfig;
import com.microsoft.cognitiveservices.speech.CancellationReason;
import com.microsoft.cognitiveservices.speech.PropertyId;
import com.microsoft.cognitiveservices.speech.ResultReason;
import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.microsoft.cognitiveservices.speech.SpeechRecognizer;
import com.microsoft.cognitiveservices.speech.SpeechSynthesisOutputFormat;
import com.microsoft.cognitiveservices.speech.SpeechSynthesisResult;
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer;
import com.microsoft.cognitiveservices.speech.StreamStatus;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import com.microsoft.cognitiveservices.speech.audio.AudioInputStream;
import com.microsoft.cognitiveservices.speech.audio.AudioStreamFormat;
import com.microsoft.cognitiveservices.speech.audio.PushAudioInputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The Azure Speech SDK (MEM-137). Each recognition and each speech owns its SDK objects and closes them; nothing is
 * shared between sessions. The SDK's native library loads on first use, so an image without it fails the first
 * session, which the callers report as an unavailable provider.
 */
@Component
final class SpeechSdkGateway implements AzureSpeechGateway {
    private static final Duration START_TIMEOUT = Duration.ofSeconds(10);
    /** Recognition of audio already sent ends within a few seconds of the stream closing. */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(8);
    private static final int READ_BYTES = 16 * 1024;
    /** Named in Microsoft's Speech SDK README; the SDK has no typed property for it. */
    private static final String TELEMETRY_PROPERTY = "SPEECH-TelemetryDataEnabled";

    @Override
    public Recognition recognize(AzureSpeechTarget target, String key, List<String> locales, RecognitionListener listener) {
        SpeechConfig config = config(target, key);
        PushAudioInputStream stream = null;
        AudioConfig audio = null;
        AutoDetectSourceLanguageConfig languages = null;
        SpeechRecognizer recognizer = null;
        try {
            stream = AudioInputStream.createPushStream(AudioStreamFormat.getWaveFormatPCM(16_000, (short) 16, (short) 1));
            audio = AudioConfig.fromStreamInput(stream);
            if (locales.size() == 1) {
                config.setSpeechRecognitionLanguage(locales.getFirst());
                recognizer = new SpeechRecognizer(config, audio);
            } else {
                config.setProperty(PropertyId.SpeechServiceConnection_LanguageIdMode, "Continuous");
                languages = AutoDetectSourceLanguageConfig.fromLanguages(locales);
                recognizer = new SpeechRecognizer(config, languages, audio);
            }
            var session = new SdkRecognition(config, stream, audio, languages, recognizer);
            recognizer.recognizing.addEventListener((_, event) -> {
                String text = event.getResult().getText();
                if (text != null && !text.isBlank()) listener.recognizing(text);
            });
            recognizer.recognized.addEventListener((_, event) -> {
                var result = event.getResult();
                if (result.getReason() == ResultReason.RecognizedSpeech && result.getText() != null && !result.getText().isBlank())
                    listener.recognized(result.getText());
            });
            recognizer.canceled.addEventListener((_, event) -> {
                if (event.getReason() == CancellationReason.Error) listener.failed();
                session.stopped.complete(null);
            });
            recognizer.sessionStopped.addEventListener((_, _) -> session.stopped.complete(null));
            recognizer.startContinuousRecognitionAsync().get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return session;
        } catch (Exception | LinkageError failed) {
            if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
            closeAll(recognizer, languages, audio, stream, config);
            throw new IllegalStateException("Azure speech recognition unavailable");
        }
    }

    @Override
    public Speech speak(AzureSpeechTarget target, String key, String ssml) {
        SpeechConfig config = config(target, key);
        SpeechSynthesizer synthesizer = null;
        SpeechSynthesisResult result = null;
        try {
            config.setSpeechSynthesisOutputFormat(SpeechSynthesisOutputFormat.Audio24Khz48KBitRateMonoMp3);
            // No audio output device: the audio is read from the result's stream.
            synthesizer = new SpeechSynthesizer(config, (AudioConfig) null);
            result = synthesizer.StartSpeakingSsmlAsync(ssml).get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (result.getReason() == ResultReason.Canceled) throw new IllegalStateException("Azure speech canceled");
            return new SdkSpeech(config, synthesizer, result, AudioDataStream.fromResult(result));
        } catch (Exception | LinkageError failed) {
            if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
            closeAll(result, synthesizer, config);
            throw new IllegalStateException("Azure speech synthesis unavailable");
        }
    }

    private static SpeechConfig config(AzureSpeechTarget target, String key) {
        try {
            var endpoint = target.endpoint();
            SpeechConfig config = endpoint != null ? SpeechConfig.fromEndpoint(endpoint, key)
                    : SpeechConfig.fromSubscription(key, target.region());
            // Only the audio and text a request needs leave MemoryOS; the SDK's usage telemetry to Microsoft is off.
            config.setProperty(TELEMETRY_PROPERTY, "false");
            return config;
        } catch (RuntimeException | LinkageError failed) {
            throw new IllegalStateException("Azure Speech SDK unavailable");
        }
    }

    /** Closes in order, each even when an earlier one fails; the SDK objects hold native handles. */
    private static void closeAll(@Nullable AutoCloseable... resources) {
        for (var resource : resources) {
            if (resource == null) continue;
            try { resource.close(); } catch (Exception ignored) { /* Already failing or released. */ }
        }
    }

    private static final class SdkRecognition implements Recognition {
        private final SpeechConfig config;
        private final PushAudioInputStream stream;
        private final AudioConfig audio;
        private final @Nullable AutoDetectSourceLanguageConfig languages;
        private final SpeechRecognizer recognizer;
        private final CompletableFuture<Void> stopped = new CompletableFuture<>();
        private boolean closed;

        private SdkRecognition(SpeechConfig config, PushAudioInputStream stream, AudioConfig audio,
                               @Nullable AutoDetectSourceLanguageConfig languages, SpeechRecognizer recognizer) {
            this.config = config; this.stream = stream; this.audio = audio; this.languages = languages;
            this.recognizer = recognizer;
        }

        @Override
        public synchronized void write(byte[] pcm16k) {
            if (!closed) stream.write(pcm16k);
        }

        @Override
        public void stop() {
            synchronized (this) {
                if (closed) return;
                stream.close();
            }
            try {
                stopped.get(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                recognizer.stopContinuousRecognitionAsync().get(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception failed) {
                if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new IllegalStateException("Azure speech recognition did not finish");
            }
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            closeAll(recognizer, languages, audio, stream, config);
        }
    }

    private static final class SdkSpeech implements Speech {
        private final SpeechConfig config;
        private final SpeechSynthesizer synthesizer;
        private final SpeechSynthesisResult result;
        private final AudioDataStream audio;
        private final byte[] buffer = new byte[READ_BYTES];
        private boolean closed;

        private SdkSpeech(SpeechConfig config, SpeechSynthesizer synthesizer, SpeechSynthesisResult result,
                          AudioDataStream audio) {
            this.config = config; this.synthesizer = synthesizer; this.result = result; this.audio = audio;
        }

        @Override
        public synchronized byte @Nullable [] read() {
            if (closed) return null;
            long read = audio.readData(buffer);
            if (read > 0) return Arrays.copyOf(buffer, (int) read);
            var status = audio.getStatus();
            if (status == StreamStatus.Canceled)
                throw new IllegalStateException("Azure speech canceled");
            return null;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            try {
                synthesizer.StopSpeakingAsync().get(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception stopFailed) {
                // The speech is being discarded; only a pending interrupt must survive.
                if (stopFailed instanceof InterruptedException) Thread.currentThread().interrupt();
            }
            closeAll(audio, result, synthesizer, config);
        }
    }
}
