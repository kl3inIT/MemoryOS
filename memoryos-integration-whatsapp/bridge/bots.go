package main

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
	"google.golang.org/protobuf/proto"
)

// How long a message waits for the JVM to claim it before WhatsApp is acknowledged anyway.
const ackWait = 20 * time.Second

// A failure the JVM can act on, by code.
type failure string

func (f failure) Error() string { return string(f) }

const (
	errUnknownBot  failure = "UNKNOWN_BOT"
	errInvalid     failure = "INVALID"
	errNotLinked   failure = "NOT_LINKED"
	errUnavailable failure = "UNAVAILABLE"
)

// Every WhatsApp bot of the process, one whatsmeow client each.
type bots struct {
	container *sqlstore.Container
	out       *output
	log       waLog.Logger
	seq       atomic.Int64

	mu   sync.Mutex
	held map[string]*bot

	acksMu sync.Mutex
	acks   map[int64]chan struct{}
}

// One bot: the number it must be, its client, and the context that ends with it.
type bot struct {
	id     string
	phone  string
	client *whatsmeow.Client
	cancel context.CancelFunc
}

func newBots(container *sqlstore.Container, out *output, log waLog.Logger) *bots {
	return &bots{container: container, out: out, log: log, held: map[string]*bot{}, acks: map[int64]chan struct{}{}}
}

func (b *bots) emit(e event) int64 {
	e.Seq = b.seq.Add(1)
	b.out.write(e)
	return e.Seq
}

// Opens a bot: connects its session, or starts pairing when its number has none.
func (b *bots) open(ctx context.Context, id, phone string) (map[string]any, error) {
	if id == "" || !validPhone(phone) {
		return nil, errInvalid
	}
	b.close(id)
	device, err := b.device(ctx, phone)
	if err != nil {
		return nil, err
	}
	client := whatsmeow.NewClient(device, b.log.Sub(id))
	client.SynchronousAck = true
	client.ManualHistorySyncDownload = true
	client.EnableAutoReconnect = true
	botCtx, cancel := context.WithCancel(context.Background())
	held := &bot{id: id, phone: phone, client: client, cancel: cancel}
	client.AddEventHandler(func(evt any) { b.handle(botCtx, held, evt) })
	pairing := device.ID == nil
	if pairing {
		qr, err := client.GetQRChannel(botCtx)
		if err != nil {
			cancel()
			b.log.Warnf("bot %s: the pairing could not start: %v", id, err)
			return nil, errUnavailable
		}
		go b.pair(held, qr)
	}
	if err := client.ConnectContext(botCtx); err != nil {
		cancel()
		client.Disconnect()
		b.log.Warnf("bot %s: WhatsApp could not be reached: %v", id, err)
		return nil, errUnavailable
	}
	b.mu.Lock()
	b.held[id] = held
	b.mu.Unlock()
	return map[string]any{"pairing": pairing}, nil
}

// The session of a number, or a new device for it.
func (b *bots) device(ctx context.Context, phone string) (*store.Device, error) {
	devices, err := b.container.GetAllDevices(ctx)
	if err != nil {
		b.log.Warnf("the sessions could not be read: %v", err)
		return nil, errUnavailable
	}
	for _, device := range devices {
		if device.ID != nil && device.ID.User == phone {
			return device, nil
		}
	}
	return b.container.NewDevice(), nil
}

// Hands each QR code to the JVM until the phone scans one or WhatsApp ends the pairing.
func (b *bots) pair(held *bot, qr <-chan whatsmeow.QRChannelItem) {
	for item := range qr {
		switch item.Event {
		case whatsmeow.QRChannelEventCode:
			b.emit(event{Event: "qr", Bot: held.id, Code: item.Code,
				ExpiresAt: time.Now().Add(item.Timeout).UTC().Format(time.RFC3339)})
		case whatsmeow.QRChannelSuccess.Event:
			// PairSuccess carries the number; the code is no longer shown.
		case whatsmeow.QRChannelClientOutdated.Event:
			b.emit(event{Event: "pairingEnded", Bot: held.id, Reason: "OUTDATED"})
		default:
			b.emit(event{Event: "pairingEnded", Bot: held.id, Reason: "TIMEOUT"})
		}
	}
}

