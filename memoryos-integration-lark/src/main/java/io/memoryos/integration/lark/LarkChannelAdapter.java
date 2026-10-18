package io.memoryos.integration.lark;

import com.lark.oapi.channel.LarkChannel;
import com.lark.oapi.channel.config.LarkChannelOptions;
import com.lark.oapi.channel.model.BotAddedEvent;
import com.lark.oapi.channel.model.NormalizedMessage;
import com.lark.oapi.channel.model.SendInput;
import com.lark.oapi.channel.model.SendOptions;
import com.lark.oapi.service.contact.v3.model.GetUserReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReactionReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReactionReqBody;
import com.lark.oapi.service.im.v1.model.Emoji;
import com.lark.oapi.service.im.v1.model.GetChatReq;
import com.lark.oapi.service.im.v1.model.ListChatReq;
import com.lark.oapi.service.im.v1.model.ListChatResp;
import com.lark.oapi.service.im.v1.model.P2ChatMemberBotAddedV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import io.memoryos.channel.ChannelAdapter;
import io.memoryos.channel.ChannelChat;
import io.memoryos.channel.ChannelConnection;
import io.memoryos.channel.ChannelCredentials;
import io.memoryos.channel.ChannelException;
import io.memoryos.channel.ChannelFeatures;
import io.memoryos.channel.ChannelGroupPresence;
import io.memoryos.channel.ChannelMessage;
import io.memoryos.channel.ChannelProvider;
import io.memoryos.channel.ChannelReceiver;
import io.memoryos.channel.ChannelRegion;
import io.memoryos.channel.ChannelReply;
import io.memoryos.channel.ChannelSender;
import io.memoryos.channel.ChannelTransport;
import io.memoryos.channel.ChannelVerification;
import io.memoryos.connector.LarkGateway;
import io.memoryos.connector.LarkProviderException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final String WAITING = "Get";
    private static final Pattern APP_ID = Pattern.compile("^cli_[0-9a-z]{8,40}$");
    /** A card's text may be far longer than an answer; the card is rewritten as the answer is written. */
    private static final ChannelFeatures FEATURES = new ChannelFeatures(true, true, 150_000);
    /** Lark's largest page of a bot's chats, and how many pages are read before the list is taken as unreadable. */
    private static final int CHAT_PAGE = 100;
    private static final int CHAT_PAGES = 50;

    private final LarkTransport transport;
    private final @Nullable RestLarkGateway gateway;
    private final Map<UUID, LarkChannel> channels = new ConcurrentHashMap<>();
    private final Map<String, Known> chats = new ConcurrentHashMap<>();
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
    public ChannelFeatures features() {
        return FEATURES;
    }

    /** An app is its App ID, which starts with {@code cli_}, on Lark international or Feishu; the secret is its App Secret. */
    @Override
    public ChannelVerification verify(ChannelCredentials credentials) {
        String appId = credentials.appId();
        if (appId == null || !APP_ID.matcher(appId).matches()) {
            throw ChannelException.invalid("Supply the App ID of the Lark app, which starts with cli_.");
        }
        var region = ChannelRegion.of(credentials.settings())
                .orElseThrow(() -> ChannelException.invalid("Choose whether the app is on Lark or Feishu."));
        if (gateway == null) throw ChannelException.providerUnavailable();
        byte[] bytes = credentials.secret().getBytes(StandardCharsets.UTF_8);
        try {
            var check = gateway.checkBot(region == ChannelRegion.FEISHU ? LarkGateway.Region.FEISHU : LarkGateway.Region.LARK,
                    appId, bytes);
            return new ChannelVerification(appId, check.bot().appName(), check.bot().openId(), check.missingScopes());
        } catch (LarkProviderException refused) {
            throw switch (refused.failure()) {
                case AUTHENTICATION, AUTHORIZATION, NOT_FOUND, MALFORMED -> ChannelException.rejected();
                default -> ChannelException.providerUnavailable();
            };
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /** A Lark bot's one setting is its app's region. */
    @Override
    public Map<String, String> settings(Map<String, String> requested) {
        var region = ChannelRegion.of(requested)
                .orElseThrow(() -> ChannelException.invalid("Choose whether the app is on Lark or Feishu."));
        if (requested.size() != 1) throw ChannelException.invalid("A Lark bot has no setting but its region.");
        return Map.of(ChannelRegion.SETTING, region.name());
    }

    @Override
    public ChannelProvider provider() {
        return ChannelProvider.LARK;
    }

    @Override
    public ChannelTransport open(ChannelConnection connection, String secret, ChannelReceiver receiver) {
        var policy = new LarkChannelOptions.PolicyConfig();
        policy.setRequireMention(true);
        policy.setRespondToMentionAll(false);
        var safety = new LarkChannelOptions.SafetyConfig();
        safety.setChatQueueEnabled(false);
        var options = LarkChannelOptions.newBuilder(connection.appId(), secret)
                .domain(baseUrl(region(connection))).httpTransport(transport)
                .policy(policy).safety(safety).includeRawInMessage(true).build();
        var channel = new LarkChannel(options);
        channel.on("message", (NormalizedMessage message) -> {
            var converted = convert(connection, message);
            if (converted != null) receiver.accept(converted);
        });
        // MEM-273: a group the bot is added to is on its page at once; Lark names the group in the event.
        channel.on("botAdded", (BotAddedEvent added) -> {
            String chatId = joinedGroup(added);
            if (chatId != null) receiver.joined(chatId, blankToNull(added.getChatName()));
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

    /** The group an added event names, or null for one with people from outside the organisation. */
    static @Nullable String joinedGroup(BotAddedEvent added) {
        if (!(added.getRaw() instanceof P2ChatMemberBotAddedV1 event) || event.getEvent() == null) return null;
        if (!Boolean.FALSE.equals(event.getEvent().getExternal())) return null;
        return added.getChatId();
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
                new ChannelMessage.Organisations(senderOrganisation, botOrganisation), direct || message.isMentionedBot(),
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

    /** The {@code Get} reaction, as spring-agent's Feishu bot puts on a message that waits behind another. */
    @Override
    public void acknowledge(ChannelConnection connection, ChannelMessage message) {
        try {
            var answer = channel(connection).getRawClient().im().v1().messageReaction().create(CreateMessageReactionReq
                    .newBuilder().messageId(message.messageId()).createMessageReactionReqBody(CreateMessageReactionReqBody
                            .newBuilder().reactionType(Emoji.newBuilder().emojiType(WAITING).build()).build()).build());
            if (answer == null || !answer.success()) {
                LOG.atWarn().addKeyValue("event", "channel.lark.reaction_failed")
                        .addKeyValue("error_code", answer == null ? -1 : answer.getCode()).log("Lark reaction was not put");
            }
        } catch (Exception unput) {
            LOG.atWarn().addKeyValue("event", "channel.lark.reaction_failed")
                    .addKeyValue("error_type", unput.getClass().getName()).log("Lark reaction was not put");
        }
    }

    @Override
    public void notice(ChannelConnection connection, ChannelMessage message, String text) {
        channel(connection).sendSync(message.chatId(), SendInput.text(text), replyTo(message));
    }

    /** The e-mail Lark's contact directory holds for the sender. */
    @Override
    public ChannelSender sender(ChannelConnection connection, String senderId) {
        try {
            var answer = channel(connection).getRawClient().contact().v3().user()
                    .get(GetUserReq.newBuilder().userId(senderId).userIdType("open_id").build());
            if (answer == null || !answer.success() || answer.getData() == null || answer.getData().getUser() == null) {
                return ChannelSender.UNKNOWN;
            }
            String email = answer.getData().getUser().getEmail();
            return email == null || email.isBlank() ? ChannelSender.UNKNOWN : new ChannelSender(email);
        } catch (Exception unreadable) {
            LOG.atWarn().addKeyValue("event", "channel.lark.sender_unreadable")
                    .addKeyValue("error_type", unreadable.getClass().getName()).log("Lark sender could not be read");
            return ChannelSender.UNKNOWN;
        }
    }

    /**
     * Whether people outside the organisation read the chat, and its name, remembered for an hour. A chat whose answer
     * cannot be read counts as external: the bot answers no group it cannot vouch for.
     */
    @Override
    public ChannelChat chat(ChannelConnection connection, String chatId) {
        Instant now = clock.instant();
        var known = chats.get(chatId);
        if (known != null && known.until().isAfter(now)) return known.chat();
        ChannelChat result;
        try {
            var answer = channel(connection).getRawClient().im().v1().chat().get(GetChatReq.newBuilder().chatId(chatId).build());
            boolean readable = answer != null && answer.success() && answer.getData() != null;
            String name = readable ? answer.getData().getName() : null;
            result = new ChannelChat(!readable || !Boolean.FALSE.equals(answer.getData().getExternal()),
                    name == null || name.isBlank() ? null : name.strip());
        } catch (Exception unreadable) {
            result = new ChannelChat(true, null);
        }
        chats.put(chatId, new Known(result, now.plus(CHAT_TTL)));
        return result;
    }

    /**
     * The groups the bot is in, page by page from {@code im/v1/chats}, which lists no direct chat; a group with people
     * from outside the organisation or no longer in use is left out, as the bot answers in neither. A page that cannot
     * be read, or more than {@link #CHAT_PAGES} of them, gives no list.
     */
    @Override
    public Optional<List<ChannelGroupPresence>> groups(ChannelConnection connection) {
        var client = channel(connection).getRawClient();
        return groups(token -> {
            var request = ListChatReq.newBuilder().pageSize(CHAT_PAGE);
            if (token != null) request.pageToken(token);
            return client.im().v1().chat().list(request.build());
        });
    }

    /** Reads one page of the bot's chats after {@code token}, null for the first. */
    @FunctionalInterface
    interface ChatPages {
        @Nullable ListChatResp read(@Nullable String token) throws Exception;
    }

    static Optional<List<ChannelGroupPresence>> groups(ChatPages pages) {
        var present = new ArrayList<ChannelGroupPresence>();
        String token = null;
        for (int page = 0; page < CHAT_PAGES; page++) {
            ListChatResp answer;
            try {
                answer = pages.read(token);
            } catch (Exception unreadable) {
                LOG.atWarn().addKeyValue("event", "channel.lark.chats_unreadable")
                        .addKeyValue("error_type", unreadable.getClass().getName()).log("Lark chats could not be listed");
                return Optional.empty();
            }
            if (answer == null || !answer.success() || answer.getData() == null) {
                LOG.atWarn().addKeyValue("event", "channel.lark.chats_unreadable")
                        .addKeyValue("error_code", answer == null ? -1 : answer.getCode()).log("Lark chats could not be listed");
                return Optional.empty();
            }
            var body = answer.getData();
            if (body.getItems() != null) {
                for (var chat : body.getItems()) {
                    if (chat.getChatId() == null || !Boolean.FALSE.equals(chat.getExternal())) continue;
                    if (chat.getChatStatus() != null && !"normal".equals(chat.getChatStatus())) continue;
                    present.add(new ChannelGroupPresence(chat.getChatId(), blankToNull(chat.getName())));
                }
            }
            token = body.getPageToken();
            if (!Boolean.TRUE.equals(body.getHasMore()) || token == null || token.isBlank()) return Optional.of(present);
        }
        LOG.atWarn().addKeyValue("event", "channel.lark.chats_too_many").log("Lark chats past the listed pages");
        return Optional.empty();
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
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

    /** A stored Lark bot always names its region; the database refuses one that does not. */
    private static ChannelRegion region(ChannelConnection connection) {
        return ChannelRegion.of(connection.settings())
                .orElseThrow(() -> new IllegalStateException("the Lark bot names no region"));
    }

    static String baseUrl(ChannelRegion region) {
        return switch (region) {
            case LARK -> "https://open.larksuite.com";
            case FEISHU -> "https://open.feishu.cn";
        };
    }

    private record Known(ChannelChat chat, Instant until) {}
}
