package main

import (
	"context"
	"database/sql"
	"errors"

	"github.com/jackc/pgx/v5/pgconn"
)

// Which device each bot linked, in channel_devices (V192). A session is found by its bot, never by its number, so a
// bot added later for the same number links afresh; a number links one bot.
type sessions struct {
	db *sql.DB
}

var errNumberInUse = errors.New("the number already links another bot")

// The device the bot linked; empty when it has none.
func (s sessions) device(ctx context.Context, bot string) (string, error) {
	var jid string
	err := s.db.QueryRowContext(ctx, `SELECT device_jid FROM channel_devices WHERE connection_id = $1`, bot).Scan(&jid)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return jid, err
}

// Records the device a bot's own number linked, replacing one it linked before. errNumberInUse when the number links
// another bot; a bot removed meanwhile cannot be recorded either.
func (s sessions) link(ctx context.Context, bot, jid, phone string) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO channel_devices(connection_id, device_jid, phone) VALUES ($1, $2, $3)
		ON CONFLICT (connection_id) DO UPDATE SET device_jid = EXCLUDED.device_jid, phone = EXCLUDED.phone,
			linked_at = CURRENT_TIMESTAMP`, bot, jid, phone)
	var refused *pgconn.PgError
	if errors.As(err, &refused) && refused.ConstraintName == "channel_devices_phone_key" {
		return errNumberInUse
	}
	return err
}

// Forgets the device a bot linked.
func (s sessions) unlink(ctx context.Context, bot string) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM channel_devices WHERE connection_id = $1`, bot)
	return err
}

// Every device a bot names.
func (s sessions) named(ctx context.Context) (map[string]bool, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT device_jid FROM channel_devices`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	named := map[string]bool{}
	for rows.Next() {
		var jid string
		if err := rows.Scan(&jid); err != nil {
			return nil, err
		}
		named[jid] = true
	}
	return named, rows.Err()
}
