package main

import (
	"regexp"
	"strings"

	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

// A message as the JVM receives it. Sender and Chat are canonical: a person by their LID where one is known, a group
// by its JID.
type inbound struct {
	ID         string `json:"id"`
	Chat       string `json:"chat"`
	Group      bool   `json:"group"`
	Sender     string `json:"sender"`
	SenderName string `json:"senderName,omitempty"`
	// TEXT or OTHER.
	Type string `json:"type"`
	Text string `json:"text"`
	// Whether the text came with media the bot does not read: an image's or a video's caption.
	Media     bool   `json:"media,omitempty"`
	Addressed bool   `json:"addressed"`
	RepliesTo string `json:"repliesTo,omitempty"`
}

// The bot's own addresses: its phone JID and its LID, without a device part.
type self struct {
	phone types.JID
	lid   types.JID
}

func (s self) is(jid types.JID) bool {
	if jid.IsEmpty() {
		return false
	}
	user := jid.ToNonAD()
	return (!s.phone.IsEmpty() && user.User == s.phone.User && user.Server == s.phone.Server) ||
		(!s.lid.IsEmpty() && user.User == s.lid.User && user.Server == s.lid.Server)
}

// Resolves a phone JID to the LID WhatsApp maps it to; ok is false when none is known.
type lidLookup func(phone types.JID) (types.JID, bool)

// Converts a message; ok is false for one the bot never answers: its own, a status, a broadcast, a newsletter, and
// anything that is no message a person wrote (a reaction, an edit, a deletion, a poll vote).
func convert(evt *events.Message, me self, lids lidLookup) (inbound, bool) {
	info := evt.Info
	if info.IsFromMe || me.is(info.Sender) {
		return inbound{}, false
	}
	switch info.Chat.Server {
	case types.DefaultUserServer, types.HiddenUserServer, types.GroupServer:
	default:
		return inbound{}, false
	}
	if info.Chat.User == "status" {
		return inbound{}, false
	}
	message := evt.Message
	if message == nil || ignored(message) {
		return inbound{}, false
	}
	sender := canonical(info.Sender, info.SenderAlt, lids)
	result := inbound{
		ID:         info.ID,
		Sender:     sender,
		SenderName: info.PushName,
		Group:      info.IsGroup,
		Type:       "OTHER",
	}
	if info.IsGroup {
		result.Chat = info.Chat.ToNonAD().String()
	} else {
		result.Chat = sender
		result.Addressed = true
	}
	text, media, context := content(message)
	if context != nil {
		if id := context.GetStanzaID(); id != "" {
			result.RepliesTo = id
		}
	}
	if info.IsGroup {
		result.Addressed = addressed(context, me)
	}
	if text != "" {
		result.Type = "TEXT"
		result.Text = strings.TrimSpace(withoutMention(text, me))
		result.Media = media
	}
	return result, true
}

// A person by their LID: the sender when WhatsApp addressed it so, the alternate address when that is the LID, the
// mapping the store holds for a phone JID, and the phone JID only when no LID is known.
func canonical(sender, alt types.JID, lids lidLookup) string {
	sender = sender.ToNonAD()
	if sender.Server == types.HiddenUserServer {
		return sender.String()
	}
	if alt = alt.ToNonAD(); alt.Server == types.HiddenUserServer {
		return alt.String()
	}
	if lids != nil {
		if lid, ok := lids(sender); ok && !lid.IsEmpty() {
			return lid.ToNonAD().String()
		}
	}
	return sender.String()
}

func ignored(message *waE2E.Message) bool {
	return message.GetReactionMessage() != nil || message.GetProtocolMessage() != nil ||
		message.GetPollUpdateMessage() != nil || message.GetEncReactionMessage() != nil ||
		message.GetKeepInChatMessage() != nil || message.GetPinInChatMessage() != nil
}

// The text a person wrote, whether it came as a media's caption, and the context that names mentions and the quoted
// message.
func content(message *waE2E.Message) (string, bool, *waE2E.ContextInfo) {
	if text := message.GetConversation(); text != "" {
		return text, false, nil
	}
	if extended := message.GetExtendedTextMessage(); extended != nil {
		return extended.GetText(), false, extended.GetContextInfo()
	}
	if image := message.GetImageMessage(); image != nil {
		return image.GetCaption(), true, image.GetContextInfo()
	}
	if video := message.GetVideoMessage(); video != nil {
		return video.GetCaption(), true, video.GetContextInfo()
	}
	if document := message.GetDocumentMessage(); document != nil {
		return document.GetCaption(), true, document.GetContextInfo()
	}
	for _, other := range []interface{ GetContextInfo() *waE2E.ContextInfo }{
		message.GetAudioMessage(), message.GetStickerMessage(), message.GetLocationMessage(),
		message.GetContactMessage(),
	} {
		if other != nil {
			if info := other.GetContextInfo(); info != nil {
				return "", false, info
			}
		}
	}
	return "", false, nil
}

// A group message addresses the bot when it mentions the bot's phone JID or LID, or replies to one of its messages.
func addressed(context *waE2E.ContextInfo, me self) bool {
	if context == nil {
		return false
	}
	for _, mentioned := range context.GetMentionedJID() {
		if jid, err := types.ParseJID(mentioned); err == nil && me.is(jid) {
			return true
		}
	}
	if context.GetStanzaID() != "" {
		if jid, err := types.ParseJID(context.GetParticipant()); err == nil && me.is(jid) {
			return true
		}
	}
	return false
}

var spaces = regexp.MustCompile(`[ \t]{2,}`)

// Removes the bot's mention, which WhatsApp writes as @ and the number of the JID it names.
func withoutMention(text string, me self) string {
	for _, jid := range []types.JID{me.phone, me.lid} {
		if jid.IsEmpty() {
			continue
		}
		text = strings.ReplaceAll(text, "@"+jid.User, "")
	}
	return spaces.ReplaceAllString(text, " ")
}
