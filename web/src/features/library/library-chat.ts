import type { ComponentType } from "react";
import type { LibraryFile } from "./library";

/**
 * What the library offers of Chat. The library depends on no Chat module, as the backend `library` module does
 * not depend on `chat` (ADR 0015), so whoever composes the page hands these in; without them the library offers
 * only its own commands.
 */
export type LibraryChat = {
  /**
   * Opens a conversation titled `title` with these uploads attached, and sends `question` once it is open; an
   * empty question sends nothing.
   */
  ask: (
    request: { question: string; title: string; attach: readonly string[] },
    signal: AbortSignal,
  ) => Promise<void>;
  /** The model a question asked from the file preview goes to. */
  ModelPicker: ComponentType<{ disabled: boolean }>;
  /** Adds files to one of the caller's Projects; open while `files` is set. */
  AddToProjectDialog: ComponentType<{
    files: readonly LibraryFile[] | undefined;
    onOpenChange: (open: boolean) => void;
    onAdded?: (projectName: string) => void;
  }>;
  /** Takes an upload out of a Project without deleting it. */
  removeFromProject: (projectId: string, fileId: string, signal: AbortSignal) => Promise<void>;
  /** How long conversations are kept, shown beside what the library stores. */
  RetentionSection: ComponentType;
};
