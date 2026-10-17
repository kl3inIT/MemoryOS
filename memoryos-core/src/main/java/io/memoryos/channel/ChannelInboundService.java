package io.memoryos.channel;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;
import io.memoryos.channel.persistence.JdbcChannelConnectionRepository;
import io.memoryos.channel.persistence.JdbcChannelGroupRepository;
import io.memoryos.channel.persistence.JdbcChannelInboundRepository;
import io.memoryos.chat.ChatChannel;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.ChatSource;
import io.memoryos.iam.MemberLookup;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * What a bot does with a message it receives (MEM-246): answers it once, only for a member of its organisation or,
 * on a platform without organisations, a member who linked their account (MEM-263), only when it was addressed, as the sender in a direct message when the sender has an account here and as the
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
    private static final int ASKER_LENGTH = 64;
    /** Who a group's one conversation is kept for in {@code channel_conversations}: the group, not one of its members. */
    static final String GROUP = "@group";
    /** Spans of a received message and of each of its steps; {@code step} is link, refusal, sender, conversation, reply, turn or finish. */
    static final String MESSAGE = "memoryos.channel.message";
    static final String STEP = "memoryos.channel.message.step";

    private final JdbcChannelConnectionRepository connections;
    private final JdbcChannelInboundRepository inbound;
    private final JdbcChannelGroupRepository groups;
    private final ChannelAdapters adapters;
    private final MemberLookup members;
    private final ChannelLinkService links;
    private final ChannelTurns turns;
    private final ChannelProperties properties;
    private final ObservationRegistry observations;
    private final Clock clock = Clock.systemUTC();

    ChannelInboundService(JdbcChannelConnectionRepository connections, JdbcChannelInboundRepository inbound,
                          JdbcChannelGroupRepository groups, ChannelAdapters adapters, MemberLookup members,
                          ChannelLinkService links, ChannelTurns turns, ChannelProperties properties,
                          ObservationRegistry observations) {
        this.connections = connections;
        this.inbound = inbound;
        this.groups = groups;
        this.adapters = adapters;
        this.members = members;
        this.links = links;
        this.turns = turns;
        this.properties = properties;
        this.observations = observations;
    }

    /**
     * Handles one message to its end. The adapter has already acknowledged it to the provider and calls this on a
     * thread of its own; a message handed over again, by this process or another, is dropped at the claim.
     */
    public void receive(ChannelMessage message) {
        claim(message).ifPresent(Runnable::run);
    }

    /**
     * Claims a message at once and answers the work that handles it to its end, so a provider that pushes over HTTP is
     * answered only once the message is recorded (MEM-263). Empty for a message that is not for an enabled bot, not
     * addressed to it, or claimed before.
     */
    public Optional<Runnable> claim(ChannelMessage message) {
        var enabled = connections.findEnabled(message.connectionId()).orElse(null);
        if (enabled == null) return Optional.empty();
        var tenant = enabled.tenant();
        var connection = enabled.connection();
        // A group message that does not name the bot is not for it, and is not even recorded.
        if (message.kind() == ChannelMessage.Kind.GROUP && !message.addressed()) return Optional.empty();
        if (!inbound.claim(tenant, message)) return Optional.empty();
        return Optional.of(() -> {
            // One span a message, whose steps and Chat turn are its children: where the time before an answer goes.
            var observation = Observation.createNotStarted(MESSAGE, observations)
                    .lowCardinalityKeyValue("provider", connection.provider().name())
                    .lowCardinalityKeyValue("kind", message.kind().name()).start();
            String outcome = "FAILED";
            try (var ignored = observation.openScope()) {
                outcome = handle(tenant, connection, message);
            } finally {
                observation.lowCardinalityKeyValue("outcome", outcome).stop();
            }
        });
    }

    /** @return the outcome: {@code ANSWERED}, {@code REFUSED}, {@code LINKED}, {@code NEW_CONVERSATION} or {@code FAILED} */
    private String handle(TenantId tenant, ChannelConnection connection, ChannelMessage message) {
        var adapter = adapters.of(connection.provider());
        try {
            if (!adapter.features().organisations()) {
                var linking = step("link", () -> linking(connection, adapter, message));
                if (linking != null) return linking;
            }
            var refusal = step("refusal", () -> refusal(connection, adapter, message));
            if (refusal != null) {
                refuse(connection, adapter, message, refusal);
                return "REFUSED";
            }
            if (NEW_CONVERSATION.equals(message.text().strip())) {
                // A group's one conversation is started over by any member the bot answers there, as OpenClaw's /new.
                boolean group = message.kind() == ChannelMessage.Kind.GROUP;
                inbound.forget(connection.id(), message.chatId(), group ? GROUP : message.senderId());
                adapter.notice(connection, message, ChannelNotice.NEW_CONVERSATION.in(connection.noticeLanguage()));
                inbound.settle(connection.id(), message.messageId(), "ANSWERED", "NEW_CONVERSATION", null, null, null);
                return "NEW_CONVERSATION";
            }
            return answer(tenant, connection, adapter, message) ? "ANSWERED" : "FAILED";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(connection, message, null, "INTERRUPTED", interrupted);
        } catch (RuntimeException failure) {
            // Whatever went wrong before a reply was opened: the sender is told, the reason recorded.
            notice(connection, adapter, message, noticeFor(failure));
            fail(connection, message, null, codeOf(failure), failure);
        }
        return "FAILED";
    }

    /** One step of handling a message as a child span, named by a word of a bounded set. */
    private <T, E extends Exception> T step(String step, Step<T, E> action) throws E {
        var observation = Observation.createNotStarted(STEP, observations).lowCardinalityKeyValue("step", step).start();
        try (var ignored = observation.openScope()) {
            return action.run();
        } catch (Exception failure) {
            observation.error(failure);
            throw failure;
        } finally {
            observation.stop();
        }
    }

    @FunctionalInterface
    private interface Step<T, E extends Exception> {
        T run() throws E;
    }

    /** Why the message is not answered at all, or null. */
    private @Nullable Refusal refusal(ChannelConnection connection, ChannelAdapter adapter, ChannelMessage message) {
        if (!member(connection, adapter, message)) return new Refusal("OUTSIDER", ChannelNotice.OUTSIDER);
        // This message is already claimed, so it is counted with the minute's others.
        if (inbound.receivedFrom(connection.id(), message.senderId(), clock.instant().minus(Duration.ofMinutes(1)))
                > properties.senderRate()) {
            return new Refusal("RATE_LIMITED", ChannelNotice.RATE);
        }
        // Naming the bot with nothing to ask says nothing to answer, so the group is not told anything either.
        if (message.kind() == ChannelMessage.Kind.GROUP && message.type() == ChannelMessage.Type.TEXT && message.text().isBlank()) {
            return new Refusal("EMPTY", null);
        }
        if (message.type() != ChannelMessage.Type.TEXT || message.text().isBlank()) {
            return new Refusal("NOT_TEXT", ChannelNotice.TEXT_ONLY);
        }
        if (message.kind() == ChannelMessage.Kind.GROUP && adapter.chat(connection, message.chatId()).external()) {
            return new Refusal("EXTERNAL_GROUP", ChannelNotice.EXTERNAL_GROUP);
        }
        return null;
    }

    /**
     * For a provider without organisations, what a message from a sender is before any question (MEM-263): a sender
     * with five wrong codes within the hour is not heard; past the sender's rate nothing is tried; a linked sender's
     * code is not tried at all, whatever it is, and they are told to unlink first, so a linked account never changes
     * hands and learns nothing of the code; an unlinked sender's code is an attempt that counts until it links; a
     * linked sender goes on to be answered (null); anyone else is told how to link, at most once a minute. A code is
     * taken only in a direct message, where nobody else reads it. Every code that does not link gets the same notice,
     * whether it is wrong, used up, expired or another bot's.
     *
     * <p>An attempt is recorded on its message before the code is tried and counted with the sender's others, those
     * still in flight included, so attempts sent at once cannot pass the lock together.
     *
     * @return the outcome when the message ends here, or null when it is a linked sender's question
     */
    private @Nullable String linking(ChannelConnection connection, ChannelAdapter adapter, ChannelMessage message) {
        String sender = message.senderId();
        if (links.attempts(connection.id(), sender) >= ChannelLinkService.WRONG_CODES) {
            refuse(connection, adapter, message, new Refusal("LINK_LOCKED", null));
            return "REFUSED";
        }
        // Every message counts, codes included, so the rate also bounds how fast codes are tried.
        if (inbound.receivedFrom(connection.id(), sender, clock.instant().minus(Duration.ofMinutes(1))) > properties.senderRate()) {
            refuse(connection, adapter, message, new Refusal("RATE_LIMITED", ChannelNotice.RATE));
            return "REFUSED";
        }
        var code = message.kind() == ChannelMessage.Kind.DIRECT && message.type() == ChannelMessage.Type.TEXT
                ? ChannelLinkService.codeIn(message.text()).orElse(null) : null;
        boolean linked = links.linked(connection.id(), sender).isPresent();
        if (code != null && linked) {
            refuse(connection, adapter, message, new Refusal("LINK_ALREADY", ChannelNotice.LINK_ALREADY));
            return "REFUSED";
        }
        if (code != null) {
            inbound.settle(connection.id(), message.messageId(), "REFUSED", ChannelLinkService.WRONG_CODE, null, null, null);
            if (links.attempts(connection.id(), sender) > ChannelLinkService.WRONG_CODES) {
                refuse(connection, adapter, message, new Refusal("LINK_LOCKED", null));
                return "REFUSED";
            }
            switch (links.redeem(connection.id(), sender, code)) {
                case LINKED -> {
                    notice(connection, adapter, message, ChannelNotice.LINKED);
                    inbound.settle(connection.id(), message.messageId(), "ANSWERED", "LINKED", null, null, null);
                    return "LINKED";
                }
                // Linked to another member since it was checked: as for a linked sender, the code is not used.
                case ALREADY_LINKED -> refuse(connection, adapter, message, new Refusal("LINK_ALREADY", ChannelNotice.LINK_ALREADY));
                case NOT_LIVE -> refuse(connection, adapter, message,
                        new Refusal(ChannelLinkService.WRONG_CODE, ChannelNotice.LINK_CODE_INVALID));
            }
            return "REFUSED";
        }
        if (linked) return null;
        if (links.noticeDue(connection.id(), sender, message.chatId())) {
            refuseUnlinked(connection, adapter, message);
        } else {
            refuse(connection, adapter, message, new Refusal("LINK_REQUIRED_QUIET", null));
        }
        return "REFUSED";
    }

    /**
     * Tells a sender to link: in a direct message, how, ending with the web address where the code is got when one is
     * configured, bare since a provider may show no link markup; in a group, only to message the bot directly, where
     * the instructions are not posted for everyone.
     */
    private void refuseUnlinked(ChannelConnection connection, ChannelAdapter adapter, ChannelMessage message) {
        if (message.kind() != ChannelMessage.Kind.DIRECT) {
            refuse(connection, adapter, message, new Refusal(ChannelLinkService.LINK_REQUIRED, ChannelNotice.LINK_IN_DIRECT));
            return;
        }
        String instructions = ChannelNotice.LINK_REQUIRED.in(connection.noticeLanguage());
        String address = properties.linkingUrl();
        notice(connection, adapter, message, address == null ? instructions : instructions + "\n" + address);
        refuse(connection, adapter, message, new Refusal(ChannelLinkService.LINK_REQUIRED, null));
    }

    /**
     * Whether the sender may be answered at all. For a provider with organisations, the sender must belong to the
     * organisation of the bot's app, which the provider names on every event; a bot whose organisation was recorded
     * admits only that one, and an event that names neither admits nobody. A provider without organisations has
     * admitted only a linked sender by now.
     */
    private static boolean member(ChannelConnection connection, ChannelAdapter adapter, ChannelMessage message) {
        if (!adapter.features().organisations()) return true;
        var organisations = message.organisations();
        return organisations != null && !organisations.bot().isBlank() && organisations.bot().equals(organisations.sender())
                && (connection.organisationKey() == null || connection.organisationKey().equals(organisations.bot()));
    }

    /** @return whether the message was answered */
    private boolean answer(TenantId tenant, ChannelConnection connection, ChannelAdapter adapter, ChannelMessage message)
            throws InterruptedException {
        Instant now = clock.instant();
        ActorId sender = step("sender", () -> sender(tenant, connection, adapter, message.senderId(), now));
        boolean direct = message.kind() == ChannelMessage.Kind.DIRECT;
        // A link removed since it was checked: the sender is no longer anyone here, and is not answered as the bot.
        if (sender == null && !adapter.features().organisations()) {
            refuseUnlinked(connection, adapter, message);
            return false;
        }
        // A direct message is the sender's own; a group's answer is read by everyone in it, so it is the bot's.
        ActorId actor = direct && sender != null ? sender : connection.serviceAccount();
        // A group the bot answers in is one an administrator may give an assistant of its own (MEM-273).
        String group = direct ? null : message.chatId();
        if (group != null) groups.seen(tenant, connection.id(), group, adapter.chat(connection, group).name(), now);
        UUID session = step("conversation", () -> conversation(connection, message, actor, now));
        // The reply is opened once the question is accepted, so it is written while the turn is prepared and is not
        // shown while the question waits for a turn its conversation is running.
        var opened = new AtomicReference<ChannelReply>();
        var features = adapter.features();
        String question = actor.equals(connection.serviceAccount()) ? asked(message) : message.text();
        ChannelTurns.Answer answer;
        try {
            answer = step("turn", () -> turns.ask(actor, session, title(message.text()), request(connection, message),
                    question, new ChatChannel(connection.provider().platform(), !direct), connection.id(), group,
                    properties.orderWait(),
                    () -> adapter.acknowledge(connection, message), () -> {
                        var reply = step("reply", () -> adapter.reply(connection, message,
                                ChannelNotice.WRITING.in(connection.noticeLanguage())));
                        opened.set(reply);
                        // A reply that cannot be rewritten is written once, when the answer is whole.
                        Consumer<String> text = features.editsReply() ? reply::append : _ -> {};
                        return text;
                    }));
        } catch (RuntimeException failure) {
            var notice = noticeFor(failure);
            if (opened.get() != null) {
                try {
                    opened.get().fail(notice.in(connection.noticeLanguage()));
                } catch (RuntimeException undelivered) {
                    LOG.atWarn().addKeyValue("event", "channel.notice.failed").addKeyValue("provider", connection.provider())
                            .addKeyValue("error_type", undelivered.getClass().getName()).log("Channel notice was not delivered");
                }
            } else {
                notice(connection, adapter, message, notice);
            }
            fail(connection, message, session, codeOf(failure), failure);
            return false;
        }
        var reply = Objects.requireNonNull(opened.get(), "the reply was not opened");
        var answered = answer;
        // From here the reply is open, so whatever fails ends it, and its typing mark with it, in the sender's sight.
        try {
            if (direct) inbound.keep(tenant, connection.id(), message.chatId(), message.senderId(), answered.session(), now);
            else inbound.keep(tenant, connection.id(), message.chatId(), GROUP, answered.session(), now);
            if (answered.status() != ChatMessage.Status.COMPLETED) {
                var notice = "CHAT_CAPACITY_EXCEEDED".equals(answered.failureCode()) ? ChannelNotice.BUSY : ChannelNotice.FAILED;
                reply.fail(notice.in(connection.noticeLanguage()));
                inbound.settle(connection.id(), message.messageId(), "FAILED",
                        answered.failureCode() == null ? answered.status().name() : answered.failureCode(), answered.session(),
                        answered.assistantMessage(), null);
                return false;
            }
            var pieces = features.editsReply() ? List.of(answered.text()) : ChannelText.pieces(answered.text(), features.maxText());
            var sources = sources(answered.sources());
            var footer = footer(connection, direct, sender, answered.session());
            String replyId;
            try {
                replyId = step("finish", () -> reply.finish(pieces, sources, footer));
            } catch (ChannelReply.Incomplete partly) {
                // What was sent stays, a reply to it continues the conversation, and the sender is told the rest is missing.
                notice(connection, adapter, message, ChannelNotice.INCOMPLETE);
                inbound.settle(connection.id(), message.messageId(), "FAILED", "ANSWER_INCOMPLETE", answered.session(),
                        answered.assistantMessage(), partly.lastSent());
                return false;
            }
            inbound.settle(connection.id(), message.messageId(), "ANSWERED", null, answered.session(),
                    answered.assistantMessage(), replyId);
        } catch (RuntimeException failure) {
            try {
                reply.fail(noticeFor(failure).in(connection.noticeLanguage()));
            } catch (RuntimeException undelivered) {
                LOG.atWarn().addKeyValue("event", "channel.notice.failed").addKeyValue("provider", connection.provider())
                        .addKeyValue("error_type", undelivered.getClass().getName()).log("Channel notice was not delivered");
            }
            fail(connection, message, answered.session(), codeOf(failure), failure);
            return false;
        }
        LOG.atInfo().addKeyValue("event", "channel.message.answered").addKeyValue("provider", connection.provider())
                .log("Channel message answered");
        return true;
    }

    /** Settles what an ended process claimed and never settled, claimed before {@code before}; how many. */
    int settleAbandoned(Instant before) {
        return inbound.settleAbandoned(before);
    }

    /**
     * The sender's account here, or null: the account they linked, else the one found by the e-mail the provider
     * holds, remembered for a while.
     */
    private @Nullable ActorId sender(TenantId tenant, ChannelConnection connection, ChannelAdapter adapter, String senderId,
                                     Instant now) {
        var linked = links.linked(connection.id(), senderId);
        if (linked.isPresent()) return linked.get();
        // A provider without organisations knows a sender only by the link: no e-mail and no remembered answer.
        if (!adapter.features().organisations()) return null;
        var known = inbound.identity(connection.id(), senderId).orElse(null);
        if (known != null && known.resolvedAt().plus(properties.identityTtl()).isAfter(now)) return known.actor();
        String email = adapter.sender(connection, senderId).email();
        ActorId actor = email == null ? null : members.activeMemberByVerifiedEmail(tenant, email).orElse(null);
        inbound.remember(tenant, connection.id(), senderId, actor, now);
        return actor;
    }

    /**
     * The session the message continues, or null for a new one. A group has one conversation, as OpenClaw keys a
     * session by group (its Feishu default {@code groupSessionScope: group}), continued while it is recent: it runs as
     * the bot's account, so it holds only what everyone in the group may read, and each question in it carries its
     * asker's name. A reply in a group continues the conversation of the answer it replies to, looked up in that group
     * alone, so it never leaves the group for someone's direct conversation.
     */
    private @Nullable UUID conversation(ChannelConnection connection, ChannelMessage message, ActorId actor, Instant now) {
        UUID session = null;
        if (message.kind() == ChannelMessage.Kind.GROUP) {
            if (message.repliesTo() != null) {
                session = inbound.sessionOf(connection.id(), message.chatId(), message.repliesTo()).orElse(null);
            }
            if (session == null) session = recent(connection, message.chatId(), GROUP, properties.groupConversationIdle(), now);
        } else if (message.repliesTo() != null) {
            session = inbound.sessionOf(connection.id(), message.chatId(), message.repliesTo()).orElse(null);
        } else {
            session = recent(connection, message.chatId(), message.senderId(), properties.conversationIdle(), now);
        }
        // A conversation is continued only by whoever it belongs to: a sender who gained or lost an account since
        // starts over, a reply to another person's direct conversation never opens it, and a bot moved to another
        // assistant, or a group given another, starts its conversations over with it.
        String group = message.kind() == ChannelMessage.Kind.GROUP ? message.chatId() : null;
        return session != null && turns.continues(actor, session, connection.id(), group) ? session : null;
    }

    private void refuse(ChannelConnection connection, ChannelAdapter adapter, ChannelMessage message, Refusal refusal) {
        if (refusal.notice() != null) notice(connection, adapter, message, refusal.notice());
        inbound.settle(connection.id(), message.messageId(), "REFUSED", refusal.reason(), null, null, null);
        LOG.atInfo().addKeyValue("event", "channel.message.rejected").addKeyValue("provider", connection.provider())
                .addKeyValue("reason", refusal.reason()).log("Channel message refused");
    }

    /** The conversation a chat continues for one person, or for a group, while its last message is within {@code idle}. */
    private @Nullable UUID recent(ChannelConnection connection, String chatId, String person, Duration idle, Instant now) {
        return inbound.conversation(connection.id(), chatId, person)
                .filter(last -> last.lastMessageAt().plus(idle).isAfter(now))
                .map(JdbcChannelInboundRepository.Conversation::session).orElse(null);
    }

    private void notice(ChannelConnection connection, ChannelAdapter adapter, ChannelMessage message, ChannelNotice notice) {
        notice(connection, adapter, message, notice.in(connection.noticeLanguage()));
    }

    private void notice(ChannelConnection connection, ChannelAdapter adapter, ChannelMessage message, String text) {
        try {
            adapter.notice(connection, message, text);
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

    /**
     * The closing line, in a direct conversation only: to a sender without an account, how to get one; to a sender's
     * own conversation, the link that opens it in MemoryOS, where it continues.
     */
    private @Nullable String footer(ChannelConnection connection, boolean direct, @Nullable ActorId sender, UUID session) {
        // A group answer has no closing line: its conversation continues by itself, and no reference bot adds one.
        if (!direct) return null;
        if (sender == null) return ChannelNotice.UNMAPPED_FOOTER.in(connection.noticeLanguage());
        String url = properties.sessionUrl(session);
        return url == null ? null : "[" + ChannelNotice.CONTINUE_ON_WEB.in(connection.noticeLanguage()) + "](" + url + ")";
    }

    private static List<ChannelReply.Source> sources(List<ChatSource> sources) {
        return sources.stream().map(source -> new ChannelReply.Source(source.title(), source.providerUrl())).distinct().toList();
    }

    /**
     * The question a turn that runs as the bot is asked: labelled with the asker's name, so the answer addresses
     * them. The label names; it is cut to a plain name so that it cannot pass for an instruction.
     */
    static String asked(ChannelMessage message) {
        String name = message.senderName() == null ? "" : message.senderName().replaceAll("[\\[\\]\\s]+", " ").strip();
        if (name.isEmpty()) return message.text();
        if (name.length() > ASKER_LENGTH) name = name.substring(0, ASKER_LENGTH).strip();
        return "[" + name + "]: " + message.text();
    }

    private static String title(String text) {
        String line = text.strip().replaceAll("\\s+", " ");
        return line.length() <= TITLE_LENGTH ? line : line.substring(0, TITLE_LENGTH).strip();
    }

    /** One request id a provider message, so a question handed over twice is one Chat command. */
    private static UUID request(ChannelConnection connection, ChannelMessage message) {
        return UUID.nameUUIDFromBytes((connection.id() + ":" + message.messageId()).getBytes(StandardCharsets.UTF_8));
    }

    /** {@code notice} is null for a refusal the sender is not told of again. */
    private record Refusal(String reason, @Nullable ChannelNotice notice) {}
}
