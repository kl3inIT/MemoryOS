import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQuery } from "@tanstack/react-query";
import { listAvailableChatModels } from "@/lib/hey-api/sdk.gen";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { ChatModelLogo } from "./chat-model-logo";

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
