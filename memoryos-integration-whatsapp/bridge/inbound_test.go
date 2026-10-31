package main

import (
	"testing"

	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

var (
	botPhone = types.NewJID("84900000001", types.DefaultUserServer)
	botLID   = types.NewJID("111111111111111", types.HiddenUserServer)
	me       = self{phone: botPhone, lid: botLID}
	alice    = types.NewJID("84900000002", types.DefaultUserServer)
	aliceLID = types.NewJID("222222222222222", types.HiddenUserServer)
	team     = types.NewJID("120363000000000001", types.GroupServer)
)

func noLIDs(types.JID) (types.JID, bool) { return types.EmptyJID, false }

func direct(sender types.JID, message *waE2E.Message) *events.Message {
	return &events.Message{Info: types.MessageInfo{
		MessageSource: types.MessageSource{Chat: sender, Sender: sender},
		ID:            "M1", PushName: "Alice",
	}, Message: message}
}

func inGroup(sender types.JID, message *waE2E.Message) *events.Message {
	return &events.Message{Info: types.MessageInfo{
		MessageSource: types.MessageSource{Chat: team, Sender: sender, IsGroup: true},
		ID:            "G1", PushName: "Alice",
	}, Message: message}
}

func text(value string) *waE2E.Message { return &waE2E.Message{Conversation: proto.String(value)} }

func extended(value string, context *waE2E.ContextInfo) *waE2E.Message {
	return &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{Text: proto.String(value), ContextInfo: context}}
}

func TestDirectTextIsAddressedAndKnownByTheSendersLID(t *testing.T) {
	evt := direct(alice, text("  Quy trình nghỉ phép?  "))
	evt.Info.SenderAlt = aliceLID
	got, ok := convert(evt, me, noLIDs)
	if !ok {
		t.Fatal("a direct text is answered")
	}
	if got.Sender != aliceLID.String() || got.Chat != aliceLID.String() {
		t.Fatalf("sender and chat are the LID, got %q and %q", got.Sender, got.Chat)
	}
	if got.Type != "TEXT" || got.Text != "Quy trình nghỉ phép?" || !got.Addressed || got.Group {
		t.Fatalf("unexpected message %+v", got)
	}
	if got.SenderName != "Alice" || got.ID != "M1" {
		t.Fatalf("unexpected identity %+v", got)
	}
}

func TestThePhoneIsMappedToItsLIDThroughTheStore(t *testing.T) {
	lids := func(phone types.JID) (types.JID, bool) {
		if phone == alice {
			return aliceLID, true
		}
		return types.EmptyJID, false
	}
	got, _ := convert(direct(alice, text("hi")), me, lids)
	if got.Sender != aliceLID.String() {
		t.Fatalf("the stored mapping names the sender, got %q", got.Sender)
	}
	got, _ = convert(direct(alice, text("hi")), me, noLIDs)
	if got.Sender != alice.String() {
		t.Fatalf("without a LID the phone JID names the sender, got %q", got.Sender)
	}
}

func TestTheDevicePartIsDropped(t *testing.T) {
	device := types.NewADJID("222222222222222", 0, 3)
	device.Server = types.HiddenUserServer
	got, _ := convert(direct(device, text("hi")), me, noLIDs)
	if got.Sender != aliceLID.String() {
		t.Fatalf("a person is one id whatever device they write from, got %q", got.Sender)
	}
}

func TestOwnStatusBroadcastAndNewsletterMessagesAreDropped(t *testing.T) {
	own := direct(alice, text("hi"))
	own.Info.IsFromMe = true
	status := direct(types.StatusBroadcastJID, text("hi"))
	broadcast := direct(types.NewJID("123", types.BroadcastServer), text("hi"))
	newsletter := direct(types.NewJID("123", types.NewsletterServer), text("hi"))
	fromBot := direct(botLID, text("hi"))
	for name, evt := range map[string]*events.Message{
		"own": own, "status": status, "broadcast": broadcast, "newsletter": newsletter, "bot": fromBot,
	} {
		if _, ok := convert(evt, me, noLIDs); ok {
			t.Errorf("%s message is dropped", name)
		}
	}
}

