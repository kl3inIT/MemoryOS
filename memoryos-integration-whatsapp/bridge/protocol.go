package main

import (
	"encoding/json"
	"io"
	"sync"
)

// A command the JVM writes, one JSON object a line. Only the fields of its op are set.
type command struct {
	ID     int64    `json:"id"`
	Op     string   `json:"op"`
	Bot    string   `json:"bot,omitempty"`
	Phone  string   `json:"phone,omitempty"`
	Chat   string   `json:"chat,omitempty"`
	Text   string   `json:"text,omitempty"`
	Quote  *quote   `json:"quote,omitempty"`
	On     bool     `json:"on,omitempty"`
	Sender string   `json:"sender,omitempty"`
	IDs    []string `json:"ids,omitempty"`
	// The event an ack settles.
	Seq int64 `json:"seq,omitempty"`
}

// The message a reply quotes: its id, who wrote it and its text, which WhatsApp shows above the reply.
type quote struct {
	ID     string `json:"id"`
	Sender string `json:"sender"`
	Text   string `json:"text"`
}

// The answer to one command, by its id. Error is a stable code, never a library's text.
type reply struct {
	ID     int64  `json:"id"`
	OK     bool   `json:"ok"`
	Error  string `json:"error,omitempty"`
	Result any    `json:"result,omitempty"`
}

// What happened to a bot. Seq numbers every event; a message waits for the JVM's ack of its seq.
type event struct {
	Event     string   `json:"event"`
	Seq       int64    `json:"seq"`
	Bot       string   `json:"bot"`
	Code      string   `json:"code,omitempty"`
	ExpiresAt string   `json:"expiresAt,omitempty"`
	JID       string   `json:"jid,omitempty"`
	LID       string   `json:"lid,omitempty"`
	Name      string   `json:"name,omitempty"`
	Reason    string   `json:"reason,omitempty"`
	Chat      string   `json:"chat,omitempty"`
	Message   *inbound `json:"message,omitempty"`
}

// A group the bot is in, with the people in it.
type group struct {
	Chat         string        `json:"chat"`
	Name         string        `json:"name,omitempty"`
	Participants []participant `json:"participants"`
}

// A person in a group, known by their LID where WhatsApp gives one.
type participant struct {
	ID    string `json:"id"`
	Phone string `json:"phone,omitempty"`
}

// One writer for stdout, so replies and events never interleave within a line.
type output struct {
	mu      sync.Mutex
	encoder *json.Encoder
}

func newOutput(w io.Writer) *output {
	encoder := json.NewEncoder(w)
	encoder.SetEscapeHTML(false)
	return &output{encoder: encoder}
}

func (o *output) write(value any) {
	o.mu.Lock()
	defer o.mu.Unlock()
	// A broken stdout means the JVM is gone; stdin closing ends the process.
	_ = o.encoder.Encode(value)
}
