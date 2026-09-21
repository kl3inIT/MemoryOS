package io.memoryos.chat.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** What an uploaded recording becomes: the same segments a live stream produces, from each provider's own answer. */
class BatchTranscriptionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void sonioxTokensBecomeOneSegmentPerSpeaker() {
        var segments = SonioxAsync.group(JSON.readTree("""
                {"tokens":[
                  {"text":"Chốt ","speaker":"1","start_ms":2000,"end_ms":2400,"confidence":0.9},
                  {"text":"ngân sách ","speaker":"1","start_ms":2400,"end_ms":2900,"confidence":0.8},
                  {"text":"quý 4.","speaker":"1","start_ms":2900,"end_ms":3400,"confidence":1.0},
                  {"text":"Em ","speaker":"2","start_ms":3600,"end_ms":3800,"confidence":0.7},
                  {"text":"gửi KPI.","speaker":"2","start_ms":3800,"end_ms":4400,"confidence":0.9}]}
                """));
        assertEquals(2, segments.size(), "a speaker change ends a segment");
        assertEquals("1", segments.getFirst().speaker());
        assertEquals("Chốt ngân sách quý 4.", segments.getFirst().text());
        assertEquals(2000, segments.getFirst().startMs());
        assertEquals(3400, segments.getFirst().endMs());
        assertEquals(0.9, segments.getFirst().confidence(), 0.001, "confidence is the mean of its tokens");
        assertEquals("2", segments.get(1).speaker());
        assertEquals("Em gửi KPI.", segments.get(1).text());
    }

    @Test
    void aPauseEndsASegmentEvenWhenTheSpeakerKeepsTalking() {
        var segments = SonioxAsync.group(JSON.readTree("""
                {"tokens":[
                  {"text":"Phần một.","speaker":"1","start_ms":0,"end_ms":900,"confidence":1.0},
                  {"text":"Phần hai.","speaker":"1","start_ms":5000,"end_ms":5800,"confidence":1.0}]}
                """));
        assertEquals(2, segments.size());
        assertEquals("Phần một.", segments.getFirst().text());
        assertEquals(5000, segments.get(1).startMs(), "the second segment starts where it was said");
    }

    @Test
    void controlTokensAndEmptyTextAreDropped() {
        var segments = SonioxAsync.group(JSON.readTree("""
                {"tokens":[
                  {"text":"<fin>","speaker":"1","start_ms":0,"end_ms":0},
                  {"text":"","speaker":"1","start_ms":0,"end_ms":0},
                  {"text":"Xin chào.","speaker":"1","start_ms":100,"end_ms":800,"confidence":0.95},
                  {"text":"<end>","speaker":"1","start_ms":800,"end_ms":800}]}
                """));
        assertEquals(1, segments.size());
        assertEquals("Xin chào.", segments.getFirst().text());
        assertTrue(segments.getFirst().confidence() > 0.9);
    }

    @Test
    void aTokenWithOnlyADurationStillCarriesItsEnd() {
        var segments = SonioxAsync.group(JSON.readTree("""
                {"tokens":[{"text":"Một câu.","speaker":"1","start_ms":1000,"duration_ms":700}]}
                """));
        assertEquals(1000, segments.getFirst().startMs());
        assertEquals(1700, segments.getFirst().endMs());
    }

    @Test
    void openAiVerboseSegmentsBecomeOneSpeakerWithTimes() {
        var segments = BatchTranscriptionService.segments(JSON.readTree("""
                {"text":"toàn bộ","segments":[
                  {"start":1.25,"end":3.5,"text":" Chốt ngân sách quý 4.","avg_logprob":-0.2},
                  {"start":3.9,"end":5.0,"text":"  ","avg_logprob":-0.1},
                  {"start":5.0,"end":7.25,"text":"Em gửi KPI.","avg_logprob":-2.5}]}
                """));
        assertEquals(2, segments.size(), "a blank segment is dropped");
        assertEquals("1", segments.getFirst().speaker(), "OpenAI does not separate speakers");
        assertEquals("Chốt ngân sách quý 4.", segments.getFirst().text());
        assertEquals(1250, segments.getFirst().startMs());
        assertEquals(3500, segments.getFirst().endMs());
        assertTrue(segments.getFirst().confidence() > segments.get(1).confidence(),
                "a lower log probability reads as lower confidence");
        assertTrue(segments.get(1).confidence() >= 0 && segments.getFirst().confidence() <= 1);
    }

    @Test
    void aServerWithoutVerboseSegmentsStillGivesOneUtterance() {
        var segments = BatchTranscriptionService.segments(JSON.readTree("""
                {"text":"  Cả buổi họp trong một câu.  "}
                """));
        assertEquals(1, segments.size());
        assertEquals("Cả buổi họp trong một câu.", segments.getFirst().text());
        assertEquals(0, segments.getFirst().startMs());
    }

    @Test
    void onlySonioxSeparatesSpeakersAndOnlyOpenAiCapsTheFile() {
        assertTrue(BatchTranscriptionService.diarizes(VoiceProvider.SONIOX));
        assertFalse(BatchTranscriptionService.diarizes(VoiceProvider.OPENAI));
        assertTrue(BatchTranscriptionService.supports(VoiceProvider.OPENAI_COMPATIBLE));
        assertFalse(BatchTranscriptionService.supports(VoiceProvider.AZURE),
                "Azure posts WAV under a fixed name; an uploaded container needs its own work");
        assertFalse(BatchTranscriptionService.supports(VoiceProvider.ELEVENLABS));
        assertEquals(25L * 1024 * 1024, BatchTranscriptionService.maxBytes(VoiceProvider.OPENAI));
        assertTrue(BatchTranscriptionService.maxBytes(VoiceProvider.SONIOX) > 25L * 1024 * 1024);
    }
}
