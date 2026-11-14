import { useAui, useAuiState } from "@assistant-ui/react";
import { ClipboardPaste, XIcon } from "lucide-react";
import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { FormDialog } from "@/components/composites/form-dialog";
import { IconButton } from "@/components/ui/icon-button";
import { Textarea } from "@/components/ui/textarea";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useChatPreferences } from "@/features/identity/chat-preferences";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  MESSAGE_TEXT_LIMIT,
  PasteTilesContext,
  joinedText,
  lineCount,
  usePasteTiles,
  type PasteTile,
  type PasteTiles,
} from "./chat-paste-tiles-context";

const NO_TILES: readonly PasteTile[] = [];

function load(key: string): PasteTile[] {
  try {
    const saved: unknown = JSON.parse(sessionStorage.getItem(key) ?? "[]");
    return Array.isArray(saved)
      ? saved.filter(
          (tile): tile is PasteTile =>
            typeof tile?.id === "string" && typeof tile?.text === "string",
        )
      : [];
  } catch {
    return [];
  }
}

/**
 * Holds the draft's paste tiles (MEM-148). Like the typed draft (`ChatComposerDraft`) they are kept in
 * sessionStorage per actor and conversation, so a reload of the tab restores them and nothing outlives it.
 */
export function ChatPasteTiles({ children }: { children: ReactNode }) {
  const aui = useAui();
  const { actorId } = useApplicationSession();
  const remoteId = useAuiState((state) => state.threadListItem.remoteId);
  const typed = useAuiState((state) => state.composer.text);
  const enabled = useChatPreferences().data?.pasteAsTile ?? false;
  const key = `memoryos.chat.paste-tiles:${actorId}:${remoteId ?? "new"}`;
  const [state, setState] = useState<{ key: string; tiles: readonly PasteTile[] }>(() => ({
    key,
    tiles: load(key),
  }));
  // Another conversation shows its own tiles; adjusting during render avoids writing one draft's tiles to another.
  if (state.key !== key) setState({ key, tiles: load(key) });
  const tiles = state.key === key ? state.tiles : NO_TILES;

  useEffect(() => {
    try {
      if (state.tiles.length > 0) sessionStorage.setItem(state.key, JSON.stringify(state.tiles));
      else sessionStorage.removeItem(state.key);
    } catch {
      /* Draft storage is optional. */
    }
  }, [state]);

  const change = useCallback(
    (next: (tiles: readonly PasteTile[]) => readonly PasteTile[]) =>
      setState((current) => ({ ...current, tiles: next(current.tiles) })),
    [],
  );

  const value = useMemo<PasteTiles>(
    () => ({
      tiles,
      enabled,
      overLimit: tiles.length > 0 && joinedText(typed, tiles).length > MESSAGE_TEXT_LIMIT,
      add: (text) => change((current) => [...current, { id: crypto.randomUUID(), text }]),
      update: (id, text) =>
        change((current) => current.map((tile) => (tile.id === id ? { ...tile, text } : tile))),
      remove: (id) => change((current) => current.filter((tile) => tile.id !== id)),
      send: () => {
        if (tiles.length === 0) return false;
        const composer = aui.composer;
        const text = joinedText(composer.getState().text, tiles);
        if (text.length > MESSAGE_TEXT_LIMIT) return true;
        composer.setText(text);
        change(() => NO_TILES);
        void composer.send();
        return true;
      },
    }),
    [aui, change, enabled, tiles, typed],
  );

  return <PasteTilesContext value={value}>{children}</PasteTilesContext>;
}

/** One collapsed paste in the attachment row: it opens to read or edit the text, and its × takes it off. */
export function ChatPasteTile({ tile }: { tile: PasteTile }) {
  const ui = useAppTranslation();
  const pastes = usePasteTiles();
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState(tile.text);
  const lines = lineCount(tile.text);
  const linesLabel = lines === 1 ? ui("1 dòng") : ui("{{count}} dòng", { count: lines });
  return (
    <div className="relative flex w-64 max-w-full shrink-0 animate-in items-center gap-2 rounded-xl bg-muted p-2 duration-200 fade-in-0 zoom-in-95 motion-reduce:animate-none">
      {/* The dialog is portalled, but React events still bubble to the composer's form: keep its submit here. */}
      <span className="contents" onSubmit={(event) => event.stopPropagation()}>
        <FormDialog
          title={ui("Đoạn dán")}
          submitLabel={ui("Lưu")}
          open={open}
          onOpenChange={(next) => {
            if (next) setDraft(tile.text);
            setOpen(next);
          }}
          onSubmit={async () => {
            if (draft.trim() === "") pastes?.remove(tile.id);
            else pastes?.update(tile.id, draft);
          }}
          trigger={
            <button
              type="button"
              aria-label={ui("Xem đoạn dán, {{lines}}", { lines: linesLabel })}
              className="flex min-w-0 flex-1 items-center gap-2.5 rounded-lg text-start outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-surface-base">
                <ClipboardPaste aria-hidden="true" className="size-5" strokeWidth={1.5} />
              </span>
              <span className="flex min-w-0 flex-1 flex-col">
                <span className="truncate text-sm font-medium">{ui("Đoạn dán")}</span>
                <span className="truncate text-xs text-content-muted">{linesLabel}</span>
              </span>
            </button>
          }
        >
          <Textarea
            aria-label={ui("Nội dung đoạn dán")}
            variant="mono"
            rows={14}
            maxLength={MESSAGE_TEXT_LIMIT}
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            className="max-h-96 overflow-y-auto"
          />
        </FormDialog>
      </span>
      <IconButton
        aria-label={ui("Gỡ đoạn dán")}
        title={ui("Gỡ đoạn dán")}
        size="sm"
        prominence="secondary"
        className="z-10 size-6 min-h-0 min-w-0 shrink-0 p-0"
        onClick={() => pastes?.remove(tile.id)}
      >
        <XIcon className="size-3" strokeWidth={2.5} />
      </IconButton>
    </div>
  );
}

/** Said under the field when the pasted text makes the message longer than the server accepts. */
export function ChatPasteLimitAlert() {
  const ui = useAppTranslation();
  if (!usePasteTiles()?.overLimit) return null;
  return (
    <p role="alert" className="text-sm">
      {ui("Tin nhắn dài hơn 32.000 ký tự. Hãy gỡ hoặc rút gọn đoạn dán trước khi gửi.")}
    </p>
  );
}
