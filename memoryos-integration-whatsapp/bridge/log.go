package main

import (
	"encoding/json"
	"fmt"
	"io"
	"strings"
	"sync"
	"time"

	waLog "go.mau.fi/whatsmeow/util/log"
)

var levels = map[string]int{"DEBUG": 0, "INFO": 1, "WARN": 2, "ERROR": 3}

// Writes whatsmeow's log as one JSON object a line, which the JVM logs through SLF4J. Messages' text never reaches
// it: the library logs ids and states, not content, at WARN and above.
type logger struct {
	mu     *sync.Mutex
	w      io.Writer
	module string
	min    int
}

func newLogger(w io.Writer, level string) waLog.Logger {
	min, ok := levels[strings.ToUpper(level)]
	if !ok {
		min = levels["WARN"]
	}
	return &logger{mu: &sync.Mutex{}, w: w, module: "Bridge", min: min}
}

func (l *logger) write(level, format string, args ...any) {
	if levels[level] < l.min {
		return
	}
	line, _ := json.Marshal(map[string]string{
		"time":    time.Now().UTC().Format(time.RFC3339Nano),
		"level":   level,
		"module":  l.module,
		"message": fmt.Sprintf(format, args...),
	})
	l.mu.Lock()
	defer l.mu.Unlock()
	_, _ = l.w.Write(append(line, '\n'))
}

func (l *logger) Debugf(format string, args ...any) { l.write("DEBUG", format, args...) }
func (l *logger) Infof(format string, args ...any)  { l.write("INFO", format, args...) }
func (l *logger) Warnf(format string, args ...any)  { l.write("WARN", format, args...) }
func (l *logger) Errorf(format string, args ...any) { l.write("ERROR", format, args...) }

func (l *logger) Sub(module string) waLog.Logger {
	return &logger{mu: l.mu, w: l.w, module: l.module + "/" + module, min: l.min}
}
