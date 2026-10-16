package io.memoryos.integration.opensandbox;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.SandboxManager;
import com.alibaba.opensandbox.sandbox.domain.services.Filesystem;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException;
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxNotFoundException;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.NetworkPolicy;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.NetworkRule;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxFilter;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxState;
import io.memoryos.sandbox.SandboxCommand;
import io.memoryos.sandbox.SandboxCommandResult;
import io.memoryos.sandbox.SandboxEntry;
import io.memoryos.sandbox.SandboxFileException;
import io.memoryos.sandbox.SandboxGoneException;
import io.memoryos.sandbox.SandboxRuntime;
import io.memoryos.sandbox.SandboxService;
import io.memoryos.sandbox.SandboxSpec;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link SandboxRuntime} on an OpenSandbox server (MEM-270). Every call after creation goes through the server's
 * proxy ({@code useServerProxy}), because the API, the Worker and the channels process sit on an internal network
 * that cannot reach the ports sandboxes publish (MEM-270 probe, topology). Each sandbox is created with a network
 * policy that denies everything but the allowed domains. File calls run as the sandbox user
 * ({@code filesWithIdentity}, OpenSandbox 1.1.1): execd itself runs as root, and its default file routes would follow
 * a link the user's commands planted to any file of the sandbox.
 */
public class OpenSandboxRuntime implements SandboxRuntime, AutoCloseable {
    public static final String NAME = "OPENSANDBOX";
    private static final int FILE_MODE = 644;
    private static final int PAGE = 100;

    private final ConnectionConfig connection;
    private final SandboxManager manager;
    private final Duration readyTimeout;

    public OpenSandboxRuntime(OpenSandboxProperties properties) {
        URI endpoint = URI.create(properties.endpoint());
        String domain = endpoint.getPort() < 0 ? endpoint.getHost() : endpoint.getHost() + ":" + endpoint.getPort();
        this.connection = ConnectionConfig.builder().domain(domain).protocol(endpoint.getScheme())
                .apiKey(properties.apiKey()).requestTimeout(properties.requestTimeout()).useServerProxy(true).build();
        this.manager = SandboxManager.builder().connectionConfig(connection).build();
        this.readyTimeout = properties.readyTimeout();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String create(SandboxSpec spec) {
        var policy = NetworkPolicy.builder().defaultAction(NetworkPolicy.DefaultAction.DENY);
        for (String domain : spec.allowedDomains())
            policy.addEgress(NetworkRule.builder().action(NetworkRule.Action.ALLOW).target(domain).build());
        try (Sandbox sandbox = Sandbox.builder().connectionConfig(connection).image(spec.image())
                .timeout(spec.timeToLive()).readyTimeout(readyTimeout)
                .resource(Map.of("cpu", spec.cpu(), "memory", spec.memory()))
                .networkPolicy(policy.build()).metadata(spec.labels()).build()) {
            return sandbox.getId();
        }
    }

    @Override
    public void renew(String id, Duration timeToLive) {
        on(id, sandbox -> sandbox.renew(timeToLive));
    }

    @Override
    public SandboxCommandResult run(String id, SandboxCommand command) {
        return on(id, sandbox -> {
            long started = System.nanoTime();
            Execution execution = sandbox.commands().run(RunCommandRequest.builder().command(command.command())
                    .workingDirectory(command.workingDirectory()).uid(command.uid()).gid(command.uid())
                    .timeout(command.timeout()).build());
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            int exitCode = exitCode(execution);
            // OpenSandbox reports a command stopped at its timeout as exit −1 (MEM-270 probe).
            boolean timedOut = exitCode == -1 && elapsed.compareTo(command.timeout()) >= 0;
            return new SandboxCommandResult(exitCode, lines(execution.getLogs().getStdout()),
                    lines(execution.getLogs().getStderr()), timedOut, elapsed);
        });
    }

    @Override
    public void write(String id, String path, byte[] content) {
        onFile(id, sandbox -> {
            files(sandbox).write(List.of(WriteEntry.builder().path(path).data(content).mode(FILE_MODE).build()));
            return path;
        });
    }

    /** The SDK sends a stream as the request body as it reads it. */
    @Override
    public void write(String id, String path, InputStream content) {
        onFile(id, sandbox -> {
            files(sandbox).write(List.of(WriteEntry.builder().path(path).data(content).mode(FILE_MODE).build()));
            return path;
        });
    }

    @Override
    public byte[] read(String id, String path, long maxBytes) {
        try (InputStream in = open(id, path)) {
            byte[] content = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
            if (content.length > maxBytes) throw new IllegalArgumentException("The file is larger than " + maxBytes + " bytes");
            return content;
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** The stream keeps its sandbox connection open until it is closed: closing the connection first cuts the read. */
    @Override
    public InputStream open(String id, String path) {
        Sandbox sandbox = connect(id);
        try {
            return new FilterInputStream(files(sandbox).readStream(path)) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        sandbox.close();
                    }
                }
            };
        } catch (SandboxNotFoundException gone) {
            sandbox.close();
            throw new SandboxGoneException(id, gone);
        } catch (SandboxApiException failed) {
            sandbox.close();
            throw refusal(failed);
        } catch (RuntimeException failed) {
            sandbox.close();
            throw failed;
        }
    }

