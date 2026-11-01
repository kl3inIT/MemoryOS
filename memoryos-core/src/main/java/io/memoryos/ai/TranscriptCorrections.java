package io.memoryos.ai;

import java.util.List;

/**
 * What a model proposes for the stretches a speech provider was unsure of. Nothing here changes a transcript: each
 * proposal is an offer, carried with the model's own reasons and its own three scores so whoever decides can weigh it.
 */
public record TranscriptCorrections(List<Proposal> proposals) {
    public TranscriptCorrections {
        proposals = List.copyOf(proposals);
    }

    /**
     * One answer for one marked stretch.
     *
     * @param id            the stretch this answers, echoed back from the request
     * @param replace       false keeps the provider's words; the scores then say how sure the model is of that
     * @param text          the words to put in place of the stretch, inside the stretch and nothing beyond it
     * @param reason        why, in one short sentence, shown to the reader rather than trusted
     * @param confidence    how sure the model is that this is what was actually said
     * @param contextFit    how well it fits the sentences around it
     * @param meaningSafe   how sure the model is that it does not change what the speaker meant
     * @param matchedGlossary whether it matches a term the meeting or the Tenant supplied
     */
    public record Proposal(String id, boolean replace, String text, String reason, double confidence,
                           double contextFit, double meaningSafe, boolean matchedGlossary) {}
}
