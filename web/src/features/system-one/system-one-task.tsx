import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plug } from "lucide-react";
import {
  ModelSelectorContent,
  ModelSelectorEmpty,
  ModelSelectorGroup,
  ModelSelectorItem,
  ModelSelectorList,
  ModelSelectorRoot,
  ModelSelectorSearch,
  ModelSelectorTrigger,
  ModelSelectorValue,
  type ModelOption,
} from "@/components/assistant-ui/elements/model-selector";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Card, CardContent } from "@/components/ui/card";
import { ProviderLogo } from "@/components/provider-logos/provider-logo";
import { DataBoundaryTag } from "@/features/models/data-boundary";
import { tenantCandidate, type ManagedProvider } from "@/features/models/model-catalog";
import { ModelLogo } from "@/features/models/model-logo";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  listChatProviderAdaptersOptions,
  listChatProvidersOptions,
  listConfiguredChatModelsOptions,
  setChatModelFlowMutation,
  setSystemOneTaskMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ModelFlow, SystemOneConnection, SystemOneType } from "@/lib/hey-api/types.gen";
import { useProblemMessage } from "@/lib/use-problem-message";
import { CHECK_FLOW, invalidateSystemOne, providerMark, systemOneProblem } from "./system-one";

/** A connection's id in the picker, kept apart from the model ids it is listed with. */
const CONNECTION = "connection:";

/**
 * What the question check runs on: a System One connection or a language model. Choosing saves at once. The Models
 * page offers the language models only and does not show this task.
 */
export function SystemOneTask({
  flow,
  connections,
  types,
}: {
  flow: ModelFlow;
  connections: SystemOneConnection[];
  types: SystemOneType[];
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const problemMessage = useProblemMessage();
  const providers = useQuery({ ...listChatProvidersOptions(), retry: false });
  const adapters = useQuery({ ...listChatProviderAdaptersOptions(), retry: false });
  const configured = useQueries({
    queries: (providers.data ?? []).map((provider) => ({
      ...listConfiguredChatModelsOptions({ path: { providerId: provider.id } }),
      retry: false,
    })),
  });
  const onConnection = useMutation({
    ...setSystemOneTaskMutation(),
    onSettled: () => invalidateSystemOne(cache),
  });
  const onModel = useMutation({
    ...setChatModelFlowMutation(),
    onSettled: () => invalidateSystemOne(cache),
  });
  const saving = onConnection.isPending || onModel.isPending;
  const failure = onConnection.error ?? onModel.error;

  const usable = connections.filter(
    (connection) =>
      connection.credentialConfigured ||
      !types.find((type) => type.provider === connection.provider)?.requiresKey,
  );
  const connectionOptions = usable.map((connection): ModelOption => {
    const mark = providerMark[connection.provider];
    return {
      id: CONNECTION + connection.id,
      name: connection.name,
      description: connection.model,
      icon: mark ? (
        <ProviderLogo mark={mark} className="size-4" />
      ) : (
        <Plug aria-hidden="true" className="size-4" />
      ),
      keywords: [connection.model, connection.provider],
    };
  });
  const groups: { provider: ManagedProvider; options: ModelOption[] }[] = [];
  for (const [index, provider] of (providers.data ?? []).entries()) {
    const options = (configured[index]?.data ?? [])
      .filter((model) => tenantCandidate(model, provider, adapters.data ?? []))
      .map((model): ModelOption => ({
        id: model.id,
        name: model.displayName,
        description: model.modelName !== model.displayName ? model.modelName : undefined,
        icon: <ModelLogo modelName={model.modelName} />,
        keywords: [model.modelName, provider.name],
      }));
    if (options.length) groups.push({ provider, options });
  }
  const value = flow.systemOneConnectionId
    ? CONNECTION + flow.systemOneConnectionId
    : (flow.modelConfigurationId ?? "");

  function choose(id: string) {
    if (saving || id === value) return;
    onConnection.reset();
    onModel.reset();
    if (id.startsWith(CONNECTION))
      onConnection.mutate({
        path: { flow: CHECK_FLOW },
        body: { connectionId: id.slice(CONNECTION.length), revision: flow.revision },
      });
    else
      onModel.mutate({
        path: { flow: CHECK_FLOW },
        query: { revision: flow.revision, modelConfigurationId: id },
      });
  }

  return (
    <div className="flex flex-col gap-2">
      <Card>
        <CardContent>
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div className="min-w-0">
              <h3 className="font-main-ui-action">{ui("Question check")}</h3>
              <p className="font-secondary-body text-content-muted">
                {ui("Xếp câu hỏi trước khi trả lời: xã giao, câu hỏi hay chủ đề bị chặn.")}
              </p>
            </div>
            <ModelSelectorRoot
              models={[...connectionOptions, ...groups.flatMap((group) => group.options)]}
              value={value}
              onValueChange={choose}
            >
              <ModelSelectorTrigger
                aria-label={ui("Kiểm tra câu hỏi chạy bằng")}
                disabled={saving}
                className="max-w-full min-w-0"
              >
                <ModelSelectorValue
                  placeholder={ui("Chọn kết nối hoặc model")}
                  showEffort={false}
                />
              </ModelSelectorTrigger>
              <ModelSelectorContent className="w-[min(24rem,calc(100vw-2rem))]" align="end">
                <ModelSelectorSearch
                  aria-label={ui("Search models")}
                  placeholder={ui("Search models…")}
                />
                <ModelSelectorList>
                  <ModelSelectorEmpty>{ui("No matching models.")}</ModelSelectorEmpty>
                  {connectionOptions.length > 0 && (
                    <ModelSelectorGroup heading={ui("System One")}>
                      {connectionOptions.map((option) => (
                        <ModelSelectorItem key={option.id} model={option} />
                      ))}
                    </ModelSelectorGroup>
                  )}
                  {groups.map((group) => (
                    <ModelSelectorGroup
                      key={group.provider.id}
                      heading={
                        <span className="flex items-center gap-2">
                          <span className="min-w-0 flex-1 truncate">{group.provider.name}</span>
                          <DataBoundaryTag boundary={group.provider.dataBoundary} />
                        </span>
                      }
                    >
                      {group.options.map((option) => (
                        <ModelSelectorItem key={option.id} model={option} />
                      ))}
                    </ModelSelectorGroup>
                  ))}
                </ModelSelectorList>
              </ModelSelectorContent>
            </ModelSelectorRoot>
          </div>
        </CardContent>
      </Card>
      {(onConnection.isError || onModel.isError) && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{problemMessage(systemOneProblem(failure))}</AlertDescription>
        </Alert>
      )}
    </div>
  );
}
