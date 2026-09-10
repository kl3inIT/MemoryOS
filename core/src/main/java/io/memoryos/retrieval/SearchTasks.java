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
import java.util.concurrent.CompletableFuture;
import java.util.HashMap;

import org.jspecify.annotations.Nullable;

import java.time.Duration;

/**
 * A bounded search phase. Completion order never changes ranking or citation order.
 */
public final class SearchTasks {
    private static final ThreadLocal<NativeWork> HELPER = new ThreadLocal<>();
    private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();

    private SearchTasks() {
    }

    /**
     * Wrap the native Asyncer's executor submission, including its usage recording after provider IO.
     * CompletableFuture.cancel(true) alone does not interrupt the executing native operation.
     */
    public static void executeNative(Executor executor, Runnable command) {
        var helper = HELPER.get();
        var scope = SCOPE.get();
        if (scope != null) scope.checkActive();
        executor.execute(() -> {
            if (scope == null) {
                command.run();
                return;
            }
            try (var _ = scope.enter()) {
                if (helper == null) command.run();
                else helper.run(command);
            } catch (CancellationException stopped) {
                // The owning caller was canceled; a queued native operation must never start IO.
            }
        });
    }

    public static void checkNativeActive() {
        var scope = SCOPE.get();
        if (scope != null) scope.checkActive();
        var helper = HELPER.get();
        if (helper != null && helper.closed) throw new CancellationException("Search helper stopped");
    }

    public static <T> List<T> run(List<? extends Callable<T>> tasks, Runnable checkActive) {
        if (tasks.isEmpty()) return List.of();
        if (tasks.size() > 30) throw new IllegalArgumentException("Too many search tasks");
        var scope = SCOPE.get();
        if (scope == null) {
            try (var owned = new Scope(Duration.ofSeconds(1)); var _ = owned.enter()) {
                return run(tasks, checkActive);
            }
        }
        checkActive.run();
        var context = ContextSnapshotFactory.builder().build().captureAll();
        var futures = new ArrayList<Future<Indexed<T>>>();
        var executor = Executors.newFixedThreadPool(Math.min(4, tasks.size()), Thread.ofVirtual().name("search-", 0).factory());
        try {
            var completed = new ExecutorCompletionService<Indexed<T>>(executor);
            try {
                for (int i = 0; i < tasks.size(); i++) {
                    int position = i;
                    futures.add(completed.submit(context.wrap(() -> {
                        try (var _ = scope.enter()) {
                            checkActive.run();
                            T result = tasks.get(position).call();
                            checkActive.run();
                            return new Indexed<>(position, result);
                        }
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
                throw new SearchUnavailableException(failure.getCause());
            } finally {
                futures.forEach(future -> future.cancel(true));
                executor.shutdownNow();
                // Scope tracks actual completion; Future.cancel alone is not proof that a task stopped.
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private record Indexed<T>(int position, T value) {
    }

    /**
     * One deadline covers all native typed-binding attempts, including their cancellation.
     */
    @SuppressWarnings("resource") // shutdownNow is intentional: ExecutorService.close waits without a bound.
    public static <T> T timed(Callable<T> task, Duration timeout, Runnable checkActive) {
        var scope = SCOPE.get();
        if (scope == null) {
            try (var owned = new Scope(Duration.ofSeconds(1)); var _ = owned.enter()) {
                return timed(task, timeout, checkActive);
            }
        }
        checkActive.run();
        var context = ContextSnapshotFactory.builder().build().captureAll();
        var work = new NativeWork();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var future = executor.submit(context.wrap(() -> {
                try (var _ = scope.enter()) {
                    return work.call(task);
                }
            }));
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
                throw new SearchUnavailableException(failure.getCause());
            } finally {
                work.cancel();
                future.cancel(true);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Owns live Search/native work through terminal publication and delayed client retirement.
     * Cancellation seals admission immediately; completion means actual bodies and native accounting ended.
     */
    public static final class Scope implements AutoCloseable {
        private final Duration grace;
        private final HashMap<Thread, Integer> running = new HashMap<>();
        private final CompletableFuture<Void> drained = new CompletableFuture<>();
        private volatile boolean closed;

        public Scope(Duration grace) {
            this.grace = grace;
        }

        public CompletableFuture<Void> drained() {
            return drained;
        }

        public void checkActive() {
            if (closed) throw new CancellationException("Search stopped");
        }

        public Entry enter() {
            synchronized (this) {
                checkActive();
                running.merge(Thread.currentThread(), 1, Integer::sum);
            }
            var previous = SCOPE.get();
            SCOPE.set(this);
            return new Entry(previous);
        }

        public final class Entry implements AutoCloseable {
            private final @Nullable Scope previous;

            private Entry(@Nullable Scope previous) {
                this.previous = previous;
            }

            @Override
            public void close() {
                if (previous == null) SCOPE.remove();
                else SCOPE.set(previous);
                boolean finished;
                synchronized (Scope.this) {
                    running.computeIfPresent(Thread.currentThread(), (_, count) -> count == 1 ? null : count - 1);
                    finished = closed && running.isEmpty();
                }
                if (finished) drained.complete(null);
            }
        }

        public void cancel() {
            boolean finished;
            synchronized (this) {
                closed = true;
                running.keySet().stream().filter(thread -> thread != Thread.currentThread()).forEach(Thread::interrupt);
                finished = running.isEmpty();
            }
            if (finished) drained.complete(null);
        }

        @Override
        public void close() {
            cancel();
            boolean interrupted = Thread.interrupted();
            long deadline = System.nanoTime() + grace.toNanos();
            try {
                while (!drained.isDone()) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) break;
                    try {
                        drained.get(left, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException stopping) {
                        interrupted = true;
                        cancel();
                    } catch (ExecutionException | TimeoutException incomplete) {
                        break;
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private static final class NativeWork {
        private final java.util.Set<Thread> running = new java.util.HashSet<>();
        private volatile boolean closed;

        private <T> T call(Callable<T> action) throws Exception {
            var previous = HELPER.get();
            HELPER.set(this);
            try {
                return action.call();
            } finally {
                if (previous == null) HELPER.remove();
                else HELPER.set(previous);
            }
        }

        private void run(Runnable command) {
            synchronized (this) {
                if (closed) return;
                running.add(Thread.currentThread());
            }
            var previous = HELPER.get();
            HELPER.set(this);
            try {
                command.run();
            } finally {
                if (previous == null) HELPER.remove();
                else HELPER.set(previous);
                synchronized (this) {
                    running.remove(Thread.currentThread());
                }
            }
        }

        private synchronized void cancel() {
            closed = true;
            running.forEach(Thread::interrupt);
        }

    }

    public static final class HelperTimeoutException extends RuntimeException {
        private HelperTimeoutException() {
            super("Search helper timed out");
        }
    }
}
