import { useId, useState, type ReactNode } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, ImageIcon, Settings2, Unplug } from "lucide-react";
import { Dialog } from "radix-ui";
import { ProviderCard } from "@/components/provider-logos/provider-card";
import { ProviderLogo } from "@/components/provider-logos/provider-logo";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
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

const imageProblem = (error: unknown): ErrorMessage => presentProblem(error, "mutation").message;

/** Product names are proper nouns; the model catalog itself comes from the backend. */
const names: Record<Provider, string> = {
  OPENAI_IMAGE: "OpenAI Images",
  CLOUDFLARE_WORKERS_AI: "Cloudflare Workers AI",
};
const marks = { OPENAI_IMAGE: "OPENAI", CLOUDFLARE_WORKERS_AI: "CLOUDFLARE" } as const;
const sites: Record<Provider, string> = {
  OPENAI_IMAGE: "platform.openai.com",
  CLOUDFLARE_WORKERS_AI: "developers.cloudflare.com/workers-ai",
};
const endpointHints: Record<Provider, string> = {
  OPENAI_IMAGE: "https://api.openai.com/v1",
  CLOUDFLARE_WORKERS_AI: "<ACCOUNT_ID>",
};
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
const OTHER = "__other__";
const OFF = "__off__";
const notice =
  "rounded-xl border border-border-default bg-surface-sunken px-4 py-3 text-sm text-content-secondary";
const option =
  "flex cursor-pointer items-center gap-3 rounded-xl border border-border-default px-3 py-2.5 has-[[data-state=checked]]:border-border-strong has-[[data-state=checked]]:bg-surface-sunken";

function InUseBadge({ children }: { children: ReactNode }) {
  return (
    <StatusBadge tone="success" className="gap-1">
      <CheckCircle2 className="size-3.5" aria-hidden="true" />
      {children}
    </StatusBadge>
  );
}

function displayName(provider: ImageProviderResponse, model: string) {
  return provider.knownModels.find((known) => known.modelName === model)?.displayName ?? model;
}

function ModelSummary({ provider, model }: { provider: ImageProviderResponse; model: string }) {
  const ui = useAppTranslation();
  const generate = displayName(provider, model);
  return provider.editModel
    ? ui("Tạo: {{generate}} · Sửa: {{edit}}", { generate, edit: provider.editModel.displayName })
    : ui("Tạo và sửa: {{model}}", { model: generate });
}

export function ChatImageSettings() {
  const ui = useAppTranslation();
  const session = useApplicationSession();
  const manager = session.capabilities.includes("MODELS_MANAGE");
  const cache = useQueryClient();
  const problemMessage = useProblemMessage();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ErrorMessage>();
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
          <section aria-label={ui("Đang dùng")} className="space-y-3">
            <h2 className="text-lg font-semibold">{ui("Đang dùng")}</h2>
            {active && activeProvider ? (
              <ProviderCard
                logo={<ProviderLogo mark={marks[active.provider]} />}
                name={names[active.provider]}
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
              <p className={notice}>{ui("Chọn một nhà cung cấp để bật tạo ảnh trong Chat.")}</p>
            )}
            <p className="text-sm text-content-muted">
              {ui("Sửa ảnh dùng cùng nhà cung cấp, nên tắt tạo ảnh cũng tắt sửa ảnh.")}
            </p>
          </section>
          <section aria-label={ui("Nhà cung cấp")} className="mt-8 space-y-3">
            <h2 className="text-lg font-semibold">{ui("Nhà cung cấp")}</h2>
            <div className="space-y-3">
              {catalog.map((provider) => {
                const connection = configured.find((c) => c.provider === provider.provider);
                return (
                  <ConnectionCard
                    key={provider.provider}
                    provider={provider}
                    connection={connection}
                    replacements={configured
                      .filter((c) => c.provider !== provider.provider && c.credentialConfigured)
                      .map((c) => c.provider)}
                    disabled={pending}
                    onChanged={changed}
                    onSelect={select}
                    onDisconnect={disconnect}
                  />
                );
              })}
            </div>
          </section>
        </>
      )}
      {error && (
        <p role="alert" className="mt-4 text-sm text-status-danger-content">
          {problemMessage(error)}
        </p>
      )}
    </SettingsLayout>
  );
}

