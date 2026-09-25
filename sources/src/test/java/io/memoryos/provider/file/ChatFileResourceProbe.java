package io.memoryos.provider.file;

import java.io.FilterInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.ObjectMapper;

/** Test-only child entrypoint. Invokes the production extractor, never a benchmark parser implementation. */
@org.jspecify.annotations.NullMarked
public final class ChatFileResourceProbe {
    private ChatFileResourceProbe() {}

    public static void main(String[] args) throws Exception {
        Path fixture = Path.of(args[0]); int callers = Integer.parseInt(args[1]); Path report = Path.of(args[2]);
        long size = Files.size(fixture);
        var mapper = new ObjectMapper();
        // No Mockito agent/temp JAR in the measured JVM. Fail immediately on accidental OCR routing.
        var noOcr = (ai.docling.serve.api.DoclingServeApi) java.lang.reflect.Proxy.newProxyInstance(
                ai.docling.serve.api.DoclingServeApi.class.getClassLoader(),
                new Class<?>[] {ai.docling.serve.api.DoclingServeApi.class},
                (_, method, _) -> { throw new AssertionError("OCR is outside this probe: " + method.getName()); });
        var docling = new DoclingSourceContentExtractor(new DoclingProperties(null, null, null, 0), mapper, noOcr);
        var extractor = new BoundedChatFileExtractor(docling, mapper);
        var active = new AtomicInteger(); var peakActive = new AtomicInteger(); var heapPeak = new AtomicLong();
        var memory = ManagementFactory.getMemoryMXBean();
        var ready = new CountDownLatch(callers); var start = new CountDownLatch(1);
        var outcomes = new ArrayList<Map<String, Object>>();
        long baselineHeap = memory.getHeapMemoryUsage().getUsed();
        long started = System.nanoTime();
        try (docling; var sampler = Executors.newSingleThreadScheduledExecutor(); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            sampler.scheduleAtFixedRate(() -> heapPeak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max), 0, 10, TimeUnit.MILLISECONDS);
            var tasks = new ArrayList<java.util.concurrent.Future<Map<String, Object>>>();
            for (int i = 0; i < callers; i++) {
                tasks.add(pool.submit(() -> {
                    ready.countDown(); start.await();
                    long submitted = System.nanoTime(); var firstRead = new AtomicLong();
                    var copying = new java.util.concurrent.atomic.AtomicBoolean();
                    try (var input = new FilterInputStream(Files.newInputStream(fixture)) {
                        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                            if (firstRead.compareAndSet(0, System.nanoTime())) {
                                copying.set(true); peakActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                            }
                            int count = super.read(buffer, offset, length);
                            if (count < 0 && copying.compareAndSet(true, false)) active.decrementAndGet();
                            return count;
                        }
                    }) {
                        var content = extractor.extract(input, size, "large.xlsx");
                        if (!content.normalizedText().contains("B1: 127")) throw new AssertionError("Missing table value");
                        var chunks = new io.memoryos.document.application.StructuredDocumentChunker(mapper).chunk(content.title(), content.structuredJson());
                        if (chunks.stream().noneMatch(chunk -> chunk.content().contains("[B1] 127"))) throw new AssertionError("Missing chunk value");
                        long ended = System.nanoTime();
                        return Map.of("waitMs", (firstRead.get() - submitted) / 1_000_000,
                                "spoolParseChunkMs", (ended - firstRead.get()) / 1_000_000,
                                "totalMs", (ended - submitted) / 1_000_000, "chunks", chunks.size());
                    } finally { if (copying.compareAndSet(true, false)) active.decrementAndGet(); }
                }));
            }
            if (!ready.await(10, TimeUnit.SECONDS)) throw new AssertionError("Callers failed to start");
            start.countDown();
            for (var task : tasks) outcomes.add(task.get(120, TimeUnit.SECONDS));
            sampler.shutdownNow();
        }
        mapper.writeValue(report.toFile(), Map.of("fileBytes", size, "callers", callers, "completed", outcomes.size(),
                "peakActiveInputCopies", peakActive.get(), "heapLimitBytes", Runtime.getRuntime().maxMemory(),
                "heapBaselineBytes", baselineHeap, "sampledPeakHeapBytes", heapPeak.get(),
                "elapsedMs", (System.nanoTime() - started) / 1_000_000, "tasks", outcomes));
    }
}