func TestReactionsEditsAndPollVotesAreDropped(t *testing.T) {
	for name, message := range map[string]*waE2E.Message{
		"reaction": {ReactionMessage: &waE2E.ReactionMessage{Text: proto.String("👍")}},
		"edit":     {ProtocolMessage: &waE2E.ProtocolMessage{Type: waE2E.ProtocolMessage_MESSAGE_EDIT.Enum()}},
		"vote":     {PollUpdateMessage: &waE2E.PollUpdateMessage{}},
	} {
		if _, ok := convert(direct(alice, message), me, noLIDs); ok {
			t.Errorf("%s is no message to answer", name)
		}
	}
}

func TestACaptionIsTextWithMediaAndOtherMediaIsOther(t *testing.T) {
	image := &waE2E.Message{ImageMessage: &waE2E.ImageMessage{Caption: proto.String("Đây là hoá đơn")}}
	got, _ := convert(direct(alice, image), me, noLIDs)
	if got.Type != "TEXT" || got.Text != "Đây là hoá đơn" || !got.Media {
		t.Fatalf("a caption is text that came with media, got %+v", got)
	}
	voice := &waE2E.Message{AudioMessage: &waE2E.AudioMessage{}}
	got, ok := convert(direct(alice, voice), me, noLIDs)
	if !ok || got.Type != "OTHER" || got.Text != "" {
		t.Fatalf("a voice note is other, got %+v", got)
	}
}

func TestAGroupMessageAddressesTheBotOnlyWhenItIsMentionedOrRepliedTo(t *testing.T) {
	plain, _ := convert(inGroup(aliceLID, text("chào mọi người")), me, noLIDs)
	if plain.Addressed {
		t.Fatal("a group message that names nobody does not address the bot")
	}
	mention := extended("@111111111111111 lịch họp tuần này?",
		&waE2E.ContextInfo{MentionedJID: []string{botLID.String()}})
	got, _ := convert(inGroup(aliceLID, mention), me, noLIDs)
	if !got.Addressed || got.Text != "lịch họp tuần này?" || got.Chat != team.String() || !got.Group {
		t.Fatalf("a mention of the bot's LID addresses it and is removed, got %+v", got)
	}
	byPhone := extended("hỏi @84900000001 nhé",
		&waE2E.ContextInfo{MentionedJID: []string{botPhone.String()}})
	got, _ = convert(inGroup(aliceLID, byPhone), me, noLIDs)
	if !got.Addressed || got.Text != "hỏi nhé" {
		t.Fatalf("a mention of the bot's phone addresses it, got %+v", got)
	}
	other := extended("@84900000003 xem giúp",
		&waE2E.ContextInfo{MentionedJID: []string{"84900000003@s.whatsapp.net"}})
	got, _ = convert(inGroup(aliceLID, other), me, noLIDs)
	if got.Addressed {
		t.Fatal("a mention of someone else does not address the bot")
	}
	reply := extended("còn gì nữa không?", &waE2E.ContextInfo{
		StanzaID: proto.String("BOT1"), Participant: proto.String(botLID.String())})
	got, _ = convert(inGroup(aliceLID, reply), me, noLIDs)
	if !got.Addressed || got.RepliesTo != "BOT1" {
		t.Fatalf("a reply to the bot addresses it, got %+v", got)
	}
}

func TestAReplyNamesTheQuotedMessage(t *testing.T) {
	reply := extended("ý em là cái này", &waE2E.ContextInfo{
		StanzaID: proto.String("Q1"), Participant: proto.String(aliceLID.String())})
	got, _ := convert(direct(aliceLID, reply), me, noLIDs)
	if got.RepliesTo != "Q1" {
		t.Fatalf("the quoted message is named, got %q", got.RepliesTo)
	}
}

func TestValidPhone(t *testing.T) {
	for phone, want := range map[string]bool{
		"84900000001": true, "14155550123": true, "0900000001": false, "+84900000001": false,
		"8490": false, "8490000000000000": false, "84 900000001": false,
	} {
		if validPhone(phone) != want {
			t.Errorf("validPhone(%q) = %v", phone, !want)
		}
	}
}
