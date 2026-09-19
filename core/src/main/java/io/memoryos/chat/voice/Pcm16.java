package io.memoryos.chat.voice;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** PCM16 little-endian mono audio at 24 kHz, the voice WebSocket format (Onyx parity). */
final class Pcm16 {
    static final int SAMPLE_RATE = 24_000;
    static final int BYTES_PER_SECOND = SAMPLE_RATE * 2;
    /** The only PCM rate the Azure short-audio API accepts. */
    static final int SAMPLE_RATE_16K = 16_000;
    static final int BYTES_PER_SECOND_16K = SAMPLE_RATE_16K * 2;
    static final int WAV_HEADER_BYTES = 44;
    /** Onyx silence gate: speech reaches this RMS amplitude in at least one 100 ms frame. */
    static final double SPEECH_RMS = 150;
    private static final int FRAME_BYTES = BYTES_PER_SECOND / 10;
    private static final int PADDING_BYTES = BYTES_PER_SECOND / 2;
    private static final double MIN_RELATIVE_RMS = 30;
    private static final double RELATIVE_FACTOR = 2.5;

    private Pcm16() {}

    /** Root mean square amplitude of the whole samples in the range. */
    static double rms(byte[] pcm, int offset, int length) {
        int samples = length / 2;
        if (samples == 0) return 0;
        double sum = 0;
        for (int i = 0; i < samples; i++) {
            int index = offset + 2 * i;
            double sample = (short) ((pcm[index] & 0xff) | (pcm[index + 1] << 8));
            sum += sample * sample;
        }
        return Math.sqrt(sum / samples);
    }

    static boolean hasSpeech(byte[] pcm, int offset, int length) {
        for (int frame = offset; frame < offset + length; frame += FRAME_BYTES) {
            if (rms(pcm, frame, Math.min(FRAME_BYTES, offset + length - frame)) >= SPEECH_RMS) return true;
        }
        return false;
    }

    /**
     * Keeps the span from the first to the last voiced 100 ms frame plus half a second of padding (Onyx trim_silence).
     * A recording without loud frames uses a threshold relative to its noise floor, so quiet speech is kept while
     * uniform noise is not. Returns an empty array when nothing is voiced.
     */
    static byte[] trimSilence(byte[] pcm, int length) {
        int even = length - length % 2;
        int frames = (even + FRAME_BYTES - 1) / FRAME_BYTES;
        if (frames == 0) return new byte[0];
        double[] levels = new double[frames];
        for (int i = 0; i < frames; i++) {
            int start = i * FRAME_BYTES;
            levels[i] = rms(pcm, start, Math.min(FRAME_BYTES, even - start));
        }
        double threshold = SPEECH_RMS;
        if (Arrays.stream(levels).noneMatch(level -> level >= SPEECH_RMS)) {
            double[] sorted = levels.clone();
            Arrays.sort(sorted);
            double floor = sorted[(sorted.length - 1) / 10];
            threshold = Math.max(MIN_RELATIVE_RMS, floor * RELATIVE_FACTOR);
        }
        int first = -1;
        int last = -1;
        for (int i = 0; i < frames; i++) {
            if (levels[i] < threshold) continue;
            if (first < 0) first = i;
            last = i;
        }
        if (first < 0) return new byte[0];
        int from = Math.max(0, first * FRAME_BYTES - PADDING_BYTES);
        int to = Math.min(even, (last + 1) * FRAME_BYTES + PADDING_BYTES);
        return Arrays.copyOfRange(pcm, from, to);
    }

    /** Wraps 24 kHz PCM16 mono samples in a RIFF/WAVE header for transcription uploads. */
    static byte[] wav(byte[] pcm, int offset, int length) {
        return wav(pcm, offset, length, SAMPLE_RATE);
    }

    static byte[] wav(byte[] pcm, int offset, int length, int sampleRate) {
        var buffer = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(US_ASCII)).putInt(36 + length).put("WAVE".getBytes(US_ASCII))
                .put("fmt ".getBytes(US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(US_ASCII)).putInt(length).put(pcm, offset, length);
        return buffer.array();
    }

    /** Resamples 24 kHz PCM16 to 16 kHz by linear interpolation, which is enough for speech recognition. */
    static byte[] resampleTo16k(byte[] pcm, int offset, int length) {
        int input = length / 2;
        int output = (int) ((long) input * SAMPLE_RATE_16K / SAMPLE_RATE);
        byte[] result = new byte[output * 2];
        double step = (double) SAMPLE_RATE / SAMPLE_RATE_16K;
        for (int i = 0; i < output; i++) {
            double position = i * step;
            int index = (int) position;
            int from = sample(pcm, offset, index);
            int to = index + 1 < input ? sample(pcm, offset, index + 1) : from;
            int value = (int) Math.round(from + (to - from) * (position - index));
            result[2 * i] = (byte) value;
            result[2 * i + 1] = (byte) (value >> 8);
        }
        return result;
    }

    private static int sample(byte[] pcm, int offset, int index) {
        int at = offset + 2 * index;
        return (short) ((pcm[at] & 0xff) | (pcm[at + 1] << 8));
    }
}
