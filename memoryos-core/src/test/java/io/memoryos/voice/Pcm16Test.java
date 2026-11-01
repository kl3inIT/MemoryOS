package io.memoryos.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class Pcm16Test {
    static byte[] tone(double seconds, int amplitude) {
        int samples = (int) (seconds * Pcm16.SAMPLE_RATE);
        var buffer = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) buffer.putShort((short) (amplitude * Math.sin(2 * Math.PI * 220 * i / Pcm16.SAMPLE_RATE)));
        return buffer.array();
    }

    static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) size += part.length;
        var result = ByteBuffer.allocate(size);
        for (byte[] part : parts) result.put(part);
        return result.array();
    }

    @Test
    void wavHeaderDescribesMono24kHzPcm16() {
        byte[] pcm = tone(0.1, 1000);
        var wav = ByteBuffer.wrap(Pcm16.wav(pcm, 0, pcm.length)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(44 + pcm.length, wav.capacity());
        assertEquals("RIFF", new String(wav.array(), 0, 4));
        assertEquals(1, wav.getShort(22));
        assertEquals(24_000, wav.getInt(24));
        assertEquals(16, wav.getShort(34));
        assertEquals(pcm.length, wav.getInt(40));
    }

    @Test
    void resamplingTo16kHzInterpolatesTwoSamplesForEveryThree() {
        var input = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        for (short value : new short[] {0, 300, 600, 900, 1200, 1500}) input.putShort(value);
        var output = ByteBuffer.wrap(Pcm16.resampleTo16k(input.array(), 0, 12)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(8, output.capacity());
        assertEquals(0, output.getShort(0));
        assertEquals(450, output.getShort(2));
        assertEquals(900, output.getShort(4));
        assertEquals(1350, output.getShort(6));
        assertEquals(Pcm16.BYTES_PER_SECOND_16K, Pcm16.resampleTo16k(tone(1, 3000), 0, Pcm16.BYTES_PER_SECOND).length);
        assertEquals(16_000, ByteBuffer.wrap(Pcm16.wav(new byte[2], 0, 2, Pcm16.SAMPLE_RATE_16K)).order(ByteOrder.LITTLE_ENDIAN).getInt(24));
    }

    @Test
    void silenceIsNotSpeechButAnAudibleToneIs() {
        assertFalse(Pcm16.hasSpeech(new byte[Pcm16.BYTES_PER_SECOND], 0, Pcm16.BYTES_PER_SECOND));
        byte[] voiced = concat(new byte[Pcm16.BYTES_PER_SECOND], tone(0.2, 3000));
        assertTrue(Pcm16.hasSpeech(voiced, 0, voiced.length));
    }

    @Test
    void trimmingKeepsVoicedAudioWithHalfASecondOfPadding() {
        byte[] recording = concat(new byte[Pcm16.BYTES_PER_SECOND * 3], tone(1, 3000), new byte[Pcm16.BYTES_PER_SECOND * 3]);
        byte[] trimmed = Pcm16.trimSilence(recording, recording.length);
        assertEquals(Pcm16.BYTES_PER_SECOND * 2, trimmed.length);
    }

    @Test
    void quietSpeechAboveTheNoiseFloorIsKeptWhileSilenceAndUniformNoiseAreDropped() {
        byte[] quiet = concat(tone(2, 20), tone(1, 120), tone(2, 20));
        assertTrue(Pcm16.trimSilence(quiet, quiet.length).length > 0);
        byte[] silence = new byte[Pcm16.BYTES_PER_SECOND * 2];
        assertEquals(0, Pcm16.trimSilence(silence, silence.length).length);
        byte[] noise = tone(3, 100);
        assertEquals(0, Pcm16.trimSilence(noise, noise.length).length);
    }
}
