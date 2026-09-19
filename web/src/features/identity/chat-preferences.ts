import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { sameOriginMutationHeaders } from "@/lib/api";
import { getChatPreferencesOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { saveChatPreferences } from "@/lib/hey-api/sdk.gen";
import type { ChatPreferences, ChatPreferencesInput } from "@/lib/hey-api/types.gen";

export const chatPreferencesKey = getChatPreferencesOptions().queryKey;

export function useChatPreferences() {
  return useQuery({ ...getChatPreferencesOptions(), staleTime: 60_000 });
}

function input(value: ChatPreferences): ChatPreferencesInput {
  return {
    workRole: value.workRole,
    personalPreferences: value.personalPreferences,
    defaultModelId: value.defaultModelId ?? undefined,
    startPage: value.startPage,
    autoScroll: value.autoScroll,
  };
}

/** Saves one change on top of the latest saved preferences; the server replaces the whole record. */
export function useSaveChatPreferences() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: async (change: Partial<ChatPreferencesInput>) => {
      const current = client.getQueryData<ChatPreferences>(chatPreferencesKey);
      if (!current) throw new Error("Preferences are not loaded.");
      return (
        await saveChatPreferences({
          body: { ...input(current), ...change },
          headers: sameOriginMutationHeaders,
          throwOnError: true,
        })
      ).data;
    },
    onSuccess: (data) => {
      client.setQueryData(chatPreferencesKey, data);
      // The personal default changes which model new conversations inherit.
      void client.invalidateQueries({ queryKey: ["chat-models"] });
    },
  });
}