function ModelOption({ id, model }: { id: string; model: ImageKnownModelResponse }) {
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
        <StatusBadge tone="neutral">
          {formats[model.outputMediaType] ?? model.outputMediaType}
        </StatusBadge>
        {model.sizes.length > 0 && (
          <StatusBadge tone="neutral">
            {model.sizes.map((size) => size.replace("x", "×")).join(" · ")}
          </StatusBadge>
        )}
        {model.edit && <StatusBadge tone="info">{ui("Hỗ trợ sửa ảnh")}</StatusBadge>}
        {model.deprecated && <StatusBadge tone="warning">{ui("Ngừng hỗ trợ")}</StatusBadge>}
      </span>
    </label>
  );
}

function ConnectionCard({
  provider,
  connection,
  replacements,
  disabled,
  onChanged,
  onSelect,
  onDisconnect,
}: {
  provider: ImageProviderResponse;
  connection?: ImageConnectionResponse;
  replacements: Provider[];
  disabled: boolean;
  onChanged: () => Promise<void>;
  onSelect: (provider: Provider) => Promise<void>;
  onDisconnect: (provider: Provider, replacement: Provider | null) => Promise<void>;
}) {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const id = useId();
  const name = names[provider.provider];
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
  const [open, setOpen] = useState(false);
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
  function changeOpen(next: boolean) {
    if (pending) return;
    setActionError(undefined);
    setTested(false);
    setKey("");
    setStep("configure");
    setEndpoint(displayEndpoint(provider.provider, connection?.endpoint ?? ""));
    setChoice(initialChoice);
    setCustom(initialChoice === OTHER ? (connection?.model ?? "") : "");
    setReplacement(replacements[0] ?? OFF);
    setOpen(next);
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
      setOpen(false);
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
      setOpen(false);
    });
  return (
    <ProviderCard
      as="section"
      aria-label={name}
      logo={<ProviderLogo mark={marks[provider.provider]} />}
      name={name}
      description={
        connection && configured ? (
          <ModelSummary provider={provider} model={connection.model} />
        ) : (
          sites[provider.provider]
        )
      }
      selected={active}
      actions={
        <>
          {active ? (
            <InUseBadge>{ui("Đang dùng")}</InUseBadge>
          ) : configured ? (
            <InUseBadge>{ui("Đã kết nối")}</InUseBadge>
          ) : null}
          {configured && !active && (
            <Button
              size="sm"
              prominence="secondary"
              disabled={disabled || pending}
              onClick={() => void onSelect(provider.provider)}
            >
              {ui("Đặt làm mặc định")}
            </Button>
          )}
          {configured ? (
            <Button
              size="sm"
              prominence="tertiary"
              disabled={disabled || pending}
              onClick={() => changeOpen(true)}
            >
              <Settings2 aria-hidden="true" /> {ui("Cấu hình")}
            </Button>
          ) : (
            <Button
              size="sm"
              prominence="secondary"
              disabled={disabled || pending}
              onClick={() => changeOpen(true)}
            >
              {ui("Kết nối")}
            </Button>
          )}
        </>
      }
    >
      <Dialog.Root open={open} onOpenChange={changeOpen}>
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 z-40 bg-content-primary/20 backdrop-blur-[2px]" />
          <Dialog.Content className="fixed top-1/2 left-1/2 z-50 max-h-[calc(100dvh-2rem)] w-[min(38rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 overflow-y-auto rounded-2xl border border-border-default bg-surface-overlay p-6 shadow-md outline-none">
            {step === "disconnect" ? (
              <>
                <Dialog.Title className="text-xl font-semibold">
                  {ui("Ngắt kết nối {{name}}?", { name })}
                </Dialog.Title>
                <Dialog.Description className="mt-2 text-sm text-content-secondary">
                  {ui("Khóa API sẽ bị xóa khỏi MemoryOS; địa chỉ và mô hình được giữ lại.")}
                </Dialog.Description>
                {active && (
                  <div className="mt-5 space-y-2">
                    <p className="text-sm">
                      {ui(
                        "Nhà cung cấp này đang dùng. Chọn nhà cung cấp thay thế hoặc tắt tạo ảnh.",
                      )}
                    </p>
                    <RadioGroup value={replacement} onValueChange={setReplacement}>
                      {replacements.map((other) => (
                        <label key={other} htmlFor={`${id}-${other}`} className={option}>
                          <RadioGroupItem id={`${id}-${other}`} value={other} />
                          <ProviderLogo mark={marks[other]} />
                          <span className="text-sm font-medium">{names[other]}</span>
                        </label>
                      ))}
                      <label htmlFor={`${id}-off`} className={option}>
                        <RadioGroupItem id={`${id}-off`} value={OFF} />
                        <span className="text-sm font-medium">{ui("Tắt tạo ảnh")}</span>
                      </label>
                    </RadioGroup>
                  </div>
                )}
                {actionError && (
                  <p role="alert" className="mt-4 text-sm text-status-danger-content">
                    {problemMessage(actionError)}
                  </p>
                )}
                <div className="mt-6 flex justify-end gap-2">
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
                </div>
              </>
            ) : (
              <form
                onSubmit={(event) => {
                  event.preventDefault();
                  void save();
                }}
              >
                <Dialog.Title className="text-xl font-semibold">{name}</Dialog.Title>
                <Dialog.Description className="mt-2 text-sm text-content-secondary">
                  {sites[provider.provider]}
                </Dialog.Description>
                <fieldset disabled={disabled || pending} className="mt-5 space-y-4">
                  <label className="block space-y-1">
                    <span>
                      {provider.provider === "CLOUDFLARE_WORKERS_AI"
                        ? ui("Account ID")
                        : provider.endpointRequired
                          ? ui("Địa chỉ tài khoản")
                          : ui("Địa chỉ tùy chỉnh (để trống dùng mặc định)")}
                    </span>
                    <Input
                      value={endpoint}
                      onChange={(event) => setEndpoint(event.target.value)}
                      required={provider.endpointRequired}
                      maxLength={2048}
                      placeholder={provider.defaultEndpoint ?? endpointHints[provider.provider]}
                    />
                  </label>
                  {provider.provider === "CLOUDFLARE_WORKERS_AI" && (
                    <p className="-mt-3 text-xs text-content-muted">
                      {ui(
                        "Chuỗi 32 ký tự trong URL dashboard Cloudflare: dash.cloudflare.com/<ACCOUNT_ID>",
                      )}
                    </p>
                  )}
                  <label className="block space-y-1">
                    <span>{ui("Khóa API")}</span>
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
                  <div role="group" aria-labelledby={`${id}-models`} className="space-y-2">
                    <span id={`${id}-models`}>{ui("Mô hình tạo ảnh")}</span>
                    <RadioGroup value={choice} onValueChange={setChoice}>
                      {models.map((known) => (
                        <ModelOption
                          key={known.modelName}
                          id={`${id}-${known.modelName}`}
                          model={known}
                        />
                      ))}
                      <label htmlFor={`${id}-other`} className={option}>
                        <RadioGroupItem id={`${id}-other`} value={OTHER} />
                        <span className="text-sm font-medium">{ui("Mô hình khác…")}</span>
                      </label>
                    </RadioGroup>
                    {choice === OTHER && (
                      <Input
                        aria-label={ui("Tên mô hình")}
                        value={custom}
                        onChange={(event) => setCustom(event.target.value)}
                        required
                        maxLength={200}
                        placeholder={provider.knownModels[0]?.modelName}
                      />
                    )}
                  </div>
                  {provider.editModel && (
                    <p className={notice}>
                      {ui("Sửa ảnh luôn dùng {{model}} với nhà cung cấp này.", {
                        model: provider.editModel.displayName,
                      })}
                    </p>
                  )}
                </fieldset>
                {tested && (
                  <p
                    role="status"
                    className="mt-4 flex items-center gap-2 rounded-xl border border-status-success-emphasis-border bg-status-success-surface px-4 py-3 text-sm text-status-success-content"
                  >
                    <CheckCircle2 className="size-4" aria-hidden="true" />
                    {ui("Kiểm tra kết nối thành công")}
                  </p>
                )}
                {actionError && (
                  <p role="alert" className="mt-4 text-sm text-status-danger-content">
                    {problemMessage(actionError)}
                  </p>
                )}
                <p className="mt-4 text-xs text-content-muted">
                  {ui("Kiểm tra kết nối tạo một ảnh thật và có thể tính phí nhà cung cấp.")}
                </p>
                <div className="mt-6 flex flex-wrap items-center justify-between gap-2">
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
                  <div className="flex flex-wrap justify-end gap-2">
                    <Button
                      type="button"
                      prominence="internal"
                      disabled={disabled || pending || !testable}
                      onClick={() => void test()}
                    >
                      {ui("Kiểm tra kết nối")}
                    </Button>
                    <Button
                      type="button"
                      prominence="secondary"
                      disabled={pending}
                      onClick={() => changeOpen(false)}
                    >
                      {ui("Đóng")}
                    </Button>
                    <Button type="submit" pending={pending} disabled={disabled || !model}>
                      {ui("Lưu")}
                    </Button>
                  </div>
                </div>
              </form>
            )}
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </ProviderCard>
  );
}
