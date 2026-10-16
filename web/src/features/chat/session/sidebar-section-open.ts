import { useState } from "react";

/**
 * Whether a home sidebar section is open, remembered per browser as LobeHub's sidebar remembers each section. Storage
 * may be blocked or cleared; the section then starts open and the choice holds for the page.
 */
export function useSidebarSectionOpen(storageKey: string) {
  const [open, setOpen] = useState(() => {
    try {
      return window.localStorage.getItem(storageKey) !== "closed";
    } catch {
      return true;
    }
  });
  const change = (next: boolean) => {
    setOpen(next);
    try {
      window.localStorage.setItem(storageKey, next ? "open" : "closed");
    } catch {
      // Storage may be blocked; the state still holds for this page.
    }
  };
  return [open, change] as const;
}
