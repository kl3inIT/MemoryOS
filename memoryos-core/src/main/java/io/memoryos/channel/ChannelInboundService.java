package io.memoryos.channel;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;
import io.memoryos.channel.persistence.JdbcChannelConnectionRepository;
import io.memoryos.channel.persistence.JdbcChannelInboundRepository;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.ChatSource;
import io.memoryos.iam.MemberLookup;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * What a bot does with a message it receives (MEM-246): answers it once, only for a member of its organisation,
 * only when it was addressed, as the sender in a direct message when the sender has an account here and as the
 * bot's own account otherwise, in the conversation the message continues.
 *
 * <p>A reply to a message continues that message's conversation; a new message in a group starts one; a new direct
 * message continues the last one while it is recent; {@code /new} in a direct message starts over. Every refusal
 * and failure is a notice in the bot's language and a recorded reason.
 */
@Service
@EnableConfigurationProperties(ChannelProperties.class)
public class ChannelInboundService {
    private static final Logger LOG = LoggerFactory.getLogger(ChannelInboundService.class);
    private static final String NEW_CONVERSATION = "/new";
    private static final int TITLE_LENGTH = 80;

    private final JdbcChannelConnectionRepository connections;
    private final JdbcChannelInboundRepository inbound;
    private final ChannelAdapters adapters;
    private final MemberLookup members;
    private final ChannelTurns turns;
    private final ChannelProperties properties;
    private final Clock clock = Clock.systemUTC();

    ChannelInboundService(JdbcChannelConnectionRepository connections, JdbcChannelInboundRepository inbound,
                          ChannelAdapters adapters, MemberLookup members, ChannelTurns turns, ChannelProperties properties) {
        this.connections = connections;
        this.inbound = inbound;
        this.adapters = adapters;
        this.members = members;
        this.turns = turns;
        this.properties = properties;
    }

    /**
     * Handles one message to its end. The adapter has already acknowledged it to the provider and calls this on a
     * thread of its own; a message handed over again, by this process or another, is dropped at the claim.
     */
    public void receive(ChannelMessage message) {
        var enabled = connections.findEnabled(message.connectionId()).orElse(null);
        if (enabled == null) return;
        var tenant = enabled.tenant();
        var connection = enabled.connection();
        // A group message that does not name the bot is not for it, and is not even recorded.
        if (message.kind() == ChannelMessage.Kind.GROUP && !message.addressed()) return;
        if (!inbound.claim(tenant, message)) return;
        var adapter = adapters.of(connection.provider());
        try {
            var refusal = refusal(connection, adapter, message);
            if (refusal != null) {
                refuse(connection, adapter, message, refusal);
                return;
            }
            if (message.kind() == ChannelMessage.Kind.DIRECT && NEW_CONVERSATION.equals(message.text().strip())) {
                inbound.forget(connection.id(), message.chatId(), message.senderId());
                adapter.notice(connection, message, ChannelNotice.NEW_CONVERSATION.in(connection.noticeLanguage()));
                inbound.settle(connection.id(), message.messageId(), "ANSWERED", "NEW_CONVERSATION", null, null, null);
                return;
            }
            answer(tenant, connection, adapter, message);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(connection, message, null, "INTERRUPTED", interrupted);
        } catch (RuntimeException failure) {
            // Whatever went wrong before a reply was opened: the sender is told, the reason recorded.
            notice(connection, adapter, message, noticeFor(failure));
            fail(connection, message, null, codeOf(failure), failure);
        }
    }

    /** Why the message is not answered at all, or null. */
    private @Nullable Refusal refusal(ChannelConnection connection, ChannelAdapter adapter, ChannelMessage message) {
        // The sender must belong to the organisation of the bot's app, which the provider names on every event; a bot
        // whose organisation was recorded admits only that one. An event that names neither admits nobody.
        boolean member = !message.botOrganisationKey().isBlank()
                && message.botOrganisationKey().equals(message.organisationKey())
                && (connection.organisationKey() == null || connection.organisationKey().equals(message.botOrganisationKey()));
        if (!member) return new Refusal("OUTSIDER", ChannelNotice.OUTSIDER);
        if (message.type() != ChannelMessage.Type.TEXT || message.text().isBlank()) {
            return new Refusal("NOT_TEXT", ChannelNotice.TEXT_ONLY);
        }
        if (message.kind() == ChannelMessage.Kind.GROUP && adapter.externalChat(connection, message.chatId())) {
            return new Refusal("EXTERNAL_GROUP", ChannelNotice.EXTERNAL_GROUP);
        }
        return null;
    }

    private void answer(TenantId tenant, ChannelConnection connection, ChannelAdapter adapter, ChannelMessage message)
            throws InterruptedException {
        Instant now = clock.instant();
        ActorId sender = sender(tenant, connection, adapter, message.senderId(), now);
        boolean direct = message.kind() == ChannelMessage.Kind.DIRECT;
        // A direct message is the sender's own; a group's answer is read by everyone in it, so it is the bot's.
        ActorId actor = direct && sender != null ? sender : connection.serviceAccount();
        UUID session = conversation(connection, message, actor, now);
        var reply = adapter.reply(connection, message, ChannelNotice.WRITING.in(connection.noticeLanguage()));
        ChannelTurns.Answer answer;
        try {
            answer = turns.ask(actor, session, title(message.text()), request(connection, message), message.text(),
                    reply::append);
        } catch (RuntimeException failure) {
            reply.fail(noticeFor(failure).in(connection.noticeLanguage()));
            fail(connection, message, session, codeOf(failure), failure);
            return;
        }
        if (direct) inbound.keep(tenant, connection.id(), message.chatId(), message.senderId(), answer.session(), now);
        if (answer.status() != ChatMessage.Status.COMPLETED) {
            var notice = "CHAT_CAPACITY_EXCEEDED".equals(answer.failureCode()) ? ChannelNotice.BUSY : ChannelNotice.FAILED;
            reply.fail(notice.in(connection.noticeLanguage()));
            inbound.settle(connection.id(), message.messageId(), "FAILED",
                    answer.failureCode() == null ? answer.status().name() : answer.failureCode(), answer.session(),
                    answer.assistantMessage(), null);
            return;
        }
        String replyId = reply.finish(answer.text(), sources(answer.sources()), footer(connection, direct, sender));
        inbound.settle(connection.id(), message.messageId(), "ANSWERED", null, answer.session(), answer.assistantMessage(),
                replyId);
        LOG.atInfo().addKeyValue("event", "channel.message.answered").addKeyValue("provider", connection.provider())
                .log("Channel message answered");
    }

