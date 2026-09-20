import { useId, useState, type ReactNode } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, ImageIcon, ImageOff, Plug, Settings2, Unplug } from "lucide-react";
import { ProviderCard } from "@/components/provider-logos/provider-card";
import { ProviderLogo } from "@/components/provider-logos/provider-logo";
import type { ProviderMark } from "@/components/provider-logos/provider-marks";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { StatusBadge } from "@/components/ui/status-badge";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  listChatImageConnections,
  listChatImageProviders,
  saveChatImageConnection,
  selectChatImageProvider,
  testChatImageConnection,
} from "@/lib/hey-api/sdk.gen";
import type {
  ImageConnectionResponse,
  ImageKnownModelResponse,
  ImageProviderResponse,
} from "@/lib/hey-api/types.gen";
import { presentProblem, type ErrorMessage } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";

type Provider = ImageProviderResponse["provider"];
type Group = "vendors" | "platforms" | "custom";

const imageProblem = (error: unknown): ErrorMessage => presentProblem(error, "mutation").message;

type Presentation = {
  /** Product names are proper nouns; the model catalog itself comes from the backend. */
  name: string;
  mark?: ProviderMark;
  group: Group;
  site: string;
  endpoint: "optional" | "cloudflare" | "azure" | "base";
  endpointHint: string;
  /** Azure deploys models under names the Tenant chooses. */
  deployment?: boolean;
};

const presentation: Record<Provider, Presentation> = {
  OPENAI_IMAGE: {
    name: "OpenAI",
    mark: "OPENAI",
    group: "vendors",
    site: "platform.openai.com",
    endpoint: "optional",
    endpointHint: "https://api.openai.com/v1",
  },
  GOOGLE_GEMINI_IMAGE: {
    name: "Google Gemini",
    mark: "GEMINI",
    group: "vendors",
    site: "aistudio.google.com",
    endpoint: "optional",
    endpointHint: "https://generativelanguage.googleapis.com/v1beta",
  },
  AZURE_OPENAI_IMAGE: {
    name: "Azure OpenAI",
    mark: "AZURE",
    group: "platforms",
    site: "ai.azure.com",
    endpoint: "azure",
    endpointHint: "contoso",
    deployment: true,
  },
  CLOUDFLARE_WORKERS_AI: {
    name: "Cloudflare Workers AI",
    mark: "CLOUDFLARE",
    group: "platforms",
    site: "developers.cloudflare.com/workers-ai",
    endpoint: "cloudflare",
    endpointHint: "<ACCOUNT_ID>",
  },
  OPENAI_COMPATIBLE_IMAGE: {
    name: "OpenAI-compatible",
    group: "custom",
    site: "/images/generations · b64_json",
    endpoint: "base",
    endpointHint: "https://gateway.example.com/v1",
  },
};
const groups: Group[] = ["vendors", "platforms", "custom"];

const CLOUDFLARE_ACCOUNT_BASE = "https://api.cloudflare.com/client/v4/accounts/";
/** Cloudflare asks for its account ID; a stored account endpoint is shown back as the ID. */
const displayEndpoint = (provider: Provider, endpoint: string) =>
  provider === "CLOUDFLARE_WORKERS_AI" && endpoint.startsWith(CLOUDFLARE_ACCOUNT_BASE)
    ? endpoint.slice(CLOUDFLARE_ACCOUNT_BASE.length)
    : endpoint;
const formats: Record<string, string> = {
  "image/png": "PNG",
  "image/jpeg": "JPEG",
  "image/webp": "WebP",
};
/** Google models take an aspect ratio instead of a pixel size. */
const ASPECT_RATIOS = "1:1 · 16:9 · 9:16";
/** A typical model name on OpenAI-compatible gateways. */
const COMPATIBLE_MODEL_HINT = "gpt-image-1";
const OTHER = "__other__";
const OFF = "__off__";
const notice =
  "rounded-xl border border-border-default bg-surface-sunken px-4 py-3 text-sm text-content-secondary";
