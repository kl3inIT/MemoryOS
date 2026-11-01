package io.memoryos.integration.whatsapp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The whatsmeow bridge as a child process of this one (MEM-283), as Hermes Agent starts its WhatsApp bridge: started
 * when the first bot opens, spoken to with one JSON object a line over its standard streams, and started again after a
 * back-off from one second to a minute when it ends. Its replies complete the call that asked; its events reach the
 * listener in the order the bridge wrote them, on a thread of their own, so a listener may call the bridge. Its log is
 * logged here. Closing it asks it to stop and ends it if it does not.
 */
final class WhatsAppBridge implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(WhatsAppBridge.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration BACKOFF_FIRST = Duration.ofSeconds(1);
    private static final Duration BACKOFF_MAX = Duration.ofMinutes(1);
    /** A bridge that ran this long before it ended is started again without waiting. */
    private static final Duration STEADY = Duration.ofMinutes(5);
    private static final Duration STOP_WAIT = Duration.ofSeconds(10);

    /** What the bridge says on its own: an event of a bot, or that it ended. */
    interface Listener {
        void event(JsonNode event);

        /** The bridge ended; every bot it held is closed, and opening one starts it again. */
        void exited();
    }

    /** The bridge refused a command, by its code, or could not be reached. */
    static final class Failure extends RuntimeException {
        private final String code;

        Failure(String code) {
            super("WhatsApp bridge: " + code, null, false, false);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private final Path binary;
    private final String logLevel;
    private final Supplier<String> database;
    private final Clock clock;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private volatile Listener listener = new Listener() {
        @Override public void event(JsonNode event) {}

        @Override public void exited() {}
    };
    private @Nullable Process process;
    private @Nullable OutputStream input;
    private @Nullable Instant startedAt;
    private Instant retryAt = Instant.MIN;
    private Duration backoff = BACKOFF_FIRST;
    private boolean closed;

    WhatsAppBridge(Path binary, String logLevel, Supplier<String> database) {
        this(binary, logLevel, database, Clock.systemUTC());
    }

    WhatsAppBridge(Path binary, String logLevel, Supplier<String> database, Clock clock) {
        this.binary = binary;
        this.logLevel = logLevel;
        this.database = database;
        this.clock = clock;
    }

    void listen(Listener listener) {
        this.listener = listener;
    }

    /**
     * Sends a command and waits for its reply's result.
     *
     * @throws Failure with the bridge's code, or {@code UNAVAILABLE} when the bridge cannot be started, ends or does not
     *         answer in time
     */
    JsonNode call(String op, Map<String, ?> fields, Duration timeout) {
        long id = ids.incrementAndGet();
        ObjectNode command = JSON.createObjectNode().put("id", id).put("op", op);
        fields.forEach((name, value) -> command.set(name, JSON.valueToTree(value)));
        var reply = new CompletableFuture<JsonNode>();
        pending.put(id, reply);
        try {
            write(command);
            var answered = reply.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!answered.path("ok").asBoolean(false)) throw new Failure(answered.path("error").asString("UNAVAILABLE"));
            return answered.path("result");
        } catch (TimeoutException late) {
            throw new Failure("UNAVAILABLE");
        } catch (ExecutionException ended) {
            throw ended.getCause() instanceof Failure failure ? failure : new Failure("UNAVAILABLE");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Failure("UNAVAILABLE");
        } finally {
            pending.remove(id);
        }
    }

    /** The message event {@code seq} was claimed; WhatsApp is acknowledged once the bridge reads this. */
    void ack(long seq) {
        try {
            write(JSON.createObjectNode().put("op", "ack").put("seq", seq));
        } catch (Failure unreachable) {
            // The bridge ended; WhatsApp delivers the message again to the next one, and the claim deduplicates it.
        }
    }

    private synchronized void write(ObjectNode command) {
        var out = start();
        try {
            out.write(JSON.writeValueAsBytes(command));
            out.write('\n');
            out.flush();
        } catch (IOException broken) {
            throw new Failure("UNAVAILABLE");
        }
    }

    private synchronized OutputStream start() {
        if (closed) throw new Failure("UNAVAILABLE");
        if (process != null && process.isAlive() && input != null) return input;
        Instant now = clock.instant();
        if (now.isBefore(retryAt)) throw new Failure("UNAVAILABLE");
        if (!Files.isExecutable(binary)) {
            LOG.atWarn().addKeyValue("event", "channel.whatsapp.bridge.missing").log("WhatsApp bridge binary is missing");
            throw new Failure("UNAVAILABLE");
        }
        try {
            var builder = new ProcessBuilder(binary.toString());
            var environment = builder.environment();
            environment.clear();
            environment.put("MEMORYOS_WHATSAPP_DATABASE", database.get());
            environment.put("MEMORYOS_WHATSAPP_LOG_LEVEL", logLevel);
            var started = builder.start();
            process = started;
            input = started.getOutputStream();
            startedAt = now;
            var ordered = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("whatsapp-bridge-events").factory());
            Thread.ofVirtual().name("whatsapp-bridge-out").start(() -> read(started, ordered));
            Thread.ofVirtual().name("whatsapp-bridge-log").start(() -> log(started.getErrorStream()));
            LOG.atInfo().addKeyValue("event", "channel.whatsapp.bridge.started").log("WhatsApp bridge started");
            return input;
        } catch (IOException | RuntimeException failed) {
            retry(now);
            LOG.atWarn().addKeyValue("event", "channel.whatsapp.bridge.start_failed")
                    .addKeyValue("error_type", failed.getClass().getName()).log("WhatsApp bridge could not start");
            throw new Failure("UNAVAILABLE");
        }
    }

    private void retry(Instant now) {
        retryAt = now.plus(backoff);
        backoff = backoff.multipliedBy(2).compareTo(BACKOFF_MAX) > 0 ? BACKOFF_MAX : backoff.multipliedBy(2);
    }

    /** Reads replies and events until the bridge ends, then fails what waits and tells the listener. */
    private void read(Process started, ExecutorService ordered) {
        try (var lines = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                JsonNode node;
                try {
                    node = JSON.readTree(line);
                } catch (RuntimeException unreadable) {
                    LOG.atWarn().addKeyValue("event", "channel.whatsapp.bridge.unreadable").log("WhatsApp bridge wrote a line that is not JSON");
                    continue;
                }
                if (node.has("event")) {
                    ordered.execute(() -> dispatch(node));
                } else {
                    var waiting = pending.get(node.path("id").asLong(-1));
                    if (waiting != null) waiting.complete(node);
                }
            }
        } catch (IOException ended) {
            // The streams close with the process.
        } finally {
            ended(started, ordered);
        }
    }

    private void dispatch(JsonNode event) {
        try {
            listener.event(event);
        } catch (RuntimeException failure) {
            LOG.atWarn().addKeyValue("event", "channel.whatsapp.event.failed")
                    .addKeyValue("error_type", failure.getClass().getName()).log("WhatsApp bridge event was not handled");
        }
    }

    private void ended(Process started, ExecutorService ordered) {
        synchronized (this) {
            if (process == started) {
                process = null;
                input = null;
                Instant now = clock.instant();
                if (startedAt != null && startedAt.plus(STEADY).isBefore(now)) backoff = BACKOFF_FIRST;
                retry(now);
            }
        }
        pending.values().forEach(waiting -> waiting.completeExceptionally(new Failure("UNAVAILABLE")));
        ordered.execute(() -> listener.exited());
        ordered.shutdown();
        if (!closed) {
            LOG.atWarn().addKeyValue("event", "channel.whatsapp.bridge.exited").addKeyValue("exit_code", exitCode(started))
                    .log("WhatsApp bridge ended");
        }
    }

    private static int exitCode(Process started) {
        try {
            return started.waitFor(5, TimeUnit.SECONDS) ? started.exitValue() : -1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** The bridge's log, one JSON object a line: its level, module and message, logged at that level. */
    private static void log(InputStream errors) {
        try (var lines = new BufferedReader(new InputStreamReader(errors, StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                String level = "WARN";
                String module = "";
                String message = line;
                try {
                    var node = JSON.readTree(line);
                    level = node.path("level").asString("WARN");
                    module = node.path("module").asString("");
                    message = node.path("message").asString("");
                } catch (RuntimeException plain) {
                    // A line that is not the bridge's JSON, such as a Go runtime panic, is logged as it is.
                }
                var at = switch (level) {
                    case "ERROR" -> LOG.atError();
                    case "INFO" -> LOG.atInfo();
                    case "DEBUG" -> LOG.atDebug();
                    default -> LOG.atWarn();
                };
                at.addKeyValue("event", "channel.whatsapp.bridge.log").addKeyValue("module", module).log(message);
            }
        } catch (IOException ended) {
            // The stream closes with the process.
        }
    }

    /** Asks the bridge to disconnect every bot and stop, and ends it if it does not. */
    @Override
    public void close() {
        Process running;
        synchronized (this) {
            closed = true;
            running = process;
            if (running != null && input != null) {
                try {
                    input.write("{\"op\":\"shutdown\"}\n".getBytes(StandardCharsets.UTF_8));
                    input.flush();
                    input.close();
                } catch (IOException gone) {
                    // Ended already.
                }
            }
        }
        if (running == null) return;
        try {
            if (!running.waitFor(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) running.destroyForcibly();
        } catch (InterruptedException interrupted) {
            running.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