func (b *bots) handle(ctx context.Context, held *bot, evt any) {
	client := held.client
	switch e := evt.(type) {
	case *events.PairSuccess:
		if e.ID.User != held.phone {
			// Another number scanned the code: it must not become this Tenant's bot.
			logoutCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			if err := client.Logout(logoutCtx); err != nil {
				client.Disconnect()
				_ = client.Store.Delete(logoutCtx)
			}
			b.emit(event{Event: "wrongNumber", Bot: held.id, JID: e.ID.ToNonAD().String()})
			return
		}
		b.emit(event{Event: "paired", Bot: held.id, JID: e.ID.ToNonAD().String(), LID: e.LID.ToNonAD().String(),
			Name: e.BusinessName})
	case *events.Connected:
		b.emit(event{Event: "connected", Bot: held.id, JID: jid(client.Store.ID), LID: client.Store.GetLID().String(),
			Name: client.Store.PushName})
	case *events.Disconnected:
		b.emit(event{Event: "disconnected", Bot: held.id})
	case *events.LoggedOut:
		b.emit(event{Event: "loggedOut", Bot: held.id, Reason: e.Reason.String()})
	case *events.StreamReplaced:
		b.emit(event{Event: "replaced", Bot: held.id})
	case *events.TemporaryBan:
		b.emit(event{Event: "banned", Bot: held.id, Reason: e.Code.String(),
			ExpiresAt: time.Now().Add(e.Expire).UTC().Format(time.RFC3339)})
	case *events.ClientOutdated:
		b.emit(event{Event: "outdated", Bot: held.id})
	case *events.JoinedGroup:
		b.emit(event{Event: "joined", Bot: held.id, Chat: e.JID.String(), Name: e.Name})
	case *events.Message:
		me := self{phone: phoneJID(client.Store.ID), lid: client.Store.GetLID()}
		message, ok := convert(e, me, func(phone types.JID) (types.JID, bool) {
			lid, err := client.Store.LIDs.GetLIDForPN(ctx, phone)
			return lid, err == nil && !lid.IsEmpty()
		})
		if !ok {
			return
		}
		b.await(b.seq.Add(1), held.id, message)
	}
}

// Writes a message and holds the handler, and so WhatsApp's acknowledgement, until the JVM claimed it. The wait is
// registered before the line is written, so an ack cannot outrun it.
func (b *bots) await(seq int64, botID string, message inbound) {
	done := make(chan struct{})
	b.acksMu.Lock()
	b.acks[seq] = done
	b.acksMu.Unlock()
	b.out.write(event{Event: "message", Seq: seq, Bot: botID, Message: &message})
	select {
	case <-done:
	case <-time.After(ackWait):
		b.log.Warnf("message %d was not claimed in time", seq)
	}
	b.acksMu.Lock()
	delete(b.acks, seq)
	b.acksMu.Unlock()
}

func (b *bots) ack(seq int64) {
	b.acksMu.Lock()
	done, ok := b.acks[seq]
	if ok {
		delete(b.acks, seq)
	}
	b.acksMu.Unlock()
	if ok {
		close(done)
	}
}

func (b *bots) find(id string) (*bot, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	held, ok := b.held[id]
	if !ok {
		return nil, errUnknownBot
	}
	return held, nil
}

// Disconnects a bot and keeps its session.
func (b *bots) close(id string) {
	b.mu.Lock()
	held, ok := b.held[id]
	delete(b.held, id)
	b.mu.Unlock()
	if ok {
		held.client.Disconnect()
		held.cancel()
	}
}

// Logs a bot's device out of WhatsApp and deletes its session, opened or not.
func (b *bots) logout(ctx context.Context, id, phone string) error {
	if held, err := b.find(id); err == nil && held.client.Store.ID != nil {
		err := held.client.Logout(ctx)
		b.close(id)
		if err == nil {
			return nil
		}
	} else {
		b.close(id)
	}
	if !validPhone(phone) {
		return errInvalid
	}
	devices, err := b.container.GetAllDevices(ctx)
	if err != nil {
		return errUnavailable
	}
	for _, device := range devices {
		if device.ID != nil && device.ID.User == phone {
			if err := device.Delete(ctx); err != nil {
				return errUnavailable
			}
		}
	}
	return nil
}

