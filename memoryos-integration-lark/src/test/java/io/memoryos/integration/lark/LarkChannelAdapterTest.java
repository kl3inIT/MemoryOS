package io.memoryos.integration.lark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lark.oapi.channel.model.MentionInfo;
import com.lark.oapi.channel.model.NormalizedMessage;
import com.lark.oapi.event.model.Header;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import io.memoryos.channel.ChannelConnection;
import io.memoryos.channel.ChannelMessage;
import io.memoryos.channel.ChannelProvider;
import io.memoryos.channel.ChannelRegion;
import io.memoryos.channel.ChannelReply;
import io.memoryos.shared.ActorId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class LarkChannelAdapterTest {
    private static final ChannelConnection CONNECTION = new ChannelConnection(UUID.randomUUID(), ChannelProvider.LARK, "Bot",
            ChannelRegion.LARK, "cli_a1b2c3d4e5f6", "vi", true, new ActorId(UUID.randomUUID()), 1, Instant.now(), Instant.now(), null, null, null, List.of());
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
        assertEquals("org-member", message.organisationKey());
        assertEquals("org-bot", message.botOrganisationKey());
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
    void anAnswerIsMarkdownWithItsSourcesAsLinksAndItsClosingLine() {
        assertEquals("""
                Bạn có 12 ngày phép.

                1. [Quy chế (2026)](https://tasco.larksuite.com/wiki/abc)
                2. Sổ tay nhân sự

                Trả lời vào tin này để hỏi tiếp.""",
                LarkChannelAdapter.markdown(" Bạn có 12 ngày phép. ", List.of(
                        new ChannelReply.Source("Quy chế [2026]", "https://tasco.larksuite.com/wiki/abc"),
                        new ChannelReply.Source("Sổ tay nhân sự", null)), "Trả lời vào tin này để hỏi tiếp."));
        assertEquals("Chỉ câu trả lời.", LarkChannelAdapter.markdown("Chỉ câu trả lời.", List.of(), null));
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
