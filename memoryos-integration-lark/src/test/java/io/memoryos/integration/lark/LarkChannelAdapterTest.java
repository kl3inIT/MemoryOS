package io.memoryos.integration.lark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lark.oapi.channel.model.BotAddedEvent;
import com.lark.oapi.channel.model.MentionInfo;
import com.lark.oapi.channel.model.NormalizedMessage;
import com.lark.oapi.event.model.Header;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.ListChat;
import com.lark.oapi.service.im.v1.model.ListChatResp;
import com.lark.oapi.service.im.v1.model.ListChatRespBody;
import com.lark.oapi.service.im.v1.model.P2ChatMemberBotAddedV1;
import com.lark.oapi.service.im.v1.model.P2ChatMemberBotAddedV1Data;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import io.memoryos.channel.ChannelConnection;
import io.memoryos.channel.ChannelCredentials;
import io.memoryos.channel.ChannelException;
import io.memoryos.channel.ChannelFeatures;
import io.memoryos.channel.ChannelGroupPresence;
import io.memoryos.channel.ChannelMessage;
import io.memoryos.channel.ChannelProvider;
import io.memoryos.channel.ChannelRegion;
import io.memoryos.shared.ActorId;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class LarkChannelAdapterTest {
    private static final ChannelConnection CONNECTION = new ChannelConnection(UUID.randomUUID(), ChannelProvider.LARK, "Bot",
            "cli_a1b2c3d4e5f6", Map.of(ChannelRegion.SETTING, "LARK"), "vi", true, new ActorId(UUID.randomUUID()), 1, Instant.now(), Instant.now(), null, null, null, List.of());
    private static final MentionInfo BOT = new MentionInfo("@_user_1", "ou_bot", "Trợ lý", true);
    private static final MentionInfo QUAN = new MentionInfo("@_user_2", "ou_quan", "Quân", false);

    @Test
    void aGroupMessageNamingTheBotIsAddressedWithTheMentionRemovedAndBothOrganisations() {
        var message = LarkChannelAdapter.convert(CONNECTION, message("oc_group", "group", "@Trợ lý chính sách nghỉ phép cho @Quân?",
                "text", List.of(BOT, QUAN), true, null, "org-member", "org-bot"));

        assertEquals(ChannelMessage.Kind.GROUP, message.kind());
        assertTrue(message.addressed());
        assertEquals(ChannelMessage.Type.TEXT, message.type());
        assertEquals("chính sách nghỉ phép cho @Quân?", message.text());
        assertEquals(new ChannelMessage.Organisations("org-member", "org-bot"), message.organisations());
        assertNull(message.repliesTo());
    }

    @Test
    void aDirectMessageIsAddressedAndAReplyNamesWhatItRepliesTo() {
        var message = LarkChannelAdapter.convert(CONNECTION, message("oc_direct", "p2p", "Còn thử việc thì sao?", "text", List.of(),
                false, "om_answer", "org", "org"));

        assertEquals(ChannelMessage.Kind.DIRECT, message.kind());
        assertTrue(message.addressed());
        assertEquals("om_answer", message.repliesTo());
    }

    @Test
    void aMessageThatIsNotTextCarriesNoTextAndAGroupMessageWithoutTheBotIsNotAddressed() {
        var image = LarkChannelAdapter.convert(CONNECTION, message("oc_direct", "p2p", "[image]", "image", List.of(), false, null, "o", "o"));
        assertEquals(ChannelMessage.Type.OTHER, image.type());
        assertEquals("", image.text());

        var chatter = LarkChannelAdapter.convert(CONNECTION, message("oc_group", "group", "hello all", "text", List.of(), false, null, "o", "o"));
        assertFalse(chatter.addressed());
    }

    @Test
    void anotherPersonsMentionKeyBecomesTheirName() {
        var message = LarkChannelAdapter.convert(CONNECTION, message("oc_group", "group", "@_user_1 hỏi giúp @_user_2 về phép",
                "text", List.of(BOT, QUAN), true, null, "o", "o"));

        assertEquals("hỏi giúp @Quân về phép", message.text());
    }

    @Test
    void anAppIsCheckedForItsShapeBeforeLarkIsAsked() {
        try (var adapter = new LarkChannelAdapter(new LarkTransport(Duration.ofSeconds(1), "memoryos-test",
                LarkChannelAdapter.CALL), null, Clock.systemUTC())) {
            var lark = Map.of(ChannelRegion.SETTING, "LARK");
            assertEquals("CHANNEL_INVALID", code(() -> adapter.verify(new ChannelCredentials("app-without-prefix", "s", lark))));
            assertEquals("CHANNEL_INVALID", code(() -> adapter.verify(new ChannelCredentials(null, "s", lark))));
            assertEquals("CHANNEL_INVALID", code(() -> adapter.verify(new ChannelCredentials("cli_a1b2c3d4e5f6", "s",
                    Map.of(ChannelRegion.SETTING, "MARS")))));
            // Well formed, it is Lark's to answer, and no gateway here means Lark cannot be asked.
            assertEquals("CHANNEL_PROVIDER_UNAVAILABLE", code(() -> adapter.verify(new ChannelCredentials("cli_a1b2c3d4e5f6", "s", lark))));
            assertEquals(new ChannelFeatures(true, true, 150_000, false), adapter.features());
        }
    }

    private static String code(Runnable call) {
        return assertThrows(ChannelException.class, call::run).code();
    }

    /** MEM-273 5d: the bot's groups are every page Lark lists, without a group of outsiders or one disbanded. */
    @Test
    void theBotsGroupsAreEveryPageLarkListsOfItsOrganisationsGroups() {
        var asked = new ArrayList<String>();
        var groups = LarkChannelAdapter.groups(token -> {
            asked.add(String.valueOf(token));
            return token == null
                    ? page(true, "p2", chat("oc_accounting", " Phòng Kế toán ", false, "normal"),
                            chat("oc_partners", "Đối tác", true, "normal"))
                    : page(false, null, chat("oc_hr", "", false, "normal"), chat("oc_old", "Cũ", false, "dissolved"));
        });

        assertEquals(Optional.of(List.of(new ChannelGroupPresence("oc_accounting", "Phòng Kế toán"),
                new ChannelGroupPresence("oc_hr", null))), groups);
        assertEquals(List.of("null", "p2"), asked);
    }

    /** A list read only in part would remove groups the bot is still in, so it is no list. */
    @Test
    void aListReadInPartIsNoList() {
        var refused = new ListChatResp();
        refused.setCode(99991672);
        assertEquals(Optional.empty(), LarkChannelAdapter.groups(token -> token == null ? page(true, "p2") : refused));
        assertEquals(Optional.empty(), LarkChannelAdapter.groups(token -> {
            if (token != null) throw new IOException("Lark exchange passed its deadline");
            return page(true, "p2");
        }));
        var pages = new AtomicInteger();
        assertEquals(Optional.empty(), LarkChannelAdapter.groups(token -> page(true, "p" + pages.incrementAndGet())));
        assertEquals(50, pages.get());
    }

    @Test
    void aGroupTheBotIsAddedToCountsOnlyWithoutOutsiders() {
        assertEquals("oc_accounting", LarkChannelAdapter.joinedGroup(added("oc_accounting", false)));
        assertNull(LarkChannelAdapter.joinedGroup(added("oc_partners", true)));
        assertNull(LarkChannelAdapter.joinedGroup(added("oc_unknown", null)));
    }

    private static BotAddedEvent added(String chatId, @Nullable Boolean external) {
        var data = new P2ChatMemberBotAddedV1Data();
        data.setChatId(chatId);
        data.setExternal(external);
        var event = new P2ChatMemberBotAddedV1();
        event.setEvent(data);
        return new BotAddedEvent(chatId, "ou_operator", "Phòng Kế toán", event);
    }

    private static ListChatResp page(boolean more, @Nullable String token, ListChat... chats) {
        var body = new ListChatRespBody();
        body.setItems(chats);
        body.setHasMore(more);
        body.setPageToken(token);
        var answer = new ListChatResp();
        answer.setData(body);
        return answer;
    }

    private static ListChat chat(String id, String name, boolean external, String status) {
        var chat = new ListChat();
        chat.setChatId(id);
        chat.setName(name);
        chat.setExternal(external);
        chat.setChatStatus(status);
        return chat;
    }

    @Test
    void eachRegionHasItsAddress() {
        assertEquals("https://open.larksuite.com", LarkChannelAdapter.baseUrl(ChannelRegion.LARK));
        assertEquals("https://open.feishu.cn", LarkChannelAdapter.baseUrl(ChannelRegion.FEISHU));
    }

    private static NormalizedMessage message(String chatId, String chatType, String content, String type, List<MentionInfo> mentions,
                                             boolean mentionedBot, @Nullable String replyTo, String senderOrganisation,
                                             String botOrganisation) {
        var header = new Header();
        header.setTenantKey(botOrganisation);
        var sender = new EventSender();
        sender.setTenantKey(senderOrganisation);
        var data = new P2MessageReceiveV1Data();
        data.setSender(sender);
        var event = new P2MessageReceiveV1();
        event.setHeader(header);
        event.setEvent(data);
        return new NormalizedMessage("om_" + UUID.randomUUID(), chatId, chatType, "ou_sender", "Hà", content, type, List.of(),
                mentions, false, mentionedBot, null, null, replyTo, 0L, event);
    }
}