func (b *bots) send(ctx context.Context, id, chat, text string, quoted *quote) (map[string]any, error) {
	held, err := b.find(id)
	if err != nil {
		return nil, err
	}
	to, err := types.ParseJID(chat)
	if err != nil || text == "" {
		return nil, errInvalid
	}
	message := &waE2E.Message{Conversation: proto.String(text)}
	if quoted != nil && quoted.ID != "" {
		message = &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{
			Text: proto.String(text),
			ContextInfo: &waE2E.ContextInfo{
				StanzaID:      proto.String(quoted.ID),
				Participant:   proto.String(quoted.Sender),
				QuotedMessage: &waE2E.Message{Conversation: proto.String(quoted.Text)},
			},
		}}
	}
	sent, err := held.client.SendMessage(ctx, to, message)
	if err != nil {
		return nil, sendFailure(err)
	}
	return map[string]any{"messageId": sent.ID}, nil
}

func (b *bots) typing(ctx context.Context, id, chat string, on bool) error {
	held, err := b.find(id)
	if err != nil {
		return err
	}
	to, err := types.ParseJID(chat)
	if err != nil {
		return errInvalid
	}
	state := types.ChatPresencePaused
	if on {
		state = types.ChatPresenceComposing
	}
	if err := held.client.SendChatPresence(ctx, to, state, types.ChatPresenceMediaText); err != nil {
		return sendFailure(err)
	}
	return nil
}

func (b *bots) read(ctx context.Context, id, chat, sender string, ids []string) error {
	held, err := b.find(id)
	if err != nil {
		return err
	}
	chatJID, err := types.ParseJID(chat)
	if err != nil || len(ids) == 0 {
		return errInvalid
	}
	senderJID := chatJID
	if sender != "" {
		if senderJID, err = types.ParseJID(sender); err != nil {
			return errInvalid
		}
	}
	if err := held.client.MarkRead(ctx, ids, time.Now(), chatJID, senderJID); err != nil {
		return sendFailure(err)
	}
	return nil
}

// The groups the bot is in, each with its people.
func (b *bots) groups(ctx context.Context, id string) ([]group, error) {
	held, err := b.find(id)
	if err != nil {
		return nil, err
	}
	joined, err := held.client.GetJoinedGroups(ctx)
	if err != nil {
		return nil, sendFailure(err)
	}
	result := make([]group, 0, len(joined))
	for _, info := range joined {
		result = append(result, groupOf(info))
	}
	return result, nil
}

func (b *bots) group(ctx context.Context, id, chat string) (group, error) {
	held, err := b.find(id)
	if err != nil {
		return group{}, err
	}
	jid, err := types.ParseJID(chat)
	if err != nil || jid.Server != types.GroupServer {
		return group{}, errInvalid
	}
	info, err := held.client.GetGroupInfo(ctx, jid)
	if err != nil {
		return group{}, sendFailure(err)
	}
	return groupOf(info), nil
}

func groupOf(info *types.GroupInfo) group {
	people := make([]participant, 0, len(info.Participants))
	for _, person := range info.Participants {
		id := person.JID
		if !person.LID.IsEmpty() {
			id = person.LID
		}
		entry := participant{ID: id.ToNonAD().String()}
		if !person.PhoneNumber.IsEmpty() {
			entry.Phone = person.PhoneNumber.ToNonAD().String()
		}
		people = append(people, entry)
	}
	return group{Chat: info.JID.String(), Name: info.Name, Participants: people}
}

// Disconnects every bot, keeping their sessions.
func (b *bots) closeAll() {
	b.mu.Lock()
	ids := make([]string, 0, len(b.held))
	for id := range b.held {
		ids = append(ids, id)
	}
	b.mu.Unlock()
	for _, id := range ids {
		b.close(id)
	}
}

func sendFailure(err error) error {
	switch {
	case errors.Is(err, whatsmeow.ErrNotConnected), errors.Is(err, whatsmeow.ErrNotLoggedIn):
		return errNotLinked
	default:
		return errUnavailable
	}
}

func jid(id *types.JID) string {
	if id == nil {
		return ""
	}
	return id.ToNonAD().String()
}

func phoneJID(id *types.JID) types.JID {
	if id == nil {
		return types.EmptyJID
	}
	return id.ToNonAD()
}

// A number in international form without the plus: 8 to 15 digits, the first not 0.
func validPhone(phone string) bool {
	if len(phone) < 8 || len(phone) > 15 || phone[0] == '0' {
		return false
	}
	for _, digit := range phone {
		if digit < '0' || digit > '9' {
			return false
		}
	}
	return true
}
