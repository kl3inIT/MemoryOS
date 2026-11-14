import type { SuggestionAdapter } from "@assistant-ui/react";
import type { QueryClient } from "@tanstack/react-query";
import { getChatSettingsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { suggestChatFollowUps } from "@/lib/hey-api/sdk.gen";
import type { ChatThreadController } from "./chat-thread-controller";

/**
 * MEM-266: the questions offered under the latest answer, written by the API. assistant-ui calls `generate` once a run
 * settles with an answer last and clears the suggestions when the next run starts. The browser sends only the answer's
 * id, never a prompt, so `createSuggestionAdapter` (which builds the prompt from the browser's transcript) is not used.
 * Nothing is asked while the Tenant's switch is off, for a temporary conversation or for an answer that did not
 * complete; any failure shows nothing.
 */
export function createChatFollowUpAdapter(
  controller: ChatThreadController,
  queries: QueryClient,
): SuggestionAdapter {
  return {
    async generate({ messages, signal }) {
      const answer = messages.at(-1);
      const sessionId = controller.transport.session?.id ?? controller.remoteId;
      if (
        !answer ||
        answer.role !== "assistant" ||
        answer.metadata.custom.serverStatus !== "COMPLETED" ||
        !sessionId ||
        controller.transport.temporary
      )
        return [];
      try {
        const settings = await queries.ensureQueryData(getChatSettingsOptions());
        if (!settings.followUpSuggestions || signal?.aborted) return [];
        controller.setFollowUpsFor(answer.id, true);
        const { data } = await suggestChatFollowUps({
          path: { sessionId, messageId: answer.id },
          // The server bounds the call (60 s for a reasoning task model); this only ends a lost connection.
          signal: AbortSignal.any([...(signal ? [signal] : []), AbortSignal.timeout(70_000)]),
        });
        return data.suggestions.map((prompt) => ({ prompt }));
      } catch {
        // Suggestions are optional: a failure leaves the answer as it is.
        return [];
      } finally {
        controller.setFollowUpsFor(answer.id, false);
      }
    },
  };
}
