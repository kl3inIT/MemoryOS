import { useState } from "react";
import { useApplicationSession } from "@/features/identity/application-session-context";
import type { LibraryLayout } from "./library-toolbar";

const storageKey = (owner: string) => `memoryos:library-layout:${owner}`;

function readLayout(owner: string): LibraryLayout {
  try {
    return localStorage.getItem(storageKey(owner)) === "grid" ? "grid" : "list";
  } catch {
    return "list";
  }
}

/**
 * Rows or cards, kept for the person in this browser so the choice survives a change of tab or a visit to another
 * page. A browser preference only; storage that is disabled or full leaves the choice for the visit.
 */
export function useLibraryLayout(): [LibraryLayout, (next: LibraryLayout) => void] {
  const owner = useApplicationSession().actorId;
  const [layout, setLayout] = useState(() => readLayout(owner));
  return [
    layout,
    (next) => {
      setLayout(next);
      try {
        localStorage.setItem(storageKey(owner), next);
      } catch {
        // Disabled or full browser storage must not prevent changing the layout.
      }
    },
  ];
}
