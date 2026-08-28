package io.memoryos.provider.file;

import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import io.memoryos.ingestion.ExtractionResult;
import io.memoryos.ingestion.SourceContentExtractor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TikaSourceContentExtractor implements SourceContentExtractor, AutoCloseable {

    private static final Duration DEFAULT_EXTRACTION_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration PROCESS_EXIT_GRACE = Duration.ofSeconds(5);

    private final Duration extractionTimeout;
    private final Set<Process> processes = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public TikaSourceContentExtractor() {
        this(DEFAULT_EXTRACTION_TIMEOUT);
    }

    TikaSourceContentExtractor(Duration extractionTimeout) {
        this.extractionTimeout = Objects.requireNonNull(
                extractionTimeout,
                "extractionTimeout must not be null"
        );
        if (extractionTimeout.isNegative()) {
            throw new IllegalArgumentException("extractionTimeout must not be negative");
        }
    }

    @Override
    public ExtractionResult extract(byte[] content, String filename) throws ExtractionException {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(filename, "filename must not be null");
        if (closed.get()) {
            throw new IllegalStateException("extractor is closed");
        }
        if (extractionTimeout.isZero()) {
            throw new ExtractionException(ExtractionFailure.TIMEOUT, "document extraction timed out");
        }

        Path directory = null;
        Process process = null;
        try {
            directory = Files.createTempDirectory("memoryos-tika-");
            Path arguments = directory.resolve("java.args");
            Path request = directory.resolve("request.bin");
            Path response = directory.resolve("response.bin");
            TikaExtractionProcess.writeRequest(request, content, filename);
            writeArgumentFile(arguments, request, response);
            process = startProcess(arguments);
            processes.add(process);
            if (closed.get()) {
                terminate(process);
                throw new IllegalStateException("extractor is closed");
            }
            long timeoutMillis = Math.max(1, extractionTimeout.toMillis());
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                terminate(process);
                throw new ExtractionException(ExtractionFailure.TIMEOUT, "document extraction timed out");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(response)) {
                throw new ExtractionException(ExtractionFailure.INTERNAL, "document extraction process failed");
            }
            return TikaExtractionProcess.readResponse(response);
        } catch (InterruptedException exception) {
            terminate(process);
            Thread.currentThread().interrupt();
            throw new ExtractionException(ExtractionFailure.TIMEOUT, "document extraction was interrupted", exception);
        } catch (IOException exception) {
            if (process != null) {
                terminate(process);
            }
            throw new ExtractionException(ExtractionFailure.INTERNAL, "document extraction process failed", exception);
        } finally {
            if (process != null) {
                processes.remove(process);
            }
            deleteTemporaryFiles(directory);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Process[] active = processes.toArray(Process[]::new);
        for (Process process : active) {
            destroyProcessTree(process);
        }
        long deadline = System.nanoTime() + PROCESS_EXIT_GRACE.toNanos();
        for (Process process : active) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                process.waitFor(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        processes.clear();
    }

    private static Process startProcess(Path arguments) throws IOException {
        return new ProcessBuilder(javaCommand(), "@" + arguments)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static void writeArgumentFile(Path path, Path request, Path response) throws IOException {
        String arguments = String.join(
                System.lineSeparator(),
                "-Xmx512m",
                "-XX:+ExitOnOutOfMemoryError",
                "-Djava.awt.headless=true",
                "-cp",
                quoteArgument(extractionClasspath()),
                TikaExtractionProcess.class.getName(),
                quoteArgument(request.toString()),
                quoteArgument(response.toString())
        );
        Files.writeString(path, arguments, StandardCharsets.UTF_8);
    }

    private static String quoteArgument(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String javaCommand() {
        String configured = System.getProperty("memoryos.extraction.java-command");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static String extractionClasspath() {
        String configured = System.getenv("MEMORYOS_EXTRACTION_CLASSPATH");
        return configured == null || configured.isBlank()
                ? System.getProperty("java.class.path")
                : configured;
    }

    private static void terminate(Process process) {
        destroyProcessTree(process);
        try {
            process.waitFor(PROCESS_EXIT_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void destroyProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void deleteTemporaryFiles(Path directory) {
        if (directory == null) {
            return;
        }
        try {
            Files.deleteIfExists(directory.resolve("response.bin"));
            Files.deleteIfExists(directory.resolve("java.args"));
            Files.deleteIfExists(directory.resolve("request.bin"));
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            directory.toFile().deleteOnExit();
        }
    }
}
