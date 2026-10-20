package main

import (
	"reflect"
	"testing"
	"time"

	"go.mau.fi/whatsmeow/types"
)

func TestAGroupListsEveryoneButTheBotByLIDAndPhone(t *testing.T) {
	own := types.NewADJID("84900000001", 0, 12)
	ownLID := types.NewJID("111111111111111", types.HiddenUserServer)
	info := &types.GroupInfo{
		JID:       types.NewJID("120363000000000001", types.GroupServer),
		GroupName: types.GroupName{Name: "Nhân sự"},
		Participants: []types.GroupParticipant{
			// The bot, by its LID with its number beside it.
			{JID: ownLID, LID: ownLID, PhoneNumber: types.NewJID("84900000001", types.DefaultUserServer)},
			// A person whose LID WhatsApp gives.
			{JID: types.NewJID("84911111111", types.DefaultUserServer),
				LID: types.NewJID("222222222222222", types.HiddenUserServer)},
			// A person known by number alone.
			{JID: types.NewJID("84922222222", types.DefaultUserServer)},
		},
	}

	got := groupOf(info, &own, ownLID)

	want := []participant{
		{ID: "222222222222222@lid", Phone: "84911111111@s.whatsapp.net"},
		{ID: "84922222222@s.whatsapp.net"},
	}
	if got.Name != "Nhân sự" || !reflect.DeepEqual(got.Participants, want) {
		t.Fatalf("got %+v, want participants %+v", got, want)
	}
}

func TestAGroupIsKeptAMinuteUntilWhatsAppChangesItsPeople(t *testing.T) {
	held := &bot{groups: map[string]cachedGroup{}}
	chat := "120363000000000001@g.us"
	if _, ok := held.cached(chat); ok {
		t.Fatal("a group never read is not kept")
	}
	held.keep(chat, group{Chat: chat, Name: "Nhân sự"})
	if got, ok := held.cached(chat); !ok || got.Name != "Nhân sự" {
		t.Fatalf("a group just read is kept, got %+v %v", got, ok)
	}
	held.groups[chat] = cachedGroup{group: group{Chat: chat}, at: time.Now().Add(-groupFresh)}
	if _, ok := held.cached(chat); ok {
		t.Fatal("a group read a minute ago is read again")
	}
	held.keep(chat, group{Chat: chat})
	held.forgetGroup(chat)
	if _, ok := held.cached(chat); ok {
		t.Fatal("a group whose people changed is read again")
	}
}
