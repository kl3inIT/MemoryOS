package io.memoryos.chat.grounding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.chat.ChatGuardrails;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChatGuardrailCheckTest {
    @Test
    void aBlockIsRecordedInItsOwnTransactionBecauseAChatTurnHasNone() {
        // Staging, 2026-09-27: record() requires the caller's transaction, so every blocked turn failed instead of
        // answering with the Tenant's message.
        var audit = mock(AuditTrail.class);
        var check = new ChatGuardrailCheck(mock(GroundingClassifier.class), audit);

        check.recordBlock(new TenantId(UUID.randomUUID()), new ActorId(UUID.randomUUID()), UUID.randomUUID(),
                new ChatGuardrailCheck.Result(ChatGuardrailCheck.Kind.BLOCKED, "Không trả lời.", ChatGuardrails.Topic.LEADERS, null), null);

        var event = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).recordSeparately(event.capture());
        verify(audit, never()).record(any());
        assertEquals(AuditAction.CHAT_GUARDRAIL_BLOCK, event.getValue().action());
    }
}
