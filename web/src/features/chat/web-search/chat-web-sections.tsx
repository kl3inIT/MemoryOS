import type { ReactNode } from "react";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { Cpu } from "lucide-react";
import { ConnectionStatusBadge } from "@/components/composites/connection-form";
import { ProviderCard } from "@/components/provider-logos/provider-card";
import { ProviderLogo } from "@/components/provider-logos/provider-logo";
import { hasProviderMark } from "@/components/provider-logos/provider-marks";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { StatusBadge } from "@/components/ui/status-badge";
import { Switch } from "@/components/ui/switch";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  getChatWebAvailabilityQueryKey,
  listAvailableChatModelsQueryKey,
  listChatProviderAdaptersOptions,
  listChatProvidersOptions,
  listConfiguredChatModelsOptions,
  listConfiguredChatModelsQueryKey,
  updateChatModelMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { Model, ProviderView } from "@/lib/hey-api/types.gen";
import type { ErrorMessage } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { webProblem } from "./use-web-connections";

/** A section of the Web search page: its heading, its content and the failure of its last change. */
export function WebSection({
  title,
  action,
  error,
  children,
}: {
  title: string;
  /** A control beside the heading. */
  action?: ReactNode;
  error?: ErrorMessage;
  children: ReactNode;
}) {
  const problemMessage = useProblemMessage();
  return (
    <section aria-label={title} className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-heading-h3 text-content-primary">{title}</h2>
        {action}
      </div>
      {children}
      {error && (
        <Alert variant="destructive">
          <AlertTitle>{problemMessage(error)}</AlertTitle>
        </Alert>
      )}
    </section>
  );
}

/** A line that says what a section still needs, such as a provider to choose. */
export function WebNotice({ children }: { children: ReactNode }) {
  return (
    <Alert role="note">
      <AlertDescription>{children}</AlertDescription>
    </Alert>
  );
}

/** The settings of a model with provider-hosted search switched on or off. */
function withNativeSearch(model: Model, enable: boolean) {
  const options = { ...(model.settings?.options ?? {}) };
  if (enable) options.webSearch = "native";
  else delete options.webSearch;
  return {
    modelName: model.modelName,
    displayName: model.displayName,
    visible: model.visible,
    settings: { ...model.settings!, options },
  };
}

/** Provider-hosted search is a per-model option on adapters that support it; it reuses the model's own credential. */
export function NativeSearchSection() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const adapters = useQuery({ ...listChatProviderAdaptersOptions(), retry: false });
  const providers = useQuery({ ...listChatProvidersOptions(), retry: false });
  const nativeProviders = (providers.data ?? []).filter(
    (provider): provider is ProviderView & { id: string } =>
      provider.id !== undefined &&
      adapters.data?.some(
        (adapter) => adapter.type === provider.adapterType && adapter.nativeWebSearch,
      ) === true,
  );
  const models = useQueries({
    queries: nativeProviders.map((provider) => ({
      ...listConfiguredChatModelsOptions({ path: { providerId: provider.id } }),
      retry: false,
    })),
  });
  const toggle = useMutation({
    ...updateChatModelMutation(),
    onSuccess: () =>
      Promise.all([
        ...nativeProviders.map((provider) =>
          cache.invalidateQueries({
            queryKey: listConfiguredChatModelsQueryKey({ path: { providerId: provider.id } }),
          }),
        ),
        cache.invalidateQueries({ queryKey: listAvailableChatModelsQueryKey() }),
        cache.invalidateQueries({ queryKey: getChatWebAvailabilityQueryKey() }),
      ]),
  });
  const rows = nativeProviders.flatMap((provider, index) =>
    (models[index]?.data ?? []).map((model) => ({ provider, model })),
  );
  if (adapters.isPending || providers.isPending) return null;
  if (nativeProviders.length === 0) return null;
  return (
    <WebSection
      title={ui("Tìm kiếm của nhà cung cấp mô hình")}
      error={toggle.error ? webProblem(toggle.error) : undefined}
    >
      {rows.length === 0 ? (
        <WebNotice>{ui("Chưa có mô hình nào trên nhà cung cấp hỗ trợ tìm kiếm.")}</WebNotice>
      ) : (
        <ul className="flex flex-col gap-3">
          {rows.map(({ provider, model }) => {
            const enabled = model.settings?.options?.webSearch === "native";
            const toolCalling = !!model.settings?.capabilities?.toolCalling;
            const editable = model.id !== undefined && model.revision !== undefined;
            const adapter = (provider.adapterType ?? "").toUpperCase();
            const mark = hasProviderMark(adapter) ? adapter : undefined;
            const name = model.displayName || model.modelName;
            return (
              <ProviderCard
                key={model.id}
                as="li"
                logo={mark ? <ProviderLogo mark={mark} /> : <Cpu />}
                name={name}
                description={provider.name}
                selected={enabled}
                actions={
                  <>
                    {enabled && <ConnectionStatusBadge>{ui("Đang dùng")}</ConnectionStatusBadge>}
                    {!toolCalling && (
                      <StatusBadge tone="neutral">{ui("Không hỗ trợ công cụ")}</StatusBadge>
                    )}
                    <Switch
                      checked={enabled}
                      disabled={!toolCalling || !editable || !model.settings || toggle.isPending}
                      aria-label={ui("Tìm kiếm Web của nhà cung cấp cho {{name}}", { name })}
                      onCheckedChange={(checked) =>
                        toggle.mutate({
                          path: { modelId: model.id! },
                          query: { revision: model.revision! },
                          body: withNativeSearch(model, checked),
                        })
                      }
                    />
                  </>
                }
              />
            );
          })}
        </ul>
      )}
    </WebSection>
  );
}
