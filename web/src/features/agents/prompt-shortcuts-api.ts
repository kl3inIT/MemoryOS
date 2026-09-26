import { useQuery, type QueryClient } from "@tanstack/react-query";
import { useMemo } from "react";
import {
  getChatPromptShortcutPreferencesOptions,
  getChatPromptShortcutPreferencesQueryKey,
  listChatPromptShortcutsOptions,
  listChatPromptShortcutsQueryKey,
  listPublicChatPromptShortcutsQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { PromptShortcut } from "@/lib/hey-api/types.gen";

/**
 * A prompt shortcut as the API sends it. The published contract marks every field optional, although the API
 * always sends them; the view is narrowed once here.
 */
export function shortcutOf({
  id,
  revision,
  name = "",
  content = "",
  active = true,
  isPublic = false,
  hidden = false,
}: PromptShortcut) {
  if (id === undefined || revision === undefined) {
    throw new TypeError("A prompt shortcut carries its id and revision.");
  }
  return { id, revision, name, content, active, isPublic, hidden };
}
export type Shortcut = ReturnType<typeof shortcutOf>;

/** Refreshes every read of prompt shortcuts: the actor's, the public ones and the actor's preference. */
export function invalidateShortcuts(cache: QueryClient) {
  return Promise.all([
    cache.invalidateQueries({ queryKey: listChatPromptShortcutsQueryKey() }),
    cache.invalidateQueries({ queryKey: listPublicChatPromptShortcutsQueryKey() }),
    cache.invalidateQueries({ queryKey: getChatPromptShortcutPreferencesQueryKey() }),
  ]);
}

export function useShortcutPreferences() {
  return useQuery({
    ...getChatPromptShortcutPreferencesOptions(),
    select: (preferences) => ({ enabled: preferences.enabled ?? true }),
  });
}

export function useShortcuts(includeHidden: boolean) {
  return useQuery({
    ...listChatPromptShortcutsOptions({ query: { includeHidden } }),
    select: (views) => views.map(shortcutOf),
  });
}

/** Shortcuts the composer offers: enabled by the actor, active and not hidden. */
export function useComposerShortcuts() {
  const preferences = useShortcutPreferences();
  const shortcuts = useShortcuts(false);
  const enabled = preferences.data?.enabled === true;
  const items = useMemo(
    () => (shortcuts.data ?? []).filter((shortcut) => shortcut.active && !shortcut.hidden),
    [shortcuts.data],
  );
  return { enabled, items };
}

/** Vietnamese users often type without diacritics: "/tom tat" finds "Tóm tắt hợp đồng". */
function fold(value: string) {
  return value
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .replace(/[đĐ]/g, "d")
    .toLocaleLowerCase("vi");
}

export function matchingShortcuts(items: Shortcut[], query: string) {
  const folded = fold(query.trim());
  return folded ? items.filter((shortcut) => fold(shortcut.name).includes(folded)) : items;
}
