import { useState } from "react";

/**
 * One of a few choices, remembered per browser as LobeHub keeps its sidebar's state. Storage may be blocked or
 * cleared, or hold a value from elsewhere; the fallback is then used and a change holds for the page.
 */
function useStoredChoice<const T extends string>(
  storageKey: string,
  choices: readonly T[],
  fallback: T,
) {
  const [value, setValue] = useState<T>(() => {
    try {
      const stored = window.localStorage.getItem(storageKey);
      return choices.find((choice) => choice === stored) ?? fallback;
    } catch {
      return fallback;
    }
  });
  const change = (next: T) => {
    setValue(next);
    try {
      window.localStorage.setItem(storageKey, next);
    } catch {
      // Storage may be blocked; the choice still holds for this page.
    }
  };
  return [value, change] as const;
}

const SECTION_STATES = ["open", "closed"] as const;

/** Whether a home sidebar section is open, as LobeHub's sidebar remembers each section; it starts open. */
export function useSidebarSectionOpen(storageKey: string) {
  const [state, setState] = useStoredChoice(storageKey, SECTION_STATES, "open");
  return [state === "open", (open: boolean) => setState(open ? "open" : "closed")] as const;
}

/** The row counts the assistants section offers, as LobeHub's agent page sizes. */
export const ASSISTANT_ROW_LIMITS = [5, 10, 15, 20] as const;
export type AssistantRowLimit = (typeof ASSISTANT_ROW_LIMITS)[number];
const ROW_LIMIT_CHOICES = ["5", "10", "15", "20"] as const;

/** How many assistants the home sidebar lists before "Xem thêm"; five, as LobeHub's `agentPageSize`. */
export function useAssistantRowLimit(storageKey: string) {
  const [limit, setLimit] = useStoredChoice(storageKey, ROW_LIMIT_CHOICES, "5");
  return [
    Number(limit) as AssistantRowLimit,
    (next: AssistantRowLimit) => setLimit(`${next}` as const),
  ] as const;
}
