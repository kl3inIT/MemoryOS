import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQuery } from "@tanstack/react-query";
import { listAvailableChatModels } from "@/lib/hey-api/sdk.gen";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { ChatModelLogo } from "./chat-model-logo";

/**
 * The levels a member may pin on a conversation. Onyx also offers {@code xhigh}, which only OpenAI and Anthropic
 * adaptive models tell apart; MemoryOS leaves it out until levels are filtered per provider.
 */
export const REASONING_EFFORTS = [
  { id: "OFF", name: "Tắt" },
  { id: "LOW", name: "Thấp" },
  { id: "MEDIUM", name: "Vừa" },
  { id: "HIGH", name: "Cao" },
] as const;

/** As the server's ResearchProperties.minimumContextTokens (Onyx's Deep research minimum). */
export const RESEARCH_MINIMUM_CONTEXT = 50_000;

/** The authorized model catalog for a conversation, shared by the picker and regeneration. */
export function useChatModels(sessionId?: string) {
  const ui = useAppTranslation();
  const { actorId, authorizationVersion } = useApplicationSession();
  const catalog = useQuery({
    queryKey: ["chat-models", actorId, authorizationVersion, sessionId],
    queryFn: async ({ signal }) =>
      (await listAvailableChatModels({ query: { sessionId }, signal, throwOnError: true })).data,
    retry: false,
  });
  const entries = (catalog.data ?? []).flatMap((model) =>
    model.id
      ? [
          {
            provider: model.providerName ?? "",
            option: {
              id: model.id,
              name: model.displayName || model.modelName || ui("Model"),
              keywords: [model.modelName ?? "", model.providerName ?? ""],
              icon: <ChatModelLogo modelName={model.modelName ?? ""} />,
              // Only a reasoning model offers levels, and the request carries none for any other model.
              efforts: model.capabilities?.reasoning ? REASONING_EFFORTS : undefined,
            },
          },
        ]
      : [],
  );
  const models = entries.map((entry) => entry.option);
  // Onyx groupLlmOptions: one group per provider, sorted by its name.
  const groups = [...Map.groupBy(entries, (entry) => entry.provider)]
    .map(([provider, members]) => ({ provider, models: members.map((entry) => entry.option) }))
    .sort((a, b) => a.provider.localeCompare(b.provider));
  return { catalog, models, groups };
}
