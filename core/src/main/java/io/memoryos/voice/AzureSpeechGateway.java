package io.memoryos.voice;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Every Azure Speech SDK call, behind a seam so unit tests never load the SDK's native library (Northstar's
 * {@code AzureSpeechGateway}). {@link SpeechSdkGateway} is the real one.
 */
interface AzureSpeechGateway {
    /**
     * Starts continuous recognition of 16 kHz PCM16 mono audio. One locale is recognized as given; several are
     * identified continuously among themselves. Throws when the session cannot start.
     */
    Recognition recognize(AzureSpeechTarget target, String key, List<String> locales, RecognitionListener listener);

    /** Starts speaking one SSML document as 24 kHz MP3. Throws when synthesis cannot start. */
    Speech speak(AzureSpeechTarget target, String key, String ssml);

    /** Provider text only; failures carry no provider detail. Called on SDK threads. */
    interface RecognitionListener {
        /** The current guess for the utterance being spoken. */
        void recognizing(String text);

        /** A finished utterance, ended by the provider's silence detection. */
        void recognized(String text);

        void failed();
    }

    interface Recognition extends AutoCloseable {
        void write(byte[] pcm16k);

        /** Ends the audio and waits until the provider has recognized what it received. */
        void stop();

        @Override
        void close();
    }

    interface Speech extends AutoCloseable {
        /** The next MP3 bytes, or null at the end of the speech. Throws when the provider fails. */
        byte @Nullable [] read();

        /** Stops the speech in progress and releases it. */
        @Override
        void close();
    }
}