const option =
  "flex cursor-pointer items-center gap-3 rounded-xl border border-border-default px-3 py-2.5 has-[[data-state=checked]]:border-border-strong has-[[data-state=checked]]:bg-surface-sunken";
const sectionTitle = "text-sm font-semibold text-content-primary";

function Logo({ provider }: { provider: Provider }) {
  const mark = presentation[provider].mark;
  return mark ? (
    <ProviderLogo mark={mark} />
  ) : (
    <Plug className="size-6 text-content-secondary" aria-hidden="true" />
  );
}

function InUseBadge({ children }: { children: ReactNode }) {
  return (
    <StatusBadge tone="success" className="gap-1">
      <CheckCircle2 className="size-3.5" aria-hidden="true" />
      {children}
    </StatusBadge>
  );
}

function displayName(provider: ImageProviderResponse, model: string) {
  const known =
    provider.knownModels.find((entry) => entry.modelName === model) ??
    (provider.editModel?.modelName === model ? provider.editModel : undefined);
  return known?.displayName ?? model;
}

/** Mirrors ImageProvider.editModelFor: edit-capable models edit themselves, others use the fallback. */
function editModelFor(provider: ImageProviderResponse, model: string) {
  const known = provider.knownModels.find((entry) => entry.modelName === model);
  if (known?.edit) return model;
  return provider.editModel?.modelName ?? model;
}

function ModelSummary({ provider, model }: { provider: ImageProviderResponse; model: string }) {
  const ui = useAppTranslation();
  const generate = displayName(provider, model);
  const edit = editModelFor(provider, model);
  return edit === model
    ? ui("Tạo và sửa: {{model}}", { model: generate })
    : ui("Tạo: {{generate}} · Sửa: {{edit}}", {
        generate,
        edit: displayName(provider, edit),
      });
}

