package io.memoryos.api.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.chat.ChatMessage.Status;
import io.memoryos.chat.streaming.ChatStreamProperties;
import io.memoryos.chat.streaming.StreamBufferWriter;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.NullMarked;
import org.reactivestreams.Subscription;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.scheduler.Schedulers;

@NullMarked
class ChatEventStreamTest {
    private final UUID assistant = UUID.randomUUID();
    private final StreamBufferWriter streams = new StreamBufferWriter(new ChatStreamProperties(
            4096, 16384, Duration.ofMinutes(1), 512, Duration.ofMillis(25), 2048,
            4, 8, 2048, 16, Duration.ofSeconds(15), Duration.ofMinutes(1)));

    @Test
    void replayKeepsSequenceAndTerminalAndReleasesReader() {
        streams.open(assistant);
        streams.append(assistant, "First 😀");
        streams.flush();
        streams.append(assistant, " second");
        streams.finish(assistant, Status.COMPLETED, null);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var scheduler = Schedulers.fromExecutor(executor);
            var events = ChatEventStream.encode(streams.subscribe(assistant, 1), assistant, scheduler,
                    Duration.ofSeconds(5)).collectList().block(Duration.ofSeconds(5));
            assertNotNull(events);
            assertEquals(2, events.size());
            assertEquals(assistant + ":2", events.getFirst().id());
            assertEquals("outcome", events.getLast().event());
            assertEquals(0, streams.readerCount());
            scheduler.dispose();
        }
    }

    @Test
    void disconnectReleasesWaitingReaderAndWriterContinuesForReconnect() throws Exception {
        streams.open(assistant);
        streams.append(assistant, "Partial");
        streams.flush();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var scheduler = Schedulers.fromExecutor(executor);
            var received = new CountDownLatch(1);
            var subscription = ChatEventStream.encode(streams.subscribe(assistant, 0), assistant, scheduler,
                    Duration.ofSeconds(5)).subscribe(_ -> {
                        assertTrue(Thread.currentThread().isVirtual());
                        received.countDown();
                    });
            assertTrue(received.await(5, TimeUnit.SECONDS));
            subscription.dispose();
            assertEquals(0, streams.readerCount());
            streams.append(assistant, " continues");
            streams.finish(assistant, Status.COMPLETED, null);
            var replay = ChatEventStream.encode(streams.subscribe(assistant, 1), assistant, scheduler,
                    Duration.ofSeconds(5)).collectList().block(Duration.ofSeconds(5));
            assertNotNull(replay);
            assertEquals(" continues", assertInstanceOf(ChatEventStream.TextDeltaEvent.class, replay.getFirst().data()).text());
            assertEquals("outcome", replay.getLast().event());
            assertEquals(0, streams.readerCount());
            scheduler.dispose();
        }
    }

    @Test
    void readerWithNoDemandIsBoundedAndReceivesResetWhenItFallsBehind() throws Exception {
        streams.open(assistant);
        streams.append(assistant, "First");
        streams.flush();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var scheduler = Schedulers.fromExecutor(executor);
            var first = new CountDownLatch(1);
            var done = new CountDownLatch(1);
            var frames = new CopyOnWriteArrayList<ServerSentEvent<Object>>();
            var subscriber = new BaseSubscriber<ServerSentEvent<Object>>() {
                @Override protected void hookOnSubscribe(Subscription subscription) { request(1); }
                @Override protected void hookOnNext(ServerSentEvent<Object> event) { frames.add(event); first.countDown(); }
                @Override protected void hookOnComplete() { done.countDown(); }
            };
            ChatEventStream.encode(streams.subscribe(assistant, 0), assistant, scheduler,
                    Duration.ofSeconds(5)).subscribe(subscriber);
            assertTrue(first.await(5, TimeUnit.SECONDS));
            streams.append(assistant, "x".repeat(16000));
            streams.finish(assistant, Status.COMPLETED, null);
            assertEquals(1, frames.size());
            subscriber.requestUnbounded();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals("reset", frames.getLast().event());
            assertEquals(0, streams.readerCount());
            scheduler.dispose();
        }
    }

    @Test
    void connectionTimeoutOnlyClosesReaderAndMissingBufferResetsWithoutEventId() {
        streams.open(assistant);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var scheduler = Schedulers.fromExecutor(executor);
            var frames = ChatEventStream.encode(streams.subscribe(assistant, 0), assistant, scheduler,
                    Duration.ofMillis(50)).collectList().block(Duration.ofSeconds(5));
            assertNotNull(frames);
            assertTrue(frames.isEmpty());
            assertEquals(0, streams.readerCount());
            streams.append(assistant, "Still running");
            streams.finish(assistant, Status.CANCELED, null);
            var missing = ChatEventStream.encode(streams.subscribe(UUID.randomUUID(), 0), assistant, scheduler,
                    Duration.ofSeconds(5)).collectList().block(Duration.ofSeconds(5));
            assertNotNull(missing);
            assertEquals(1, missing.size());
            assertEquals("reset", missing.getFirst().event());
            assertNull(missing.getFirst().id());
            scheduler.dispose();
        }
    }
}
