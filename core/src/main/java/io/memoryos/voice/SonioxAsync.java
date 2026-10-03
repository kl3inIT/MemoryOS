package io.memoryos.voice;

import io.memoryos.shared.OutboundHttp;
import io.memoryos.shared.OutboundHttp.Limits;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Soniox async transcription of one recording: upload the file, create a transcription, poll until it completes, read
 * the transcript, and always delete both the transcription and the file (Anarlog {@code soniox/batch.rs}).
 *
 * <p>Dictation reads the transcript's text. A meeting recording reads its tokens instead, so the speakers Soniox
 * separated and the time each sentence was said survive into the transcript the owner reads.
 */
final class SonioxAsync {
    static final String DEFAULT_MODEL = "stt-async-v5";
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    /** A sentence ends at a speaker change or a pause; without one it would run for the whole recording. */
    private static final long SEGMENT_GAP_MS = 800;
    private static final int MAX_SEGMENT_CHARS = 400;
    /** The transcript of a five-hour recording names every token with its times; the other answers are a few bytes. */
    private static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;

    private SonioxAsync() {}

    /** Transcribes a WAV recording and returns its text; the language is an ISO-639-1 hint such as {@code vi}. */
    static String transcribe(String baseUrl, String key, String model, @Nullable String language, byte[] wav,
            Duration timeout) throws InterruptedException {
        return run(baseUrl, key, model, language, List.of(), false,
                AudioSource.part(AudioSource.of(wav), wav.length, "audio.wav"), "audio/wav", timeout,
                transcript -> transcript.path("text").asString("").strip());
    }

    /**
     * Transcribes one uploaded recording into the segments a meeting stores, separating speakers when asked. The file
     * is streamed from its source into the upload, so a 500 MB recording never sits in memory.
     */
    static List<LiveTranscription.Segment> segments(String baseUrl, String key, String model, @Nullable String language,
            List<String> terms, boolean diarize, BatchTranscriptionService.Recording recording, Duration timeout)
            throws InterruptedException {
        return run(baseUrl, key, model, language, terms, diarize,
                AudioSource.part(recording.audio(), recording.sizeBytes(), recording.filename()), recording.mediaType(),
                timeout, SonioxAsync::group);
    }

    private static <T> T run(String baseUrl, String key, String model, @Nullable String language, List<String> terms,
            boolean diarize, Resource audio, String mediaType, Duration timeout, Function<JsonNode, T> read)
            throws InterruptedException {
        // Every call of one transcription shares the connection's base URL and key, and the caller's deadline.
        var api = OutboundHttp.service(SonioxApi.class, OutboundHttp.builder(new Limits(timeout, MAX_RESPONSE_BYTES))
                .baseUrl(baseUrl).defaultHeaders(headers -> headers.setBearerAuth(key)).build());
        long deadline = System.nanoTime() + timeout.toNanos();
        String file = null;
        String transcription = null;
        try {
            var part = new HttpHeaders();
            part.setContentType(MediaType.parseMediaType(mediaType));
            file = id(api.upload(new HttpEntity<>(audio, part)));
            var body = new LinkedHashMap<String, Object>();
            body.put("model", asyncModel(model));
            body.put("file_id", file);
            if (diarize) body.put("enable_speaker_diarization", true);
            if (language != null) {
                body.put("language_hints", List.of(language));
                body.put("language_hints_strict", true);
            }
            if (!terms.isEmpty()) body.put("context", Map.of("terms", terms));
            transcription = id(api.transcribe(body));
            while (true) {
                var answer = api.status(transcription);
                String status = answer == null ? null : answer.status();
                if ("completed".equals(status)) break;
                if ("error".equals(status) || "failed".equals(status) || System.nanoTime() > deadline)
                    throw VoiceException.providerUnavailable();
                Thread.sleep(POLL_INTERVAL);
            }
            JsonNode transcript = api.transcript(transcription);
            if (transcript == null) throw VoiceException.providerUnavailable();
            return read.apply(transcript);
        } catch (RestClientException failed) {
            // A failed answer carries its status only, so account detail cannot reach a response or a log.
            throw VoiceException.providerUnavailable();
        } finally {
            if (transcription != null) cleanUp(api::deleteTranscription, transcription);
            if (file != null) cleanUp(api::deleteFile, file);
        }
    }

    /** An answer without an id is not something to poll or to delete. */
    private static String id(SonioxApi.@Nullable Created created) {
        String id = created == null ? null : created.id();
        if (id == null || id.isEmpty()) throw VoiceException.providerUnavailable();
        return id;
    }

    /**
     * Groups the transcript's tokens the way the live adapter groups them: one segment per speaker, broken where the
     * speaker changes, where the recording pauses, or where a sentence has run long enough to read on its own.
     */
    static List<LiveTranscription.Segment> group(JsonNode transcript) {
        var segments = new ArrayList<LiveTranscription.Segment>();
        String speaker = null;
        var text = new SpokenText();
        long startMs = 0;
        long endMs = 0;
        for (JsonNode token : transcript.path("tokens")) {
            String word = token.path("text").asString("");
            if (word.isEmpty() || "<fin>".equals(word) || "<end>".equals(word)) continue;
            // Soniox marks laughter, applause and the like as tokens of their own; they are not what was said.
            if (token.path("is_audio_event").asBoolean(false)) continue;
            String at = token.path("speaker").asString("1");
            if (at.isEmpty()) at = "1";
            long start = token.path("start_ms").asLong(0);
            long end = token.path("end_ms").asLong(start + token.path("duration_ms").asLong(0));
            boolean broken = speaker != null
                    && (!speaker.equals(at) || start - endMs > SEGMENT_GAP_MS || text.length() > MAX_SEGMENT_CHARS);
            if (broken) {
                add(segments, speaker, startMs, endMs, text);
                text.reset();
                speaker = null;
            }
            if (speaker == null) {
                speaker = at;
                startMs = start;
            }
            text.append(word, token.path("confidence").asDouble(1.0));
            endMs = Math.max(endMs, end);
        }
        if (speaker != null) add(segments, speaker, startMs, endMs, text);
        return List.copyOf(segments);
    }

    private static void add(List<LiveTranscription.Segment> segments, String speaker, long startMs, long endMs,
            SpokenText text) {
        if (text.isEmpty()) return;
        segments.add(new LiveTranscription.Segment(speaker, startMs, Math.max(endMs, startMs), text.said(),
                text.confidence(), text.spans()));
    }

    /** The connection holds the realtime model; its async sibling has the same version ({@code stt-rt-v5} → {@code stt-async-v5}). */
    static String asyncModel(String model) {
        if (model.startsWith("stt-async-")) return model;
        if (model.startsWith("stt-rt-")) return "stt-async-" + model.substring("stt-rt-".length());
        return DEFAULT_MODEL;
    }

    /** Cleanup is best effort: a failed delete must not replace the transcription's own outcome. */
    private static void cleanUp(Consumer<String> delete, String id) {
        try {
            delete.accept(id);
        } catch (RuntimeException ignored) {
            // Soniox removes abandoned files on its own schedule.
        }
    }
}
