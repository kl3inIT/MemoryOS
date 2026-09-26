package io.memoryos.chat.grounding;

import io.memoryos.ai.ModelAccounting;
import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.chat.ChatGuardrails;
import io.memoryos.chat.ChatSettingsService;
import io.memoryos.chat.execution.ChatTurnSetup;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Check 1 of MEM-195 around one turn: blocked phrases in code first, then the classifier, and the audit record of a
 * blocked question. Runs in the turn's background execution, never under the session lock.
 */
public final class ChatGuardrailCheck {
    private final GroundingClassifier classifier;
    private final AuditTrail audit;

    public ChatGuardrailCheck(GroundingClassifier classifier, AuditTrail audit) {
        this.classifier = classifier;
        this.audit = audit;
    }

    public enum Kind { CONVERSATIONAL, QUESTION, BLOCKED }

    /** How the question was classified; a blocked one carries what the person is told. */
    public record Result(Kind kind, @Nullable String message, ChatGuardrails.@Nullable Topic topic, @Nullable String phrase) {
        static final Result QUESTION = new Result(Kind.QUESTION, null, null, null);
        static final Result CONVERSATIONAL = new Result(Kind.CONVERSATIONAL, null, null, null);
    }

    /** Whether this turn needs the check at all: it is grounded, or the Tenant turned a guardrail on. */
    public static boolean applies(ChatTurnSetup setup, ChatSettingsService.TurnPolicy policy) {
        return setup.options().grounded() || policy.guardrails().active();
    }

    /** @param question the text the person wrote in this turn, without attachments */
    public Result check(ChatTurnSetup setup, String question, ChatSettingsService.TurnPolicy policy, Consumer<ModelAccounting> accounting) {
        var guardrails = policy.guardrails();
        String phrase = guardrails.blockedPhraseIn(question);
        if (phrase != null) return new Result(Kind.BLOCKED, guardrails.blockedPhraseMessage(), null, phrase);
        var topics = guardrails.enabledTopics();
        var verdict = classifier.classify(setup.binding(), question, setup.options().grounded(), topics, accounting);
        return switch (verdict.kind()) {
            case CONVERSATIONAL -> Result.CONVERSATIONAL;
            case QUESTION -> Result.QUESTION;
            case BLOCKED_TOPIC -> {
                var setting = guardrails.topic(verdict.topic());
                yield new Result(Kind.BLOCKED, setting == null ? verdict.topic().defaultMessage() : setting.message(),
                        verdict.topic(), null);
            }
        };
    }

    /** The audit line of a blocked question; the question and the phrase stay out of the audit stream. */
    public void recordBlock(ChatTurnSetup setup, Result result, @Nullable String agent) {
        audit.record(AuditRecord.of(AuditAction.CHAT_GUARDRAIL_BLOCK, setup.tenant())
                .actor(setup.actor()).resource("CHAT_SESSION", setup.sessionId().toString(), "Chat")
                .detail("rule", result.topic() != null ? "topic" : "phrase")
                .detail("topic", result.topic() == null ? null : result.topic().name())
                .detail("agent", agent).detail("session", setup.sessionId().toString()).build());
    }
}
