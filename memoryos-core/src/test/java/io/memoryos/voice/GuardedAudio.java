package io.memoryos.voice;

import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A recording far larger than any buffer a streamed upload needs. It refuses to be read whole, and records the
 * largest single read it was asked for, so a test fails if anything materialises the recording in memory.
 */
final class GuardedAudio implements AudioSource {
    final AtomicInteger opened = new AtomicInteger();
    final AtomicLong served = new AtomicLong();
    final AtomicInteger largestRead = new AtomicInteger();
    private final long size;

    GuardedAudio(long size) {
        this.size = size;
    }

    @Override
    public InputStream open() {
        opened.incrementAndGet();
        return new InputStream() {
            private long left = size;

            @Override
            public int read() {
                if (left == 0) return -1;
                left--;
                served.incrementAndGet();
                return 7;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                largestRead.accumulateAndGet(length, Math::max);
                if (left == 0) return -1;
                int count = (int) Math.min(length, left);
                Arrays.fill(buffer, offset, offset + count, (byte) 7);
                left -= count;
                served.addAndGet(count);
                return count;
            }

            @Override
            public byte[] readAllBytes() {
                throw new AssertionError("the recording was read whole");
            }

            @Override
            public byte[] readNBytes(int length) {
                throw new AssertionError("the recording was read whole");
            }
        };
    }
}
