import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  getChatPreferencesOptions,
  listAvailableChatModelsQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { saveChatPreferences } from "@/lib/hey-api/sdk.gen";
import type { ChatPreferences, ChatPreferencesInput } from "@/lib/hey-api/types.gen";

/** Typed by the generated options, so cache reads and writes carry the preferences type. */
const preferencesKey = getChatPreferencesOptions().queryKey;

export function useChatPreferences() {
  return useQuery({ ...getChatPreferencesOptions(), staleTime: 60_000 });
}

function input(value: ChatPreferences): ChatPreferencesInput {
  return {
    workRole: value.workRole,
    personalPreferences: value.personalPreferences,
    defaultModelId: value.defaultModelId ?? undefined,
    temperatureDefault: value.temperatureDefault ?? undefined,
    reasoningEffortDefault: value.reasoningEffortDefault ?? undefined,
    autoScroll: value.autoScroll,
  };
}

/** Saves one change on top of the latest saved preferences; the server replaces the whole record. */
export function useSaveChatPreferences() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: async (change: Partial<ChatPreferencesInput>) => {
      const current = client.getQueryData(preferencesKey);
      if (!current) throw new Error("Preferences are not loaded.");
      return (
        await saveChatPreferences({
          body: { ...input(current), ...change },
        })
      ).data;
    },
    onSuccess: (data) => {
      client.setQueryData(preferencesKey, data);
      // The personal default changes which model new conversations inherit.
      void client.invalidateQueries({ queryKey: listAvailableChatModelsQueryKey() });
    },
  });
}
