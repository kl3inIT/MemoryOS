import { createContext, use, type ClipboardEvent } from "react";

/** Onyx's Collapse Large Pastes threshold: more than this many lines or characters becomes a tile. */
const TILE_LINES = 3;
const TILE_CHARACTERS = 200;
/** The server's limit on a message's text (`ChatCommand`), which the joined text must respect. */
export const MESSAGE_TEXT_LIMIT = 32000;

export type PasteTile = { id: string; text: string };

export type PasteTiles = {
  tiles: readonly PasteTile[];
  /** The member turned on Collapse Large Pastes; tiles already on the draft stay either way. */
  enabled: boolean;
  /** The typed text with the tiles appended is longer than a message may be. */
  overLimit: boolean;
  add: (text: string) => void;
  update: (id: string, text: string) => void;
  remove: (id: string) => void;
  /**
   * Sends the draft with the tiles' text appended, in paste order. Returns false when there is no tile, so
   * assistant-ui's own send runs instead.
   */
  send: () => boolean;
};

export const PasteTilesContext = createContext<PasteTiles | undefined>(undefined);

/** The composer's paste tiles, or nothing outside the Chat composer. */
export function usePasteTiles(): PasteTiles | undefined {
  return use(PasteTilesContext);
}

/** Lines of a paste; one trailing line break does not start another line. */
export function lineCount(text: string) {
  return text.replace(/\r?\n$/, "").split(/\r\n|\r|\n/).length;
}

/** The message text: what was typed, then each tile, separated by a blank line and never wrapped. */
export function joinedText(typed: string, tiles: readonly PasteTile[]) {
  return [typed.trimEnd(), ...tiles.map((tile) => tile.text)]
    .filter((part) => part.trim() !== "")
    .join("\n\n");
}

/** The field's paste handler: a large text paste becomes a tile; files and short text are left alone. */
export function useLargePaste() {
  const pastes = usePasteTiles();
  return (event: ClipboardEvent<HTMLTextAreaElement>) => {
    if (!pastes?.enabled || (event.clipboardData?.files.length ?? 0) > 0) return;
    const text = event.clipboardData?.getData("text/plain") ?? "";
    if (text.length <= TILE_CHARACTERS && lineCount(text) <= TILE_LINES) return;
    event.preventDefault();
    pastes.add(text);
  };
}
