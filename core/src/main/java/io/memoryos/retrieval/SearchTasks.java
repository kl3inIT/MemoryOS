package io.memoryos.retrieval;

import io.micrometer.context.ContextSnapshotFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.time.Duration;

/** A bounded search phase. Completion order never changes ranking or citation order. */
public final class SearchTasks {
    private static final ThreadLocal<NativeWork> HELPER = new ThreadLocal<>();
    private SearchTasks() {}

    /** Wrap the native Asyncer's executor submission, including its usage recording after provider IO.
     * CompletableFuture.cancel(true) alone does not interrupt the executing native operation. */
    public static void executeNative(Executor executor, Runnable command) {
        var helper = HELPER.get();
        executor.execute(helper == null ? command : () -> helper.run(command));
    }

    public static <T> List<T> run(List<? extends Callable<T>> tasks, Runnable checkActive) {
        if (tasks.isEmpty()) return List.of();
        if (tasks.size() > 30) throw new IllegalArgumentException("Too many search tasks");
        checkActive.run();
        var context = ContextSnapshotFactory.builder().build().captureAll();
        var futures = new ArrayList<Future<Indexed<T>>>();
        try (var executor = Executors.newFixedThreadPool(Math.min(4, tasks.size()),
                Thread.ofVirtual().name("search-", 0).factory())) {
            var completed = new ExecutorCompletionService<Indexed<T>>(executor);
            try {
                for (int i = 0; i < tasks.size(); i++) {
                    int position = i;
                    futures.add(completed.submit(context.wrap(() -> {
                        checkActive.run();
                        T result = tasks.get(position).call();
                        checkActive.run();
                        return new Indexed<>(position, result);
                    })));
                }
                var results = new ArrayList<T>(Collections.nCopies(tasks.size(), null));
                for (int i = 0; i < tasks.size(); i++) {
                    var result = completed.take().get();
                    results.set(result.position(), result.value());
                }
                checkActive.run();
                return List.copyOf(results);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Search interrupted");
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                if (failure.getCause() instanceof Error error) throw error;
                throw new SearchUnavailableException();
            } finally {
                futures.forEach(future -> future.cancel(true));
                executor.shutdownNow();
                // Executor.close joins running tasks before the parent emits a terminal event or accounts usage.
            }
        }
    }

    private record Indexed<T>(int position, T value) {}

    /** One deadline covers all native typed-binding attempts, including their cancellation. */
    public static <T> T timed(Callable<T> task, Duration timeout, Runnable checkActive) {
        checkActive.run();
        var context = ContextSnapshotFactory.builder().build().captureAll();
        try (var work = new NativeWork(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(context.wrap(() -> work.call(task)));
            try {
                return future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Search helper interrupted");
            } catch (TimeoutException timeoutFailure) {
                throw new HelperTimeoutException();
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                if (failure.getCause() instanceof Error error) throw error;
                throw new SearchUnavailableException();
            } finally {
                work.cancel();
                future.cancel(true);
                executor.shutdownNow();
            }
        }
    }

    private static final class NativeWork implements AutoCloseable {
        private final java.util.Set<Thread> running = new java.util.HashSet<>();
        private boolean closed;

        private <T> T call(Callable<T> action) throws Exception {
            var previous = HELPER.get();
            HELPER.set(this);
            try { return action.call(); }
            finally { if (previous == null) HELPER.remove(); else HELPER.set(previous); }
        }

        private void run(Runnable command) {
            synchronized (this) {
                if (closed) return;
                running.add(Thread.currentThread());
            }
            var previous = HELPER.get();
            HELPER.set(this);
            try { command.run(); }
            finally {
                if (previous == null) HELPER.remove(); else HELPER.set(previous);
                synchronized (this) { running.remove(Thread.currentThread()); notifyAll(); }
            }
        }

        private synchronized void cancel() {
            closed = true;
            running.forEach(Thread::interrupt);
        }

        @Override public void close() {
            boolean interrupted = Thread.interrupted();
            synchronized (this) {
                cancel();
                while (!running.isEmpty()) {
                    try { wait(); }
                    catch (InterruptedException stopping) { interrupted = true; cancel(); }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public static final class HelperTimeoutException extends RuntimeException {
        private HelperTimeoutException() { super("Search helper timed out"); }
    }
}
