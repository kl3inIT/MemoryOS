package main

import (
	"bytes"
	"encoding/json"
	"strings"
	"sync"
	"testing"
	"time"
)

type lines struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (l *lines) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.buf.Write(p)
}

func (l *lines) replies(t *testing.T, want int) []reply {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for {
		l.mu.Lock()
		text := l.buf.String()
		l.mu.Unlock()
		var got []reply
		for _, line := range strings.Split(strings.TrimSpace(text), "\n") {
			if line == "" {
				continue
			}
			var r reply
			if err := json.Unmarshal([]byte(line), &r); err != nil {
				t.Fatalf("each line is one JSON object: %q", line)
			}
			got = append(got, r)
		}
		if len(got) >= want || time.Now().After(deadline) {
			return got
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestACommandThatCannotBeReadOrIsUnknownIsRefusedByCode(t *testing.T) {
	sink := &lines{}
	out := newOutput(sink)
	held := newBots(nil, out, newLogger(&bytes.Buffer{}, "ERROR"))
	serve(strings.NewReader("not json\n{\"id\":7,\"op\":\"teleport\"}\n{\"id\":8,\"op\":\"send\",\"bot\":\"b\"}\n"), out, held)
	got := sink.replies(t, 3)
	codes := map[int64]string{}
	for _, r := range got {
		if r.OK {
			t.Fatalf("nothing here succeeds: %+v", r)
		}
		codes[r.ID] = r.Error
	}
	if codes[0] != "INVALID" || codes[7] != "INVALID" || codes[8] != "UNKNOWN_BOT" {
		t.Fatalf("unexpected codes %v", codes)
	}
}

func TestServingEndsAtShutdownWithoutReadingFurther(t *testing.T) {
	sink := &lines{}
	out := newOutput(sink)
	held := newBots(nil, out, newLogger(&bytes.Buffer{}, "ERROR"))
	serve(strings.NewReader("{\"op\":\"shutdown\"}\n{\"id\":9,\"op\":\"teleport\"}\n"), out, held)
	time.Sleep(50 * time.Millisecond)
	if got := sink.replies(t, 0); len(got) != 0 {
		t.Fatalf("no command after shutdown runs, got %+v", got)
	}
}

func TestAnAckReleasesTheMessageItSettles(t *testing.T) {
	sink := &lines{}
	held := newBots(nil, newOutput(sink), newLogger(&bytes.Buffer{}, "ERROR"))
	done := make(chan struct{})
	go func() {
		held.await(41, "bot", inbound{ID: "M1", Type: "TEXT"})
		close(done)
	}()
	deadline := time.Now().Add(2 * time.Second)
	for !strings.Contains(func() string { sink.mu.Lock(); defer sink.mu.Unlock(); return sink.buf.String() }(), `"seq":41`) {
		if time.Now().After(deadline) {
			t.Fatal("the message is written before its ack is awaited")
		}
		time.Sleep(5 * time.Millisecond)
	}
	held.ack(41)
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("the ack releases the handler")
	}
}
