package io.memoryos.chat;

import io.memoryos.retrieval.SearchFilters;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/** Per-turn bounded recorder. Excess steps or reasoning are dropped from history, never failing the turn. */
final class ChatActivityRecorder {
    // Compact JSON budget below the 131072-byte column check, leaving room for jsonb text formatting.
    static final int BYTE_BUDGET = 96_000;
    private static final int QUERY_CHARACTERS = 500;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final LinkedHashMap<String, Step> steps = new LinkedHashMap<>();
    private final List<Segment> reasoning = new ArrayList<>();
    private int position;
    private int reasoningCharacters;
    private boolean truncated;
    private @Nullable Segment open;

    private static final class Step {
        final int position;
        final ChatToolEvent.Call call;
        final Instant startedAt = Instant.now();
        final long startedNanos = System.nanoTime();
        final int textOffset;
        ChatActivity.StepStatus status = ChatActivity.StepStatus.RUNNING;
        @Nullable Long durationMs;
        List<String> queries = List.of();
        @Nullable SearchFilters filters;
        List<ChatToolEvent.ReadingDocument> documents = List.of();
        final LinkedHashSet<Integer> citations = new LinkedHashSet<>();

        Step(int position, ChatToolEvent.Call call, int textOffset) {
            this.position = position;
            this.call = call;
            this.textOffset = textOffset;
        }

        long elapsed() {
            return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
        }
    }

    private record Segment(int position, int textOffset, StringBuilder text) {}

    synchronized void accept(ChatActivityEvent event, int textOffset) {
        switch (event) {
            case ChatToolEvent tool -> tool(tool, textOffset);
            case ChatReasoningDelta delta -> reasoning(delta.text(), textOffset);
        }
    }

    private void tool(ChatToolEvent event, int textOffset) {
        var step = steps.get(event.toolCallId());
        if (event.stage() == ChatToolEvent.Stage.SOURCE) {
            if (step != null && step.citations.size() < 24) step.citations.add(event.source().citationId());
            return;
        }
        if (step == null) {
            if (steps.size() >= ChatActivity.MAX_ITEMS) return;
            step = new Step(position++, event.call(), textOffset);
            steps.put(event.toolCallId(), step);
            open = null;
        }
        switch (event.stage()) {
            case SEARCHING -> {
                step.queries = event.search().queries().stream().map(ChatActivityRecorder::bounded).distinct().toList();
                step.filters = event.search().filters();
            }
            case EXPANDING -> step.documents = event.documents();
            case COMPLETED, FAILED -> {
                if (step.status == ChatActivity.StepStatus.RUNNING) {
                    step.status = event.stage() == ChatToolEvent.Stage.FAILED ? ChatActivity.StepStatus.FAILED : ChatActivity.StepStatus.COMPLETED;
                    step.durationMs = event.durationMs() != null ? event.durationMs() : step.elapsed();
                }
            }
            default -> { }
        }
    }

    private void reasoning(String text, int textOffset) {
        if (truncated) return;
        if (open == null || open.textOffset() != textOffset) {
            if (reasoning.size() >= ChatActivity.MAX_ITEMS) { truncated = true; return; }
            open = new Segment(position++, textOffset, new StringBuilder());
            reasoning.add(open);
        }
        int remaining = ChatActivity.MAX_REASONING - reasoningCharacters;
        if (text.length() > remaining) {
            int end = safeEnd(text, remaining);
            open.text().append(text, 0, end).append(ChatActivity.TRUNCATED);
            reasoningCharacters += end;
            truncated = true;
            return;
        }
        open.text().append(text);
        reasoningCharacters += text.length();
    }

    /** Running steps at the terminal outcome were interrupted; they are recorded as failed. */
    synchronized ChatActivity seal() {
        var sealed = new ArrayList<Step>(steps.values());
        for (var step : sealed) {
            if (step.status == ChatActivity.StepStatus.RUNNING) {
                step.status = ChatActivity.StepStatus.FAILED;
                step.durationMs = step.elapsed();
            }
        }
        var activity = build(sealed, true, reasoning.stream().filter(s -> !s.text().isEmpty()).toList());
        // Detail is removed from the latest steps first, then reasoning, until the bounded history fits.
        for (int keep = sealed.size(); size(activity) > BYTE_BUDGET && keep > 0; keep--) {
            var trimmed = sealed.get(keep - 1);
            trimmed.queries = List.of();
            trimmed.filters = null;
            trimmed.documents = List.of();
            trimmed.citations.clear();
            activity = build(sealed, true, reasoning.stream().filter(s -> !s.text().isEmpty()).toList());
        }
        if (size(activity) > BYTE_BUDGET) activity = build(sealed, false, List.of());
        return activity;
    }

    private static ChatActivity build(List<Step> steps, boolean details, List<Segment> reasoning) {
        return new ChatActivity(steps.stream().map(step -> new ChatActivity.ActivityStep(step.position, step.call.id(), step.call.name(),
                        step.status, step.startedAt, step.durationMs, step.textOffset, details ? step.queries : List.of(),
                        details ? step.filters : null, details ? step.documents : List.of(), details ? List.copyOf(step.citations) : List.of())).toList(),
                reasoning.stream().map(segment -> new ChatActivity.ReasoningSegment(segment.position(), segment.textOffset(), segment.text().toString())).toList());
    }

    static int size(ChatActivity activity) {
        return JSON.writeValueAsString(activity).getBytes(StandardCharsets.UTF_8).length;
    }

    private static String bounded(String query) {
        String value = query.strip();
        return value.length() <= QUERY_CHARACTERS ? value : value.substring(0, safeEnd(value, QUERY_CHARACTERS));
    }

    private static int safeEnd(String text, int end) {
        return end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1)) ? end - 1 : end;
    }
}
