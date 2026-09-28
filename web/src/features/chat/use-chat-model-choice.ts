import { useEffect, useState } from "react";
import { useApplicationSession } from "@/features/identity/application-session-context";
import type { MemoryOsChatTransport } from "@/features/chat/runtime/chat-transport";
import { readChatModelPreference, writeChatModelPreference } from "./chat-models";

type ModelChoice = { id?: string; fallback?: boolean };

/**
 * The model the composer sends with: the person's last choice, replaced by the server's fallback when the
 * chosen model is no longer available to them. The fallback notice survives a remount through the
 * transport, which keeps the last accepted turn; a page reload keeps only the model ID.
 */
export function useChatModelChoice(transport: MemoryOsChatTransport) {
  const { actorId } = useApplicationSession();
  const [choice, setChoice] = useState<ModelChoice>(() => {
    const accepted = transport.lastModelSelection;
    const saved = readChatModelPreference(actorId);
    if (!saved) return {};
    return {
      id: saved,
      fallback: accepted?.modelConfigurationId === saved && !!accepted.fallbackReason,
    };
  });
  // The transport is an external system: it sends with the chosen model and reports what the server accepted.
  useEffect(() => {
    transport.selectModel(choice.id);
    return transport.listenModelSelection((accepted) => {
      if (accepted.fallbackReason) {
        const next = { id: accepted.modelConfigurationId, fallback: true };
        transport.selectModel(next.id);
        writeChatModelPreference(actorId, next.id);
        setChoice(next);
      } else {
        setChoice((current) => (current.fallback ? { id: current.id } : current));
      }
    });
  }, [transport, choice.id, actorId]);
  function select(id?: string) {
    transport.selectModel(id);
    setChoice({ id });
    writeChatModelPreference(actorId, id);
  }
  return { choice, select };
}
