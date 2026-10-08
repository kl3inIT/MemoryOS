package io.memoryos.chat.prompts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The rule the MEM-141 benchmark asked for. Its cross-department questions give an actor evidence for half
 * the question and none for the other half; the replies answered their half and then completed the rest from
 * general knowledge, once with a figure the documents contradict (145% against the document's 152%).
 * Three of the four cases are statutory defaults as well as charter clauses, which is why the model did not
 * treat them as organization-specific, so the rule has to name that case outright.
 */
class ChatGroundingPromptTest {
    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    @Test
    void partialEvidenceIsAnsweredInPartAndTheGapIsNamed() {
        assertTrue(ChatPrompts.SEARCH_GUIDANCE.contains("answers only part of the request"));
        assertTrue(ChatPrompts.SEARCH_GUIDANCE.contains("say plainly which part"));
        assertTrue(ChatPrompts.SEARCH_GUIDANCE.contains("Do not complete the uncovered part from general"));
    }

    @Test
    void aLawGivingTheSameAnswerIsNotAnExcuseToSupplyIt() {
        // Without this clause the earlier rule was not enough: the model read a charter threshold that is also
        // the statutory default as existing knowledge and answered it for an actor who cannot read the charter.
        assertTrue(ChatPrompts.SEARCH_GUIDANCE.contains("a law, a standard or common practice would give the same answer"));
        assertTrue(ChatPrompts.SEARCH_GUIDANCE.contains("organization-specific fact"));
    }

    @Test
    void aCitationMustCarryTheStatementItIsAttachedTo() {
        // The reply that invented 145% did cite a document; citing at all was never the problem.
        assertTrue(ChatPrompts.SEARCH_GUIDANCE.contains("cite no document whose facts do not"));
        assertTrue(ChatPrompts.SEARCH_GUIDANCE.contains("nearest thing"));
        assertTrue(ChatPrompts.SEARCH_GUIDANCE.contains("worse than no citation"));
    }

    @Test
    void theRuleTravelsWithSearchAndStaysOutOfATurnWithoutIt() {
        String withSearch = ChatPrompts.resolve(ChatPrompts.DEFAULT_SYSTEM, true, NOW);
        String withoutSearch = ChatPrompts.resolve(ChatPrompts.DEFAULT_SYSTEM, false, NOW);

        assertTrue(withSearch.contains("answers only part of the request"));
        // A turn with no knowledge base has no evidence to be partial about, and the rule would only make
        // the assistant hedge about documents that do not exist.
        assertFalse(withoutSearch.contains("answers only part of the request"));
    }
}
