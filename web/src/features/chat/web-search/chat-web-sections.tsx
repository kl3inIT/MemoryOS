import type { ReactNode } from "react";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { Cpu, MessagesSquare, Telescope } from "lucide-react";
import { ConnectionStatusBadge } from "@/components/composites/connection-form";
import { ProviderCard } from "@/components/provider-logos/provider-card";
import { ProviderLogo } from "@/components/provider-logos/provider-logo";
import { hasProviderMark } from "@/components/provider-logos/provider-marks";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { StatusBadge } from "@/components/ui/status-badge";
import { Switch } from "@/components/ui/switch";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  getChatSettingsOptions,
  getChatSettingsQueryKey,
  getChatWebAvailabilityQueryKey,
  listAvailableChatModelsQueryKey,
  listChatProviderAdaptersOptions,
  listChatProvidersOptions,
  listConfiguredChatModelsOptions,
  listConfiguredChatModelsQueryKey,
  saveChatHistoryVisibilityMutation,
  saveChatSettingsMutation,
  updateChatModelMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChatHistoryVisibilityRequest, Model, ProviderView } from "@/lib/hey-api/types.gen";
import { presentProblem, type ErrorMessage } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { webProblem } from "./use-web-connections";

type ChatHistoryVisibility = ChatHistoryVisibilityRequest["visibility"];

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

/**
 * Who may read other people's conversations (MEM-125). "Hide who asked" hides the name and the e-mail and nothing
 * else — a question often names its author — so the screen says that rather than promising anonymity.
 */
export function ConversationHistorySection() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const settings = useQuery({ ...getChatSettingsOptions(), retry: false });
  const save = useMutation({
    ...saveChatHistoryVisibilityMutation(),
    onSuccess: () => cache.invalidateQueries({ queryKey: getChatSettingsQueryKey() }),
  });
  if (settings.isPending) return null;
  const modes: { value: ChatHistoryVisibility; label: AppCopy; detail: AppCopy }[] = [
    {
      value: "NORMAL",
      label: "Show who asked",
      detail: "A reader sees the name and e-mail of the person who asked.",
    },
    {
      value: "ANONYMIZED",
      label: "Hide who asked",
      detail:
        "The name and e-mail are hidden; the questions and answers are not. A question often names its author.",
    },
    {
      value: "DISABLED",
      label: "Nobody reads other people's conversations",
      detail: "Conversations are still recorded; this screen and its export are refused.",
    },
  ];
  return (
    <WebSection
      title={ui("Conversation history")}
      error={save.error ? presentProblem(save.error, "mutation").message : undefined}
    >
      <p className="text-content-muted">
        {ui(
          "Who may read the organization's questions and answers. Opening a conversation is recorded in the audit log.",
        )}
      </p>
      {settings.isError ? (
        <Alert variant="destructive">
          <AlertTitle>{ui("Không tải được cài đặt Chat.")}</AlertTitle>
        </Alert>
      ) : (
        <div className="flex flex-col gap-2">
          {modes.map((mode) => (
            <ProviderCard
              key={mode.value}
              logo={<MessagesSquare />}
              name={ui(mode.label)}
              description={ui(mode.detail)}
              selected={settings.data.chatHistoryVisibility === mode.value}
              actions={
                <Switch
                  checked={settings.data.chatHistoryVisibility === mode.value}
                  disabled={save.isPending}
                  aria-label={ui(mode.label)}
                  onCheckedChange={(checked) => {
                    if (checked)
                      save.mutate({
                        body: { visibility: mode.value, revision: settings.data.revision },
                      });
                  }}
                />
              }
            />
          ))}
        </div>
      )}
    </WebSection>
  );
}

/** As Onyx Chat Preferences: Deep research is offered in the composer while enabled, and is enabled until changed. */
export function DeepResearchSection() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const settings = useQuery({ ...getChatSettingsOptions(), retry: false });
  const save = useMutation({
    ...saveChatSettingsMutation(),
    onSuccess: () => cache.invalidateQueries({ queryKey: getChatSettingsQueryKey() }),
  });
  if (settings.isPending) return null;
  return (
    <WebSection
      title={ui("Deep Research")}
      error={save.error ? presentProblem(save.error, "mutation").message : undefined}
    >
      {settings.isError ? (
        <Alert variant="destructive">
          <AlertTitle>{ui("Không tải được cài đặt Chat.")}</AlertTitle>
        </Alert>
      ) : (
        <ProviderCard
          logo={<Telescope />}
          name={ui("Deep Research")}
          description={ui(
            "Hệ thống nghiên cứu tự động trên Web và các nguồn đã kết nối. Dùng nhiều token hơn đáng kể cho mỗi câu hỏi.",
          )}
          selected={settings.data.deepResearchEnabled}
          actions={
            <Switch
              checked={settings.data.deepResearchEnabled}
              disabled={save.isPending}
              aria-label={ui("Bật Deep Research")}
              onCheckedChange={(checked) =>
                save.mutate({
                  body: { deepResearchEnabled: checked, revision: settings.data.revision },
                })
              }
            />
          }
        />
      )}
    </WebSection>
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