    @Override
    public List<SandboxEntry> list(String id, String path, int depth) {
        return onFile(id, sandbox -> files(sandbox).listDirectory(path, depth).stream()
                .map(entry -> new SandboxEntry(entry.getPath(), "directory".equalsIgnoreCase(entry.getType()),
                        entry.getSize()))
                .toList());
    }

    @Override
    public void kill(String id) {
        try {
            manager.killSandbox(id);
        } catch (SandboxNotFoundException gone) {
            // Already gone is what was asked for.
        }
    }

    @Override
    public Map<String, Instant> running(Map<String, String> labels) {
        Map<String, Instant> running = new HashMap<>();
        for (int page = 1; ; page++) {
            var infos = manager.listSandboxInfos(SandboxFilter.builder().states(SandboxState.RUNNING).metadata(labels)
                    .pageSize(PAGE).page(page).build());
            infos.getSandboxInfos().forEach(info -> running.put(info.getId(), info.getCreatedAt().toInstant()));
            if (!infos.getPagination().getHasNextPage()) return running;
        }
    }

    @Override
    public void close() {
        manager.close();
    }

    /** Runs {@code operation} on the sandbox, reading the SDK's "not found" as {@link SandboxGoneException}. */
    private <T> T on(String id, Function<Sandbox, T> operation) {
        try (Sandbox sandbox = connect(id)) {
            return operation.apply(sandbox);
        } catch (SandboxNotFoundException gone) {
            throw new SandboxGoneException(id, gone);
        }
    }

    private static Filesystem files(Sandbox sandbox) {
        return sandbox.filesWithIdentity(SandboxService.USER, SandboxService.USER);
    }

    /** {@link #on} for a file call, whose refusal by execd is the file's, not the sandbox's. */
    private <T> T onFile(String id, Function<Sandbox, T> operation) {
        try {
            return on(id, operation);
        } catch (SandboxApiException failed) {
            throw refusal(failed);
        }
    }

    /**
     * execd answered, so the sandbox is up: a missing path, a directory or a file the user may not touch comes back as
     * an execd error ({@code RUNTIME_ERROR}, HTTP 500, for "permission denied"). Only those three are refusals, told in
     * a fixed sentence: the raw message may come from the server or its proxy and is never handed to the model. Anything
     * else stays a runtime failure.
     */
    private static RuntimeException refusal(SandboxApiException failed) {
        String message = failed.getError().getMessage();
        if (message == null) return failed;
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("permission denied")) return new SandboxFileException("Permission denied.", failed);
        if (lower.contains("no such file") || lower.contains("not found")) return new SandboxFileException("No such file or directory.", failed);
        if (lower.contains("is a directory")) return new SandboxFileException("The path is a directory.", failed);
        return failed;
    }

    private Sandbox connect(String id) {
        try {
            return Sandbox.connector().sandboxId(id).connectionConfig(connection).skipHealthCheck(true).connect();
        } catch (SandboxNotFoundException gone) {
            throw new SandboxGoneException(id, gone);
        }
    }

    private static int exitCode(Execution execution) {
        if (execution.getExitCode() != null) return execution.getExitCode();
        if (execution.getError() != null) {
            try {
                return Integer.parseInt(execution.getError().getValue().strip());
            } catch (NumberFormatException unreadable) {
                return -1;
            }
        }
        return 0;
    }

    /**
     * The messages joined as written: execd 1.1.1 keeps each line's newline in its message (OpenSandbox #2033). The
     * final newline is dropped, as a shell's command substitution drops it.
     */
    private static String lines(List<OutputMessage> messages) {
        String text = messages.stream().map(OutputMessage::getText).collect(Collectors.joining());
        return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }
}
