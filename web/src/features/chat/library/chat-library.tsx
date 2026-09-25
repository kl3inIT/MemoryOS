import { useMemo, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useApplicationSession } from "@/features/identity/application-session-context";
import type { LibraryChat } from "@/features/library/library-chat";
import { LibraryPage } from "@/features/library/library-page";
import { StoragePage } from "@/features/library/storage-page";
import { chatSessionsKey, newChatSession } from "@/features/chat/chat-api";
import { ChatModelPicker } from "@/features/chat/chat-model-picker";
import { readChatModelPreference, writeChatModelPreference } from "@/features/chat/chat-models";
import { ChatAddToProjectDialog } from "@/features/chat/projects/chat-add-to-project";
import { removeFromProject } from "@/features/chat/projects/chat-project-files";
import { ChatRetentionSection } from "@/features/chat/settings/chat-retention-section";

/**
 * The library as the application shows it: the library's own page with what Chat adds to it. The library does not
 * depend on Chat (ADR 0015), so Chat composes the two here.
 */
export function ChatLibraryPage() {
  return <LibraryPage chat={useLibraryChat()} />;
}

/** The person's storage settings, with how long their conversations are kept. */
export function ChatStoragePage() {
  return <StoragePage retention={<ChatRetentionSection />} />;
}

function useLibraryChat(): LibraryChat {
  const navigate = useNavigate();
  const cache = useQueryClient();
  return useMemo(
    () => ({
      ask: async ({ question, title, attach }, signal) => {
        const session = await newChatSession(title, signal);
        await cache.invalidateQueries({ queryKey: chatSessionsKey });
        await navigate({
          to: "/chat/$sessionId",
          params: { sessionId: session.id },
          search: { ask: question || undefined, attach: [...attach] },
        });
      },
      ModelPicker: PreferredModelPicker,
      AddToProjectDialog: ChatAddToProjectDialog,
      removeFromProject,
      RetentionSection: ChatRetentionSection,
    }),
    [cache, navigate],
  );
}

/**
 * The model a person last chose, which Chat reads when the conversation opens: a question started from the
 * library goes to the model chosen here.
 */
function PreferredModelPicker({ disabled }: { disabled: boolean }) {
  const { actorId } = useApplicationSession();
  const [model, setModel] = useState(() => readChatModelPreference(actorId));
  return (
    <ChatModelPicker
      value={model}
      onChange={(id) => {
        setModel(id);
        writeChatModelPreference(actorId, id);
      }}
      disabled={disabled}
    />
  );
}
