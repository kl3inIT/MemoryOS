package io.memoryos.voice;

/**
 * One cumulative transcript update. Finality means the text is committed; an utterance boundary specifically means
 * provider VAD ended the spoken turn and may drive Auto-Send.
 */
public record Transcript(String text, boolean isFinal, boolean utteranceEnd) {
    public Transcript(String text, boolean isFinal) {
        this(text, isFinal, false);
    }
}
