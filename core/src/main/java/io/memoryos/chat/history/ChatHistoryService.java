package io.memoryos.chat.history;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatSettingsService;
import io.memoryos.chat.history.persistence.JdbcChatHistoryRepository;
import io.memoryos.chat.history.persistence.JdbcChatHistoryRepository.Cursor;
import io.memoryos.chat.history.persistence.JdbcChatHistoryRepository.Entry;
import io.memoryos.chat.history.persistence.JdbcChatHistoryRepository.Message;
import io.memoryos.iam.audit.AuditAction;
import io.memoryos.iam.audit.AuditOutcome;
import io.memoryos.iam.audit.AuditRecord;
import io.memoryos.iam.audit.AuditTrail;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Tenant's conversations, read by an administrator who holds {@link IamCapability#CHAT_HISTORY_READ} (MEM-125).
 *
 * <p>This is the one surface that shows a person's own words to somebody else, so three rules hold everywhere in it:
 * the Tenant decides how much is visible, a temporary conversation has nothing to show, and every transcript read is
 * recorded in the audit stream. Onyx records nothing when an administrator reads a colleague's questions.
 */
@Service
public class ChatHistoryService {
    /** A page the screen can render and a cursor can walk; Onyx pages 20 at a time by offset. */
    public static final int MAX_PAGE = 100;
    /** As the audit export: a bound the screen can promise, not a queue to maintain for a limit no one has hit. */
    public static final int MAX_EXPORT = 50_000;
    /** A conversation longer than this is a runaway, not a transcript anyone reads to the end. */
    private static final int MAX_TRANSCRIPT = 500;

    private final IamAuthorization authorization;
    private final ChatSettingsService settings;
    private final JdbcChatHistoryRepository history;
    private final AuditTrail audit;

    public ChatHistoryService(IamAuthorization authorization, ChatSettingsService settings,
                              JdbcChatHistoryRepository history, AuditTrail audit) {
        this.authorization = authorization;
        this.settings = settings;
        this.history = history;
        this.audit = audit;
    }

    /** One conversation as the list shows it. The asker is absent when the Tenant hides them. */
    public record Conversation(UUID id, @Nullable UUID actorId, @Nullable String person, @Nullable String email,
                               String title, @Nullable String question, @Nullable String answer,
                               @Nullable String modelName, long messages,
                               JdbcChatHistoryRepository.Feedback feedback, boolean deleted, Instant updatedAt) {}

    public record Page(List<Conversation> items, @Nullable String nextCursor,
                       JdbcChatHistoryRepository.Totals totals) {}

    public record Transcript(Conversation conversation, List<Message> messages) {}

    /** A page of the Tenant's conversations, newest first. */
    @Transactional(readOnly = true)
    public Page page(ActorId reader, JdbcChatHistoryRepository.Query query, @Nullable String cursor, int size) {
        if (size < 1 || size > MAX_PAGE) throw ChatException.invalid("Page size must be between 1 and 100.");
        var access = readable(reader, query);
        var entries = history.page(access.tenant().value(), query, decode(cursor), size);
        var visibility = access.visibility();
        return new Page(entries.stream().map(entry -> conversation(entry, visibility)).toList(),
                entries.size() < size ? null : encode(entries.getLast()),
                history.totals(access.tenant().value(), query));
    }

    /**
     * One conversation's transcript, and the record that somebody read it. The record is written outside this read,
     * so a transcript that fails halfway still leaves evidence that it was opened.
     */
    @Transactional(readOnly = true)
    public Transcript transcript(ActorId reader, UUID sessionId) {
        var access = readable(reader, new JdbcChatHistoryRepository.Query(null, null, null, null, null));
        var entry = history.conversation(access.tenant().value(), sessionId).orElseThrow(ChatException::unavailable);
        var messages = history.transcript(sessionId, entry.rootMessageId(), MAX_TRANSCRIPT);
        recordRead(access, reader, entry, messages.size());
        return new Transcript(conversation(entry, access.visibility()), messages);
    }

