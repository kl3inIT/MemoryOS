import { useEffect } from "react";
import { useNavigate } from "@tanstack/react-router";
import { useChatPreferences } from "./chat-preferences";

let consumed = false;

/** Whether this tab's document was opened at the app root, as opposed to reaching it through New chat. */
function openedAtRoot() {
  try {
    const entry = performance.getEntriesByType("navigation")[0];
    return entry ? new URL(entry.name).pathname === "/" : false;
  } catch {
    return false;
  }
}

/**
 * Onyx "Default App Mode": when the app is opened at its root, a member who starts in Search is taken there once.
 * New chat and every later visit to the root stay in Chat.
 */
export function StartPageRedirect() {
  const navigate = useNavigate();
  const startPage = useChatPreferences().data?.startPage;
  useEffect(() => {
    if (!startPage || consumed) return;
    consumed = true;
    if (startPage === "SEARCH" && openedAtRoot()) void navigate({ to: "/search", replace: true });
  }, [startPage, navigate]);
  return null;
}
