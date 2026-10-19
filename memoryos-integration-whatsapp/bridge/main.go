// The MemoryOS WhatsApp bridge: every WhatsApp bot of one memoryos-channels process, as linked devices on whatsmeow.
// The JVM that starts it writes one JSON command a line to stdin and reads replies and events from stdout; logs go to
// stderr. It holds no port and writes nothing to disk: sessions are in PostgreSQL. It exits when stdin closes.
package main

import (
	"bufio"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"io"
	"os"
	"time"

	_ "github.com/jackc/pgx/v5/stdlib"
	"go.mau.fi/whatsmeow/proto/waCompanionReg"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"google.golang.org/protobuf/proto"
)

// The longest command line read: a reply's text is at most 4,096 characters, quoted text included.
const maxLine = 1 << 20

func main() {
	log := newLogger(os.Stderr, os.Getenv("MEMORYOS_WHATSAPP_LOG_LEVEL"))
	address := os.Getenv("MEMORYOS_WHATSAPP_DATABASE")
	if address == "" {
		log.Errorf("MEMORYOS_WHATSAPP_DATABASE is not set")
		os.Exit(2)
	}
	db, err := sql.Open("pgx", address)
	if err != nil {
		log.Errorf("the database address is not valid")
		os.Exit(2)
	}
	db.SetMaxOpenConns(3)
	db.SetConnMaxIdleTime(5 * time.Minute)
	container := sqlstore.NewWithDB(db, "postgres", log.Sub("Store"))
	upgradeCtx, cancel := context.WithTimeout(context.Background(), time.Minute)
	err = container.Upgrade(upgradeCtx)
	cancel()
	if err != nil {
		log.Errorf("the session store could not be prepared: %v", err)
		os.Exit(3)
	}
	store.SetOSInfo("MemoryOS", [3]uint32{1, 0, 0})
	store.DeviceProps.PlatformType = waCompanionReg.DeviceProps_DESKTOP.Enum()
	store.DeviceProps.RequireFullSync = proto.Bool(false)

	out := newOutput(os.Stdout)
	held := newBots(container, sessions{db: db}, out, log)
	go prune(held)
	serve(os.Stdin, out, held)
	held.closeAll()
	_ = container.Close()
}

// Reads commands until stdin closes. Each runs on its own goroutine, so a slow send never holds an ack.
func serve(in io.Reader, out *output, held *bots) {
	scanner := bufio.NewScanner(in)
	scanner.Buffer(make([]byte, 64*1024), maxLine)
	for scanner.Scan() {
		var cmd command
		if err := json.Unmarshal(scanner.Bytes(), &cmd); err != nil {
			out.write(reply{ID: 0, OK: false, Error: string(errInvalid)})
			continue
		}
		switch cmd.Op {
		case "ack":
			held.ack(cmd.Seq)
		case "shutdown":
			return
		default:
			go run(out, held, cmd)
		}
	}
}

func run(out *output, held *bots, cmd command) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout(cmd.Op))
	defer cancel()
	result, err := execute(ctx, held, cmd)
	if err != nil {
		var code failure
		if !errors.As(err, &code) {
			code = errUnavailable
		}
		out.write(reply{ID: cmd.ID, OK: false, Error: string(code)})
		return
	}
	out.write(reply{ID: cmd.ID, OK: true, Result: result})
}

func execute(ctx context.Context, held *bots, cmd command) (any, error) {
	switch cmd.Op {
	case "open":
		return held.open(ctx, cmd.Bot, cmd.Phone)
	case "close":
		held.close(cmd.Bot)
		return nil, nil
	case "logout":
		return nil, held.logout(ctx, cmd.Bot)
	case "send":
		return held.send(ctx, cmd.Bot, cmd.Chat, cmd.Text, cmd.Quote)
	case "typing":
		return nil, held.typing(ctx, cmd.Bot, cmd.Chat, cmd.On)
	case "read":
		return nil, held.read(ctx, cmd.Bot, cmd.Chat, cmd.Sender, cmd.IDs)
	case "groups":
		return held.groups(ctx, cmd.Bot)
	case "group":
		return held.group(ctx, cmd.Bot, cmd.Chat)
	default:
		return nil, errInvalid
	}
}

// Logs out the devices no bot names a minute after the start, once the JVM opened what it holds, and every hour after.
func prune(held *bots) {
	for wait := time.Minute; ; wait = time.Hour {
		time.Sleep(wait)
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
		held.prune(ctx)
		cancel()
	}
}

func timeout(op string) time.Duration {
	switch op {
	case "open", "logout":
		return 45 * time.Second
	default:
		return 20 * time.Second
	}
}