export function ChatImageSettings() {
  const ui = useAppTranslation();
  const session = useApplicationSession();
  const manager = session.capabilities.includes("MODELS_MANAGE");
  const cache = useQueryClient();
  const problemMessage = useProblemMessage();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ErrorMessage>();
  const [editing, setEditing] = useState<Provider>();
  const providers = useQuery({
    queryKey: ["image-providers", session.actorId, session.authorizationVersion],
    enabled: manager,
    queryFn: async ({ signal }) =>
      (await listChatImageProviders({ signal, throwOnError: true })).data,
    retry: false,
  });
  const connections = useQuery({
    queryKey: ["image-connections", session.actorId, session.authorizationVersion],
    enabled: manager,
    queryFn: async ({ signal }) =>
      (await listChatImageConnections({ signal, throwOnError: true })).data,
    retry: false,
  });
  async function changed() {
    await Promise.all([
      connections.refetch(),
      cache.invalidateQueries({ queryKey: ["chat-image"] }),
    ]);
  }
  async function select(provider: Provider | null) {
    setPending(true);
    setError(undefined);
    try {
      await selectChatImageProvider({
        body: { provider: provider ?? undefined },
        headers: sameOriginMutationHeaders,
        throwOnError: true,
      });
      await changed();
    } catch (failed) {
      setError(imageProblem(failed));
    } finally {
      setPending(false);
    }
  }
  /** Removes the stored key; a replacement is selected first so image generation stays on. */
  async function disconnect(provider: Provider, replacement: Provider | null) {
    if (replacement) {
      await selectChatImageProvider({
        body: { provider: replacement },
        headers: sameOriginMutationHeaders,
        throwOnError: true,
      });
    }
    // Selection advances revisions, so remove the key against the stored state.
    const fresh = (await listChatImageConnections({ throwOnError: true })).data;
    const current = fresh.find((connection) => connection.provider === provider);
    if (current) {
      await saveChatImageConnection({
        path: { provider },
        body: {
          endpoint: current.endpoint,
          model: current.model,
          revision: current.revision,
          credentialAction: "REMOVE",
        },
        headers: sameOriginMutationHeaders,
        throwOnError: true,
      });
    }
    await changed();
  }
  if (!manager)
    return (
      <p role="alert" className="p-6">
        {ui("Bạn không có quyền quản lý mô hình.")}
      </p>
    );
  const catalog = providers.data ?? [];
  const configured = connections.data ?? [];
  const active = configured.find((connection) => connection.active);
  const activeProvider =
    active && catalog.find((provider) => provider.provider === active.provider);
  const connectedCount = configured.filter((connection) => connection.credentialConfigured).length;
  const groupTitles: Record<Group, string> = {
    vendors: ui("Nhà cung cấp mô hình"),
    platforms: ui("Nền tảng đám mây"),
    custom: ui("Tùy chỉnh"),
  };
  const editingProvider = catalog.find((provider) => provider.provider === editing);
  return (
    <SettingsLayout>
      <PageHeader
        title={ui("Tạo ảnh")}
        icon={<ImageIcon />}
        description={ui("Cài đặt nhà cung cấp Chat dùng để tạo và sửa ảnh.")}
      />
      {providers.isError || connections.isError ? (
        <div role="alert" className="space-y-3">
          <p>{ui("Không tải được cài đặt tạo ảnh.")}</p>
          <Button onClick={() => void Promise.all([providers.refetch(), connections.refetch()])}>
            {ui("Tải lại")}
          </Button>
        </div>
      ) : providers.isPending || connections.isPending ? (
        <p role="status">{ui("Đang tải…")}</p>
      ) : (
        <>
          <section aria-labelledby="image-default" className="space-y-3">
            <div>
              <h2 id="image-default" className="text-lg font-semibold">
                {ui("Default")}
              </h2>
              <p className="text-sm text-content-muted">
                {ui("Sửa ảnh dùng cùng nhà cung cấp, nên tắt tạo ảnh cũng tắt sửa ảnh.")}
              </p>
            </div>
            {active && activeProvider ? (
              <ProviderCard
                logo={<Logo provider={active.provider} />}
                name={presentation[active.provider].name}
                description={<ModelSummary provider={activeProvider} model={active.model} />}
                selected
                actions={
                  <>
                    <InUseBadge>{ui("Đang dùng")}</InUseBadge>
                    <Button
                      size="sm"
                      prominence="secondary"
                      disabled={pending}
                      onClick={() => void select(null)}
                    >
                      {ui("Tắt tạo ảnh")}
                    </Button>
                  </>
                }
              />
            ) : (
              <div className={`${notice} flex items-start gap-3`}>
                <ImageOff className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
                <div>
                  <p className="font-medium text-content-primary">{ui("Tạo ảnh đang tắt")}</p>
                  <p>{ui("Chọn một nhà cung cấp để bật tạo ảnh trong Chat.")}</p>
                </div>
              </div>
            )}
          </section>
          <section aria-labelledby="image-providers" className="mt-8 space-y-4">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <h2 id="image-providers" className="text-lg font-semibold">
                {ui("Nhà cung cấp")}
              </h2>
              <span className="text-sm text-content-muted">
                {ui("{{connected}}/{{total}} đã kết nối", {
                  connected: connectedCount,
                  total: catalog.length,
                })}
              </span>
            </div>
            {groups.map((group) => {
              const members = catalog.filter(
                (provider) => presentation[provider.provider].group === group,
              );
              if (members.length === 0) return null;
              return (
                <div key={group} className="space-y-2">
                  <h3 className="text-xs font-medium tracking-wide text-content-muted uppercase">
                    {groupTitles[group]}
                  </h3>
                  <ul className="space-y-2">
                    {members.map((provider) => (
                      <ProviderRow
                        key={provider.provider}
                        provider={provider}
                        connection={configured.find((c) => c.provider === provider.provider)}
                        disabled={pending}
                        onSelect={select}
                        onConfigure={() => setEditing(provider.provider)}
                      />
                    ))}
                  </ul>
                </div>
              );
            })}
          </section>
        </>
      )}
      {error && (
        <p role="alert" className="mt-4 text-sm text-status-danger-content">
          {problemMessage(error)}
        </p>
      )}
      {editingProvider && (
        <ConnectionSheet
          key={editingProvider.provider}
          provider={editingProvider}
          connection={configured.find((c) => c.provider === editingProvider.provider)}
          replacements={configured
            .filter((c) => c.provider !== editingProvider.provider && c.credentialConfigured)
            .map((c) => c.provider)}
          disabled={pending}
          onClose={() => setEditing(undefined)}
          onChanged={changed}
          onDisconnect={disconnect}
        />
      )}
    </SettingsLayout>
  );
}