    /** The sender's account here, or null: remembered for a while, else found by the e-mail the provider holds. */
    private @Nullable ActorId sender(TenantId tenant, ChannelConnection connection, ChannelAdapter adapter, String senderId,
                                     Instant now) {
        var known = inbound.identity(connection.id(), senderId).orElse(null);
        if (known != null && known.resolvedAt().plus(properties.identityTtl()).isAfter(now)) return known.actor();
        ActorId actor = adapter.senderEmail(connection, senderId)
                .flatMap(email -> members.activeMemberByVerifiedEmail(tenant, email)).orElse(null);
        inbound.remember(tenant, connection.id(), senderId, actor, now);
        return actor;
    }

    /** The session the message continues, or null for a new one. */
    private @Nullable UUID conversation(ChannelConnection connection, ChannelMessage message, ActorId actor, Instant now) {
        UUID session = null;
        if (message.repliesTo() != null) {
            session = inbound.sessionOf(connection.id(), message.repliesTo()).orElse(null);
        } else if (message.kind() == ChannelMessage.Kind.DIRECT) {
            session = inbound.conversation(connection.id(), message.chatId(), message.senderId())
                    .filter(last -> last.lastMessageAt().plus(properties.conversationIdle()).isAfter(now))
                    .map(JdbcChannelInboundRepository.Conversation::session).orElse(null);
        }
        // A conversation is continued only by whoever it belongs to: a sender who gained or lost an account since
        // starts over, and a reply to another person's direct conversation never opens it.
        return session != null && turns.owns(actor, session) ? session : null;
    }

    private void refuse(ChannelConnection connection, ChannelAdapter adapter, ChannelMessage message, Refusal refusal) {
        notice(connection, adapter, message, refusal.notice());
        inbound.settle(connection.id(), message.messageId(), "REFUSED", refusal.reason(), null, null, null);
        LOG.atInfo().addKeyValue("event", "channel.message.rejected").addKeyValue("provider", connection.provider())
                .addKeyValue("reason", refusal.reason()).log("Channel message refused");
    }

    private void notice(ChannelConnection connection, ChannelAdapter adapter, ChannelMessage message, ChannelNotice notice) {
        try {
            adapter.notice(connection, message, notice.in(connection.noticeLanguage()));
        } catch (RuntimeException undelivered) {
            LOG.atWarn().addKeyValue("event", "channel.notice.failed").addKeyValue("provider", connection.provider())
                    .addKeyValue("error_type", undelivered.getClass().getName()).log("Channel notice was not delivered");
        }
    }

    private void fail(ChannelConnection connection, ChannelMessage message, @Nullable UUID session, String code,
                      Exception failure) {
        inbound.settle(connection.id(), message.messageId(), "FAILED", code, session, null, null);
        LOG.atWarn().addKeyValue("event", "channel.message.failed").addKeyValue("provider", connection.provider())
                .addKeyValue("error_code", code).addKeyValue("error_type", failure.getClass().getName())
                .log("Channel message failed");
    }

    private static ChannelNotice noticeFor(RuntimeException failure) {
        if (failure instanceof BusinessException business) {
            if (business.category() == FailureCategory.LIMIT_EXCEEDED) return ChannelNotice.LIMIT;
            if (business.category() == FailureCategory.SERVICE_UNAVAILABLE || business.category() == FailureCategory.CONFLICT) {
                return ChannelNotice.BUSY;
            }
        }
        return ChannelNotice.FAILED;
    }

    private static String codeOf(RuntimeException failure) {
        return failure instanceof BusinessException business ? business.code() : "CHANNEL_FAILED";
    }

    private static @Nullable String footer(ChannelConnection connection, boolean direct, @Nullable ActorId sender) {
        if (!direct) return ChannelNotice.GROUP_FOOTER.in(connection.noticeLanguage());
        return sender == null ? ChannelNotice.UNMAPPED_FOOTER.in(connection.noticeLanguage()) : null;
    }

    private static List<ChannelReply.Source> sources(List<ChatSource> sources) {
        return sources.stream().map(source -> new ChannelReply.Source(source.title(), source.providerUrl())).distinct().toList();
    }

    private static String title(String text) {
        String line = text.strip().replaceAll("\\s+", " ");
        return line.length() <= TITLE_LENGTH ? line : line.substring(0, TITLE_LENGTH).strip();
    }

    /** One request id a provider message, so a question handed over twice is one Chat command. */
    private static UUID request(ChannelConnection connection, ChannelMessage message) {
        return UUID.nameUUIDFromBytes((connection.id() + ":" + message.messageId()).getBytes(StandardCharsets.UTF_8));
    }

    private record Refusal(String reason, ChannelNotice notice) {}
}