    /** The filtered conversations, one row per question and answer, bounded as the audit export is. */
    @Transactional(readOnly = true)
    public int export(ActorId reader, JdbcChatHistoryRepository.Query query, Consumer<Conversation> sink) {
        var access = readable(reader, query);
        int rows = 0;
        Cursor after = null;
        try {
            while (rows < MAX_EXPORT) {
                var page = history.page(access.tenant().value(), query, after, Math.min(1000, MAX_EXPORT - rows));
                if (page.isEmpty()) break;
                for (Entry entry : page) {
                    sink.accept(conversation(entry, access.visibility()));
                    rows++;
                }
                after = new Cursor(page.getLast().updatedAt(), page.getLast().id());
            }
        } catch (RuntimeException failure) {
            recordExport(access.tenant(), reader, query, rows, AuditOutcome.FAILURE);
            throw failure;
        }
        recordExport(access.tenant(), reader, query, rows, AuditOutcome.SUCCESS);
        return rows;
    }

    /** What the Tenant lets this reader see, resolved once per request. */
    private record Access(TenantId tenant, ChatHistoryVisibility visibility) {}

    private Access readable(ActorId reader, JdbcChatHistoryRepository.Query query) {
        var tenant = authorization.require(reader, IamCapability.CHAT_HISTORY_READ, false).tenantId();
        var visibility = settings.historyVisibility(tenant);
        if (visibility == ChatHistoryVisibility.DISABLED)
            throw ChatException.historyDisabled();
        // Narrowing to one person is asking who asked, which is the question this mode refuses, as in Onyx.
        if (visibility == ChatHistoryVisibility.ANONYMIZED && query.actorId() != null)
            throw ChatException.invalid("This organization hides who asked, so history cannot be read per person.");
        return new Access(tenant, visibility);
    }

    private static Conversation conversation(Entry entry, ChatHistoryVisibility visibility) {
        boolean named = visibility == ChatHistoryVisibility.NORMAL;
        return new Conversation(entry.id(), named ? entry.actorId() : null, named ? entry.actorLabel() : null,
                named ? entry.actorEmail() : null, entry.title(), entry.firstQuestion(), entry.firstAnswer(),
                entry.modelName(), entry.messages(), entry.feedback(), entry.deleted(), entry.updatedAt());
    }

    private void recordRead(Access access, ActorId reader, Entry entry, int messages) {
        boolean named = access.visibility() == ChatHistoryVisibility.NORMAL;
        audit.recordSeparately(AuditRecord.of(AuditAction.CHAT_HISTORY_READ, access.tenant()).actor(reader)
                .resource("CHAT_SESSION", entry.id(), entry.title())
                .detail("person", named ? entry.actorLabel() : null)
                .detail("email", named ? entry.actorEmail() : null)
                .detail("messages", messages).build());
    }

    private void recordExport(TenantId tenant, ActorId reader, JdbcChatHistoryRepository.Query query, int rows,
                              AuditOutcome outcome) {
        audit.recordSeparately(AuditRecord.of(AuditAction.CHAT_HISTORY_EXPORT, tenant).actor(reader)
                .resource("CHAT_HISTORY", null, null).outcome(outcome)
                .detail("from", query.from() == null ? null : query.from().toString())
                .detail("to", query.to() == null ? null : query.to().toString())
                .detail("rows", rows).build());
    }

    private static String encode(Entry last) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString((last.updatedAt().toString() + "|" + last.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static @Nullable Cursor decode(@Nullable String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            var parts = new String(java.util.Base64.getUrlDecoder().decode(cursor),
                    java.nio.charset.StandardCharsets.UTF_8).split("\\|");
            if (parts.length != 2) throw ChatException.invalid("Invalid history cursor.");
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException malformed) {
            throw ChatException.invalid("Invalid history cursor.");
        }
    }
}
