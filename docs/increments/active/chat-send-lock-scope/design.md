# Chat send lock scope

## Requirement

Owner decision 2026-09-24 (audit item 1.7, [owner decisions](../audit-quality-fixes/owner-decisions.md)): the Chat send path must not hold its in-process session lock across network I/O. `ChatTurnService` keeps 128 striped `ReentrantLock`s keyed by session. Send held its stripe through `mcp.open`, which may refresh OAuth tokens against an authorization server, so a slow MCP or OAuth server blocked send, Stop, subscribe and delete for every session on the same stripe.

## What the lock protects

PostgreSQL, not the lock, guarantees one turn per request: `reserve` takes the owner lock, returns the earlier reservation for a repeated `requestId` and rejects a second RUNNING reply in the session. The in-process lock protects one ordering instead ([Chat contract](../../../specs/chat.md)): once a RUNNING row is visible, its `Active` run is registered and its stream buffer is open. Without it, a Stop in the window between the reservation commit and `active.put` finds no run and silently does nothing, and a subscribe finds no stream.

## Design

Send is split in two phases.

1. Under the stripe: idempotency check, admission checks, model/Web/image resolution (database and in-process only), `reserve`, `loadContext`, setup, stream open and run registration. The run now owns the permit, the model lease and, later, the MCP sessions.
2. After the stripe is released: `mcp.open`, the accepting check and dispatch. The opened tools are attached to the registered run (`Active.setup` became a volatile field written once by the sending thread before dispatch), so the run closes them when it drains.

Consequences:

- Stop during `mcp.open` cancels the registered run; execution then settles it as CANCELED without calling the model, as a Stop before the task starts always did. Send still returns the accepted pair.
- A repeated `requestId` during `mcp.open` returns the existing reservation; the browser can subscribe because the stream is already open.
- A failure of `mcp.open` or dispatch finishes the reserved turn through the run's terminal path: FAILED `CHAT_SETUP_FAILED` (MCP) or `CHAT_SUBMIT_FAILED` (dispatch), persisted with `finishAndRead`, published to the stream, then the permit and lease are released on drain. Before, an MCP failure called `finish` directly and never opened the stream; the persisted status and code are unchanged, and a subscriber now also sees the outcome. The error is still thrown to the caller, so the HTTP contract is unchanged.
- Delete during `mcp.open` marks and cancels the registered run exactly as for a running turn.

## Striped or per-session lock

The stripe stays. Once no network call runs under it, a stripe collision costs one short database transaction. Per-session locks would need a map with lifecycle and cleanup and buy nothing measurable. Removing the lock would reopen the Stop-before-registration race described above; the database cannot close it because the assistant ID is generated inside the reservation.

## Out of scope

`loadContext` (up to 201 ancestor nodes and 1 MiB) and the reservation transaction still run under the stripe. They are database work, not network I/O.