function ProviderRow({
  provider,
  connection,
  disabled,
  onSelect,
  onConfigure,
}: {
  provider: ImageProviderResponse;
  connection?: ImageConnectionResponse;
  disabled: boolean;
  onSelect: (provider: Provider) => Promise<void>;
  onConfigure: () => void;
}) {
  const ui = useAppTranslation();
  const view = presentation[provider.provider];
  const active = !!connection?.active;
  const configured = !!connection?.credentialConfigured;
  return (
    <ProviderCard
      as="li"
      aria-label={view.name}
      logo={<Logo provider={provider.provider} />}
      name={view.name}
      description={
        connection && configured ? (
          <ModelSummary provider={provider} model={connection.model} />
        ) : (
          view.site
        )
      }
      selected={active}
      actions={
        <>
          {active ? (
            <InUseBadge>{ui("Đang dùng")}</InUseBadge>
          ) : configured ? (
            <InUseBadge>{ui("Đã kết nối")}</InUseBadge>
          ) : (
            <StatusBadge tone="neutral">{ui("Chưa kết nối")}</StatusBadge>
          )}
          {configured && !active && (
            <Button
              size="sm"
              prominence="secondary"
              disabled={disabled}
              onClick={() => void onSelect(provider.provider)}
            >
              {ui("Đặt làm mặc định")}
            </Button>
          )}
          {configured ? (
            <Button size="sm" prominence="tertiary" disabled={disabled} onClick={onConfigure}>
              <Settings2 aria-hidden="true" /> {ui("Cấu hình")}
            </Button>
          ) : (
            <Button size="sm" prominence="secondary" disabled={disabled} onClick={onConfigure}>
              {ui("Kết nối")}
            </Button>
          )}
        </>
      }
    />
  );
}

function ModelOption({
  id,
  model,
  aspectRatios,
}: {
  id: string;
  model: ImageKnownModelResponse;
  aspectRatios: boolean;
}) {
  const ui = useAppTranslation();
  return (
    <label htmlFor={id} className={option}>
      <RadioGroupItem id={id} value={model.modelName} />
      <span className="min-w-0 flex-1">
        <span className="block text-sm font-medium">{model.displayName}</span>
        <span className="block truncate font-mono text-xs text-content-muted">
          {model.modelName}
        </span>
      </span>
      <span className="flex flex-wrap justify-end gap-1">
        <StatusBadge tone="neutral" size="sm">
          {formats[model.outputMediaType] ?? model.outputMediaType}
        </StatusBadge>
        {model.sizes.length > 0 ? (
          <StatusBadge tone="neutral" size="sm">
            {model.sizes.map((size) => size.replace("x", "×")).join(" · ")}
          </StatusBadge>
        ) : (
          aspectRatios && (
            <StatusBadge tone="neutral" size="sm">
              {ASPECT_RATIOS}
            </StatusBadge>
          )
        )}
        {model.edit && (
          <StatusBadge tone="info" size="sm">
            {ui("Hỗ trợ sửa ảnh")}
          </StatusBadge>
        )}
        {model.deprecated && (
          <StatusBadge tone="warning" size="sm">
            {ui("Ngừng hỗ trợ")}
          </StatusBadge>
        )}
      </span>
    </label>
  );
}

