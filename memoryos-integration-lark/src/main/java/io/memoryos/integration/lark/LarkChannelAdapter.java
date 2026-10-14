package io.memoryos.integration.lark;

import com.lark.oapi.channel.LarkChannel;
import com.lark.oapi.channel.config.LarkChannelOptions;
import com.lark.oapi.channel.model.NormalizedMessage;
import com.lark.oapi.channel.model.SendInput;
import com.lark.oapi.channel.model.SendOptions;
import com.lark.oapi.service.contact.v3.model.GetUserReq;
import com.lark.oapi.service.im.v1.model.GetChatReq;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import io.memoryos.channel.ChannelAdapter;
import io.memoryos.channel.ChannelConnection;
import io.memoryos.channel.ChannelException;
import io.memoryos.channel.ChannelMessage;
import io.memoryos.channel.ChannelProvider;
import io.memoryos.channel.ChannelRegion;
import io.memoryos.channel.ChannelReply;
import io.memoryos.channel.ChannelTransport;
import io.memoryos.channel.ChannelVerification;
import io.memoryos.connector.LarkGateway;
import io.memoryos.connector.LarkProviderException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Lark bot (MEM-246) on the SDK's {@link LarkChannel}: its long connection, its normalized messages and its mention
 * rule. An answer is a card of the bot's own ({@link LarkReplyCard}), written through the channel's client. The SDK's own chat queue is off, because it orders and merges by chat while the
 * capability orders by conversation; its in-memory deduplication stays on and is not relied on. Requests go through
 * a {@link LarkTransport} of the bot's own, bounded like every other Lark call.
 *
 * <p>Whether a sender belongs to the bot's organisation is read from the event itself: a custom app belongs to one
 * organisation, which every event names in its header, and the sender's own organisation is in the event's body.
 */
