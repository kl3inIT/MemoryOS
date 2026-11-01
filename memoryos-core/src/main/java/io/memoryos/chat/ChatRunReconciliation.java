package io.memoryos.chat;

import io.memoryos.chat.session.persistence.JdbcChatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ends the turns a process left running when it died: a reply whose lease lapsed fails with
 * {@code CHAT_INTERRUPTED}, which frees its conversation for the next question. It reads nothing a process holds in
 * memory, so the Worker runs it once for every process that executes turns (MEM-246); renewing a live turn's lease
 * stays with the process that runs it.
 */
@Service
public class ChatRunReconciliation {
    private static final Logger LOG = LoggerFactory.getLogger(ChatRunReconciliation.class);

    private final JdbcChatRepository chats;

    public ChatRunReconciliation(JdbcChatRepository chats) { this.chats = chats; }

    /** Ends at most a hundred lapsed turns; returns how many. */
    @Transactional
    public int endLapsed() {
        int ended = chats.expireRuns();
        if (ended > 0) {
            LOG.atInfo().addKeyValue("event", "chat.lease.reconciled").addKeyValue("count", ended)
                    .log("Ended chat turns whose process stopped renewing them");
        }
        return ended;
    }
}
