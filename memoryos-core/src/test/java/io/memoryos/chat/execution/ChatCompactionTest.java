package io.memoryos.chat.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.embabel.chat.Message;
import io.memoryos.ai.ModelRequestPolicy;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.session.ChatTurnPersistence.TurnContext;
import io.memoryos.shared.Tokenizers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatCompactionTest {
    private static final ModelRequestPolicy POLICY = ModelRequestPolicy.hosted(Tokenizers.o200k(), p -> p);
    /** About a hundred tokens a message. */
    private static final String FILLER = " lorem".repeat(100);

    @Test
    void aConversationWithinThreeQuartersOfTheBudgetIsNotCompacted() {
        assertTrue(ChatCompaction.plan(conversation(3), null, POLICY, 2000).isEmpty());
    }

    @Test
    void pastTheThresholdTheOlderTurnsAreSummarisedAndTheTailStartsAtAQuestion() {
        var branch = conversation(10);
        var plan = ChatCompaction.plan(branch, null, POLICY, 2000).orElseThrow();

        assertEquals(ChatMessage.Role.USER, plan.recent().getFirst().role(), "the tail is cut just before a question");
        assertEquals(branch.getFirst().id(), plan.recent().getLast().id(), "the answer just saved stays verbatim");
        assertEquals(branch.get(1).id(), plan.parent(), "the summary is written after the newest question");
        assertEquals(plan.older().getLast().id(), plan.cutoff());
        assertEquals("Question 0" + FILLER, plan.older().getFirst().text(), "everything before the tail is summarised");
        assertEquals(branch.size(), plan.older().size() + plan.recent().size());
        long tail = plan.recent().stream().mapToLong(line -> POLICY.tokens().estimate(line.text())).sum();
        assertTrue(tail <= ChatCompaction.TAIL * 2000, "the tail keeps to its quarter: " + tail);
    }

    @Test
    void theLastQuestionAndItsAnswerStayWholeEvenPastTheTailsShare() {
        var branch = new ArrayList<>(conversation(6));
        branch.set(0, message(ChatMessage.Role.ASSISTANT, "Answer 5" + FILLER.repeat(8)));
        var plan = ChatCompaction.plan(branch, null, POLICY, 2000).orElseThrow();

        assertEquals(2, plan.recent().size());
        assertEquals(branch.get(1).id(), plan.recent().getFirst().id());
    }

    @Test
    void onceASummaryCoversTheOlderTurnsTheNextTurnDoesNotCompactAgain() {
        var branch = conversation(10);
        var plan = ChatCompaction.plan(branch, null, POLICY, 2000).orElseThrow();
        var summary = new TurnContext.Summary(plan.cutoff(), "## Goal" + FILLER);

        var next = new ArrayList<ChatMessage>();
        next.add(message(ChatMessage.Role.ASSISTANT, "Answer 10" + FILLER));
        next.add(message(ChatMessage.Role.USER, "Question 10" + FILLER));
        next.addAll(branch);
        assertTrue(ChatCompaction.plan(next, summary, POLICY, 2000).isEmpty(),
                "what the next turn replays is measured, not the whole branch");
    }

    @Test
    void aLaterCompactionRollsThePreviousSummaryForward() {
        var branch = conversation(20);
        var summary = new TurnContext.Summary(branch.get(30).id(), "## Goal\nEarlier summary text");
        var plan = ChatCompaction.plan(branch, summary, POLICY, 2000).orElseThrow();

        assertEquals("## Goal\nEarlier summary text", plan.previous());
        assertEquals("Question 5" + FILLER, plan.older().getFirst().text(), "only messages after the cutoff are summarised");
        var request = ChatCompaction.request(plan, POLICY, 100_000).orElseThrow();
        assertTrue(request.getFirst().getContent().endsWith(ChatCompaction.PREVIOUS + "## Goal\nEarlier summary text"));
        assertTrue(request.getLast().getContent().startsWith(ChatCompaction.REMINDER));
    }

    @Test
    void theReminderNamesTheLastQuestionToSummariseAndNotTheKeptTail() {
        var plan = ChatCompaction.plan(conversation(10), null, POLICY, 2000).orElseThrow();
        String last = plan.older().reversed().stream().filter(line -> line.role() == ChatMessage.Role.USER)
                .findFirst().orElseThrow().text();

        String reminder = ChatCompaction.request(plan, POLICY, 100_000).orElseThrow().getLast().getContent();

        assertEquals(ChatCompaction.REMINDER + " The last question to summarize is: \""
                + last.substring(0, ChatCompaction.LAST_QUESTION) + "…\".", reminder);
        assertFalse(plan.recent().stream().map(ChatCompaction.Line::text).anyMatch(text ->
                reminder.contains(text.substring(0, 12))), "no question of the kept tail is named");
        var shortQuestion = new ChatCompaction.Line(UUID.randomUUID(), ChatMessage.Role.USER, " Mã hợp đồng là gì? ");
        var answer = new ChatCompaction.Line(UUID.randomUUID(), ChatMessage.Role.ASSISTANT, "HĐ-ZX-2093");
        assertEquals(ChatCompaction.REMINDER + " The last question to summarize is: \"Mã hợp đồng là gì?\".",
                ChatCompaction.reminder(List.of(shortQuestion, answer)));
    }

    @Test
    void aQuestionTheGuardrailsHidStaysHiddenFromTheSummary() {
        var branch = new ArrayList<>(conversation(10));
        var blocked = message(ChatMessage.Role.USER, "Vợ của lãnh tụ là ai?" + FILLER);
        var refusal = ChatMessage.builder(UUID.randomUUID(), UUID.randomUUID(), ChatMessage.Role.ASSISTANT,
                        ChatMessage.Status.COMPLETED, Instant.now())
                .parentMessageId(blocked.id()).content("Trợ lý không trả lời câu hỏi này." + FILLER)
                .refusalReason(ChatMessage.BLOCKED_TOPIC).finishedAt(Instant.now()).build();
        branch.add(refusal);
        branch.add(blocked);
        var plan = ChatCompaction.plan(branch, null, POLICY, 2000).orElseThrow();

        assertEquals(ChatTurnSetup.HIDDEN_QUESTION, plan.older().getFirst().text());
        var request = ChatCompaction.request(plan, POLICY, 100_000).orElseThrow();
        assertFalse(request.stream().map(Message::getContent).anyMatch(text -> text.contains("lãnh tụ")));
    }

    @Test
    void aRequestTooLargeForTheSummaryModelDropsTheRecentContextFirstThenTheOldestTurns() {
        var plan = ChatCompaction.plan(conversation(10), null, POLICY, 2000).orElseThrow();
        var whole = ChatCompaction.request(plan, POLICY, 100_000).orElseThrow();
        var lessContext = ChatCompaction.request(plan, POLICY, ChatCompaction.count(POLICY, whole) - 1).orElseThrow();

        assertEquals(whole.size() - 1, lessContext.size(), "the oldest recent message goes first");
        assertEquals(whole.get(1).getContent(), lessContext.get(1).getContent(), "the turns to summarise are all kept");
        var tight = ChatCompaction.request(plan, POLICY, 900).orElseThrow();
        assertEquals(ChatCompaction.CUTOFF, tight.get(tight.size() - 2).getContent(), "no recent context is left");
        assertEquals(plan.older().getLast().text(), tight.get(tight.size() - 3).getContent(),
                "the newest turn to summarise stays");
        assertTrue(ChatCompaction.request(plan, POLICY, 50).isEmpty());
    }

    @Test
    void theSummaryIsBoundedByAnEighthOfTheHistoryBudget() {
        assertEquals(1024, ChatCompactions.summaryTokens(4000));
        assertEquals(4000, ChatCompactions.summaryTokens(32_000));
        assertEquals(8192, ChatCompactions.summaryTokens(240_000));
    }

    /** {@code turns} exchanges, newest first, the newest answer first, as the branch after an answer reads. */
    private static List<ChatMessage> conversation(int turns) {
        var newestFirst = new ArrayList<ChatMessage>();
        for (int i = turns - 1; i >= 0; i--) {
            newestFirst.add(message(ChatMessage.Role.ASSISTANT, "Answer " + i + FILLER));
            newestFirst.add(message(ChatMessage.Role.USER, "Question " + i + FILLER));
        }
        return newestFirst;
    }

    private static ChatMessage message(ChatMessage.Role role, String content) {
        return ChatMessage.builder(UUID.randomUUID(), UUID.randomUUID(), role,
                role == ChatMessage.Role.USER ? ChatMessage.Status.COMPLETED : ChatMessage.Status.COMPLETED, Instant.now())
                .content(content).finishedAt(Instant.now()).build();
    }
}