public final class LarkChannelAdapter implements ChannelAdapter, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(LarkChannelAdapter.class);
    /** The budget of a call the SDK makes outside any exchange the adapter binds: sending, streaming, reading. */
    static final LarkTransport.Exchange CALL = new LarkTransport.Exchange(Duration.ofSeconds(30), 1_048_576);
    private static final Duration CHAT_TTL = Duration.ofHours(1);

    private final LarkTransport transport;
    private final @Nullable RestLarkGateway gateway;
    private final Map<UUID, LarkChannel> channels = new ConcurrentHashMap<>();
    private final Map<String, Known> external = new ConcurrentHashMap<>();
    private final Clock clock;

    public LarkChannelAdapter(LarkProviderProperties properties, @Nullable RestLarkGateway gateway) {
        this(new LarkTransport(properties.connectTimeout(), properties.userAgent(), CALL), gateway, Clock.systemUTC());
    }

    LarkChannelAdapter(LarkTransport transport, @Nullable RestLarkGateway gateway, Clock clock) {
        this.transport = transport;
        this.gateway = gateway;
        this.clock = clock;
    }

    @Override
    public ChannelVerification verify(ChannelRegion region, String appId, String secret) {
        if (gateway == null) throw ChannelException.providerUnavailable();
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        try {
            var check = gateway.checkBot(region == ChannelRegion.FEISHU ? LarkGateway.Region.FEISHU : LarkGateway.Region.LARK,
                    appId, bytes);
            return new ChannelVerification(check.bot().appName(), check.bot().openId(), check.missingScopes());
        } catch (LarkProviderException refused) {
            throw switch (refused.failure()) {
                case AUTHENTICATION, AUTHORIZATION, NOT_FOUND, MALFORMED -> ChannelException.rejected();
                default -> ChannelException.providerUnavailable();
            };
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public ChannelProvider provider() {
        return ChannelProvider.LARK;
    }

    @Override
    public ChannelTransport open(ChannelConnection connection, String secret, Consumer<ChannelMessage> receiver) {
        var policy = new LarkChannelOptions.PolicyConfig();
        policy.setRequireMention(true);
        policy.setRespondToMentionAll(false);
        var safety = new LarkChannelOptions.SafetyConfig();
        safety.setChatQueueEnabled(false);
        var options = LarkChannelOptions.newBuilder(connection.appId(), secret)
                .domain(baseUrl(connection.region())).httpTransport(transport)
                .policy(policy).safety(safety).includeRawInMessage(true).build();
        var channel = new LarkChannel(options);
        channel.on("message", (NormalizedMessage message) -> {
            var converted = convert(connection, message);
            if (converted != null) receiver.accept(converted);
        });
        channel.connectSync();
        channels.put(connection.id(), channel);
        return new ChannelTransport() {
            @Override
            public boolean connected() {
                return channel.isConnected();
            }

            @Override
            public void close() {
                channels.remove(connection.id(), channel);
                channel.disconnectSync();
            }
        };
    }

    /** A Lark message as the capability reads it; null for one that carries no sender. */
    static @Nullable ChannelMessage convert(ChannelConnection connection, NormalizedMessage message) {
        String senderOrganisation = "";
        String botOrganisation = "";
        if (message.getRaw() instanceof P2MessageReceiveV1 event) {
            if (event.getHeader() != null && event.getHeader().getTenantKey() != null) botOrganisation = event.getHeader().getTenantKey();
            if (event.getEvent() != null && event.getEvent().getSender() != null && event.getEvent().getSender().getTenantKey() != null) {
                senderOrganisation = event.getEvent().getSender().getTenantKey();
            }
        }
        if (message.getSenderId() == null || message.getMessageId() == null || message.getChatId() == null) return null;
        boolean direct = "p2p".equals(message.getChatType());
        String type = message.getRawContentType();
        boolean text = "text".equals(type) || "post".equals(type);
        String content = text && message.getContent() != null ? withoutBotMentions(message) : "";
        String repliesTo = message.getReplyToMessageId() != null ? message.getReplyToMessageId() : message.getRootId();
        return new ChannelMessage(connection.id(), message.getMessageId(), message.getChatId(),
                direct ? ChannelMessage.Kind.DIRECT : ChannelMessage.Kind.GROUP, message.getSenderId(), message.getSenderName(),
                senderOrganisation, botOrganisation, direct || message.isMentionedBot(),
                text ? ChannelMessage.Type.TEXT : ChannelMessage.Type.OTHER, content, repliesTo);
    }

    /**
     * The text without the bot's own mentions. Another person's mention stays, as the question names them, by name
     * rather than by the placeholder key Lark writes for it.
     */
    private static String withoutBotMentions(NormalizedMessage message) {
        String content = message.getContent();
        if (message.getMentions() != null) {
            for (var mention : message.getMentions()) {
                if (mention.getName() == null) continue;
                if (mention.isBot()) {
                    content = content.replace("@" + mention.getName(), "");
                    if (mention.getKey() != null) content = content.replace(mention.getKey(), "");
                } else if (mention.getKey() != null) {
                    content = content.replace(mention.getKey(), "@" + mention.getName());
                }
            }
        }
        return content.strip();
    }

    @Override
    public ChannelReply reply(ChannelConnection connection, ChannelMessage message, String waiting) {
        try {
            return new LarkReplyCard(channel(connection).getRawClient(), message, waiting);
        } catch (RuntimeException failed) {
            throw failed;
        } catch (Exception failed) {
            throw new IllegalStateException("the Lark reply could not be sent", failed);
        }
    }

    @Override
    public void notice(ChannelConnection connection, ChannelMessage message, String text) {
        channel(connection).sendSync(message.chatId(), SendInput.text(text), replyTo(message));
    }

    @Override
    public Optional<String> senderEmail(ChannelConnection connection, String senderId) {
        try {
            var answer = channel(connection).getRawClient().contact().v3().user()
                    .get(GetUserReq.newBuilder().userId(senderId).userIdType("open_id").build());
            if (answer == null || !answer.success() || answer.getData() == null || answer.getData().getUser() == null) {
                return Optional.empty();
            }
            String email = answer.getData().getUser().getEmail();
            return email == null || email.isBlank() ? Optional.empty() : Optional.of(email);
        } catch (Exception unreadable) {
            LOG.atWarn().addKeyValue("event", "channel.lark.sender_unreadable")
                    .addKeyValue("error_type", unreadable.getClass().getName()).log("Lark sender could not be read");
            return Optional.empty();
        }
    }

    /**
     * Whether people outside the organisation read the chat, remembered for an hour. A chat whose answer cannot be
     * read counts as external: the bot answers no group it cannot vouch for.
     */
    @Override
    public boolean externalChat(ChannelConnection connection, String chatId) {
        Instant now = clock.instant();
        var known = external.get(chatId);
        if (known != null && known.until().isAfter(now)) return known.external();
        boolean result;
        try {
            var answer = channel(connection).getRawClient().im().v1().chat().get(GetChatReq.newBuilder().chatId(chatId).build());
            result = answer == null || !answer.success() || answer.getData() == null
                    || !Boolean.FALSE.equals(answer.getData().getExternal());
        } catch (Exception unreadable) {
            result = true;
        }
        external.put(chatId, new Known(result, now.plus(CHAT_TTL)));
        return result;
    }

    @Override
    public void close() {
        channels.values().forEach(LarkChannel::disconnectSync);
        channels.clear();
        transport.close();
    }

    private LarkChannel channel(ChannelConnection connection) {
        var channel = channels.get(connection.id());
        if (channel == null) throw new IllegalStateException("the Lark bot's connection is not open");
        return channel;
    }

    private static SendOptions replyTo(ChannelMessage message) {
        return SendOptions.newBuilder().replyTo(message.messageId()).replyInThread(false).build();
    }

    static String baseUrl(ChannelRegion region) {
        return switch (region) {
            case LARK -> "https://open.larksuite.com";
            case FEISHU -> "https://open.feishu.cn";
        };
    }

    private record Known(boolean external, Instant until) {}
}