function ConnectionSheet({
  provider,
  connection,
  replacements,
  disabled,
  onClose,
  onChanged,
  onDisconnect,
}: {
  provider: ImageProviderResponse;
  connection?: ImageConnectionResponse;
  replacements: Provider[];
  disabled: boolean;
  onClose: () => void;
  onChanged: () => Promise<void>;
  onDisconnect: (provider: Provider, replacement: Provider | null) => Promise<void>;
}) {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const id = useId();
  const view = presentation[provider.provider];
  const active = !!connection?.active;
  const configured = !!connection?.credentialConfigured;
  const models = provider.knownModels.filter(
    (model) => !model.deprecated || model.modelName === connection?.model,
  );
  const initialChoice = connection
    ? provider.knownModels.some((model) => model.modelName === connection.model)
      ? connection.model
      : OTHER
    : (models[0]?.modelName ?? OTHER);
  const [step, setStep] = useState<"configure" | "disconnect">("configure");
  const [key, setKey] = useState("");
  const [endpoint, setEndpoint] = useState(
    displayEndpoint(provider.provider, connection?.endpoint ?? ""),
  );
  const [choice, setChoice] = useState(initialChoice);
  const [custom, setCustom] = useState(initialChoice === OTHER ? (connection?.model ?? "") : "");
  const [replacement, setReplacement] = useState<string>(replacements[0] ?? OFF);
  const [pending, setPending] = useState(false);
  const [actionError, setActionError] = useState<ErrorMessage>();
  const [tested, setTested] = useState(false);
  const model = choice === OTHER ? custom.trim() : choice;
  const testable =
    model.length > 0 &&
    (!provider.endpointRequired || endpoint.trim().length > 0) &&
    (configured || key.trim().length > 0);
  const editModel = model ? editModelFor(provider, model) : "";
  const endpointLabel = {
    optional: ui("Địa chỉ tùy chỉnh (để trống dùng mặc định)"),
    cloudflare: ui("Account ID"),
    azure: ui("Tài nguyên Azure"),
    base: ui("Base URL"),
  }[view.endpoint];
  const endpointHelp = {
    optional: undefined,
    cloudflare: ui(
      "Chuỗi 32 ký tự trong URL dashboard Cloudflare: dash.cloudflare.com/<ACCOUNT_ID>",
    ),
    azure: ui(
      "Tên tài nguyên hoặc URL https://<resource>.openai.azure.com; MemoryOS dùng Azure OpenAI v1 API.",
    ),
    base: ui("Gateway phải phục vụ POST /images/generations và trả ảnh dạng b64_json."),
  }[view.endpoint];
  function changeOpen(next: boolean) {
    if (!next && !pending) onClose();
  }
  async function perform(action: () => Promise<void>) {
    setPending(true);
    setActionError(undefined);
    setTested(false);
    try {
      await action();
    } catch (failed) {
      setActionError(imageProblem(failed));
    } finally {
      setPending(false);
    }
  }
  const save = () =>
    perform(async () => {
      await saveChatImageConnection({
        path: { provider: provider.provider },
        body: {
          endpoint: endpoint.trim(),
          model,
          revision: connection?.revision ?? 0,
          credentialAction: key ? "REPLACE" : "KEEP",
          credentialValue: key || undefined,
        },
        headers: sameOriginMutationHeaders,
        throwOnError: true,
      });
      setKey("");
      await onChanged();
      onClose();
    });
  const test = () =>
    perform(async () => {
      await testChatImageConnection({
        path: { provider: provider.provider },
        body: {
          endpoint: endpoint.trim(),
          model,
          credentialValue: key || undefined,
        },
        headers: sameOriginMutationHeaders,
        throwOnError: true,
      });
      setTested(true);
    });
  const disconnect = () =>
    perform(async () => {
      const next = active && replacement !== OFF ? (replacement as Provider) : null;
      await onDisconnect(provider.provider, next);
      onClose();
    });
  const errorNotice = actionError && (
    <p role="alert" className="text-sm text-status-danger-content">
      {problemMessage(actionError)}
    </p>
  );
  return (
    <Sheet open onOpenChange={changeOpen}>
      <SheetContent className="w-full gap-0 overflow-y-auto sm:max-w-xl">
        <SheetHeader className="flex-row items-center gap-3 border-b border-border-subtle pr-12">
          <span className="grid size-9 shrink-0 place-items-center [&_img]:size-7">
            <Logo provider={provider.provider} />
          </span>
          <span className="min-w-0">
            <SheetTitle className="font-heading-h3 text-content-primary">
              {step === "disconnect"
                ? ui("Ngắt kết nối {{name}}?", { name: view.name })
                : view.name}
            </SheetTitle>
            <SheetDescription className="break-words">
              {step === "disconnect"
                ? ui("Khóa API sẽ bị xóa khỏi MemoryOS; địa chỉ và mô hình được giữ lại.")
                : view.site}
            </SheetDescription>
          </span>
        </SheetHeader>
        {step === "disconnect" ? (
          <>
            <div className="space-y-4 p-4">
              {active && (
                <div className="space-y-2">
                  <p className="text-sm">
                    {ui("Nhà cung cấp này đang dùng. Chọn nhà cung cấp thay thế hoặc tắt tạo ảnh.")}
                  </p>
                  <RadioGroup value={replacement} onValueChange={setReplacement}>
                    {replacements.map((other) => (
                      <label key={other} htmlFor={`${id}-${other}`} className={option}>
                        <RadioGroupItem id={`${id}-${other}`} value={other} />
                        <Logo provider={other} />
                        <span className="text-sm font-medium">{presentation[other].name}</span>
                      </label>
                    ))}
                    <label htmlFor={`${id}-off`} className={option}>
                      <RadioGroupItem id={`${id}-off`} value={OFF} />
                      <span className="text-sm font-medium">{ui("Tắt tạo ảnh")}</span>
                    </label>
                  </RadioGroup>
                </div>
              )}
              {errorNotice}
            </div>
            <SheetFooter className="flex-row justify-end border-t border-border-subtle">
              <Button
                type="button"
                prominence="secondary"
                disabled={pending}
                onClick={() => setStep("configure")}
              >
                {ui("Quay lại")}
              </Button>
              <Button
                type="button"
                tone="danger"
                pending={pending}
                onClick={() => void disconnect()}
              >
                {ui("Ngắt kết nối")}
              </Button>
            </SheetFooter>
          </>
        ) : (
          <form
            className="flex flex-1 flex-col"
            onSubmit={(event) => {
              event.preventDefault();
              void save();
            }}
          >
            <fieldset disabled={disabled || pending} className="space-y-6 p-4">
              <div className="space-y-4">
                <h3 className={sectionTitle}>{ui("Thông tin kết nối")}</h3>
                <div className="space-y-1">
                  <label className="block space-y-1">
                    <span className="text-sm">{endpointLabel}</span>
                    <Input
                      value={endpoint}
                      onChange={(event) => setEndpoint(event.target.value)}
                      required={provider.endpointRequired}
                      maxLength={2048}
                      placeholder={provider.defaultEndpoint ?? view.endpointHint}
                      aria-describedby={endpointHelp ? `${id}-endpoint-help` : undefined}
                    />
                  </label>
                  {endpointHelp && (
                    <p id={`${id}-endpoint-help`} className="text-xs text-content-muted">
                      {endpointHelp}
                    </p>
                  )}
                </div>
                <label className="block space-y-1">
                  <span className="text-sm">{ui("Khóa API")}</span>
                  <Input
                    type="password"
                    autoComplete="new-password"
                    value={key}
                    maxLength={8192}
                    required={!configured}
                    onChange={(event) => setKey(event.target.value)}
                    placeholder={configured ? ui("Đã lưu khóa; để trống để giữ nguyên") : ""}
                  />
                </label>
              </div>
              <div role="group" aria-labelledby={`${id}-models`} className="space-y-2">
                <h3 id={`${id}-models`} className={sectionTitle}>
                  {view.deployment ? ui("Deployment tạo ảnh") : ui("Mô hình tạo ảnh")}
                </h3>
                {view.deployment && (
                  <p className="text-xs text-content-muted">
                    {ui("Chọn mô hình đã deploy với cùng tên, hoặc nhập tên deployment của bạn.")}
                  </p>
                )}
                {models.length > 0 ? (
                  <>
                    <RadioGroup value={choice} onValueChange={setChoice}>
                      {models.map((known) => (
                        <ModelOption
                          key={known.modelName}
                          id={`${id}-${known.modelName}`}
                          model={known}
                          aspectRatios={provider.provider === "GOOGLE_GEMINI_IMAGE"}
                        />
                      ))}
                      <label htmlFor={`${id}-other`} className={option}>
                        <RadioGroupItem id={`${id}-other`} value={OTHER} />
                        <span className="text-sm font-medium">{ui("Mô hình khác…")}</span>
                      </label>
                    </RadioGroup>
                    {choice === OTHER && (
                      <Input
                        aria-label={view.deployment ? ui("Tên deployment") : ui("Tên mô hình")}
                        value={custom}
                        onChange={(event) => setCustom(event.target.value)}
                        required
                        maxLength={200}
                        placeholder={provider.knownModels[0]?.modelName}
                      />
                    )}
                  </>
                ) : (
                  <Input
                    aria-label={ui("Tên mô hình")}
                    value={custom}
                    onChange={(event) => setCustom(event.target.value)}
                    required
                    maxLength={200}
                    placeholder={COMPATIBLE_MODEL_HINT}
                  />
                )}
              </div>
              {editModel && (
                <div className="space-y-2">
                  <h3 className={sectionTitle}>{ui("Khả năng sửa ảnh")}</h3>
                  <p className={notice}>
                    {editModel === model
                      ? ui("Mô hình này cũng dùng để sửa ảnh.")
                      : ui("Sửa ảnh dùng {{model}} vì mô hình đã chọn không sửa được ảnh.", {
                          model: displayName(provider, editModel),
                        })}
                  </p>
                </div>
              )}
              {tested && (
                <p
                  role="status"
                  className="flex items-center gap-2 rounded-xl border border-status-success-emphasis-border bg-status-success-surface px-4 py-3 text-sm text-status-success-content"
                >
                  <CheckCircle2 className="size-4" aria-hidden="true" />
                  {ui("Kiểm tra kết nối thành công")}
                </p>
              )}
              {errorNotice}
              <div className="flex flex-wrap items-center justify-between gap-2">
                <p className="text-xs text-content-muted">
                  {ui("Kiểm tra kết nối tạo một ảnh thật và có thể tính phí nhà cung cấp.")}
                </p>
                <Button
                  type="button"
                  prominence="internal"
                  disabled={disabled || pending || !testable}
                  onClick={() => void test()}
                >
                  {ui("Kiểm tra kết nối")}
                </Button>
              </div>
            </fieldset>
            <SheetFooter className="flex-row flex-wrap items-center justify-between border-t border-border-subtle">
              {configured ? (
                <Button
                  type="button"
                  prominence="tertiary"
                  tone="danger"
                  disabled={disabled || pending}
                  onClick={() => {
                    setActionError(undefined);
                    setStep("disconnect");
                  }}
                >
                  <Unplug aria-hidden="true" /> {ui("Ngắt kết nối")}
                </Button>
              ) : (
                <span />
              )}
              <div className="flex gap-2">
                <Button
                  type="button"
                  prominence="secondary"
                  disabled={pending}
                  onClick={() => changeOpen(false)}
                >
                  {ui("Cancel")}
                </Button>
                <Button type="submit" pending={pending} disabled={disabled || !model}>
                  {ui("Lưu")}
                </Button>
              </div>
            </SheetFooter>
          </form>
        )}
      </SheetContent>
    </Sheet>
  );
}
