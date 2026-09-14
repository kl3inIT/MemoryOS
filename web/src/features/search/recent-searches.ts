const MAX_RECENT_SEARCHES = 5;

function storageKey(owner: string) {
  return `memoryos:search:recent:${owner}`;
}

/** Browser convenience only: queries stay on this device and are never sent anywhere else. */
export function readRecentSearches(owner: string | undefined): string[] {
  if (!owner) return [];
  try {
    const value: unknown = JSON.parse(localStorage.getItem(storageKey(owner)) ?? "[]");
    return Array.isArray(value)
      ? value
          .filter((item): item is string => typeof item === "string" && item.trim() !== "")
          .slice(0, MAX_RECENT_SEARCHES)
      : [];
  } catch {
    return [];
  }
}

export function rememberRecentSearch(owner: string | undefined, query: string): string[] {
  const trimmed = query.trim();
  if (!owner || !trimmed) return readRecentSearches(owner);
  const next = [
    trimmed,
    ...readRecentSearches(owner).filter(
      (item) => item.toLocaleLowerCase() !== trimmed.toLocaleLowerCase(),
    ),
  ].slice(0, MAX_RECENT_SEARCHES);
  try {
    localStorage.setItem(storageKey(owner), JSON.stringify(next));
  } catch {
    // Disabled or full browser storage must not prevent searching.
  }
  return next;
}

export function clearRecentSearches(owner: string | undefined) {
  if (!owner) return;
  try {
    localStorage.removeItem(storageKey(owner));
  } catch {
    // Nothing to clear when storage is unavailable.
  }
}
