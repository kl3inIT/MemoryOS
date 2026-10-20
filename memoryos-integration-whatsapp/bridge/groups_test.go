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
		// The bot and the two people below.
		ParticipantCount: 3,
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
	if got.Name != "Nhân sự" || !got.Complete || !reflect.DeepEqual(got.Participants, want) {
		t.Fatalf("got %+v, want participants %+v", got, want)
	}

	// A list shorter than WhatsApp's count, or a community's parent, is not everyone.
	info.ParticipantCount = 4
	if groupOf(info, &own, ownLID).Complete {
		t.Fatal("a list WhatsApp counts more people than is not complete")
	}
	info.ParticipantCount = 3
	info.IsParent = true
	if groupOf(info, &own, ownLID).Complete {
		t.Fatal("a community's parent is not complete")
	}
}

func TestAGroupIsKeptAMinuteUntilWhatsAppChangesItsPeople(t *testing.T) {
	held := &bot{groups: map[string]cachedGroup{}}
	chat := "120363000000000001@g.us"
	if _, ok, _ := held.cached(chat); ok {
		t.Fatal("a group never read is not kept")
	}
	_, _, epoch := held.cached(chat)
	held.keep(chat, group{Chat: chat, Name: "Nhân sự"}, epoch)
	if got, ok, _ := held.cached(chat); !ok || got.Name != "Nhân sự" {
		t.Fatalf("a group just read is kept, got %+v %v", got, ok)
	}
	held.groups[chat] = cachedGroup{group: group{Chat: chat}, at: time.Now().Add(-groupFresh)}
	if _, ok, _ := held.cached(chat); ok {
		t.Fatal("a group read a minute ago is read again")
	}

	// A read begun before WhatsApp reported a change is not kept after it.
	_, _, before := held.cached(chat)
	held.forgetGroup(chat)
	held.keep(chat, group{Chat: chat}, before)
	if _, ok, _ := held.cached(chat); ok {
		t.Fatal("a group read before its people changed is read again")
	}

	// A reconnection forgets every group.
	_, _, epoch = held.cached(chat)
	held.keep(chat, group{Chat: chat}, epoch)
	held.forgetGroups()
	if _, ok, _ := held.cached(chat); ok {
		t.Fatal("a group kept before a reconnection is read again")
	}
}
