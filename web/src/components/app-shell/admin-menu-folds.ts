import { adminGroups, type AdminGroup } from "@/components/app-shell/admin-pages";

const STORAGE_KEY = "memoryos:admin-menu:folded";

/**
 * The administration sections this person folded, or undefined when they never folded or opened one. Browser
 * convenience only: it stays on this device.
 */
export function readFoldedGroups(): ReadonlySet<AdminGroup> | undefined {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === null) return undefined;
    const value: unknown = JSON.parse(stored);
    if (!Array.isArray(value)) return undefined;
    return new Set(adminGroups.map((group) => group.id).filter((id) => value.includes(id)));
  } catch {
    return undefined;
  }
}

export function rememberFoldedGroups(folded: ReadonlySet<AdminGroup>) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify([...folded]));
  } catch {
    // Disabled or full browser storage must not prevent folding a section.
  }
}
