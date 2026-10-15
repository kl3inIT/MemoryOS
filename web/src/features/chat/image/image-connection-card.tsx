import { useId, useState, type ReactNode } from "react";
import { useStore } from "@tanstack/react-form";
import { Unplug } from "lucide-react";
import { useAppForm } from "@/components/form/app-form";
import { useFieldValidity } from "@/components/form/form-context";
import {
  ConnectionCard,
  ConnectionDialog,
  ConnectionForm,
} from "@/components/composites/connection-form";
import { ProviderLogo } from "@/components/provider-logos/provider-logo";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldLabel,
  FieldLegend,
  FieldSet,
  FieldTitle,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { StatusBadge } from "@/components/ui/status-badge";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type {
  ImageConnectionResponse,
  ImageKnownModelResponse,
  ImageProviderResponse,
} from "@/lib/hey-api/types.gen";
import { useProblemMessage } from "@/lib/use-problem-message";
import {
  customImagePresets,
  isJsonObject,
  templateComplete,
  templateDraft,
  templateOf,
} from "./custom-image-presets";
import { ImageFormatField, TemplateBodyField } from "./custom-image-template";
import {
  imageProblem,
  imageProviderMarks,
  imageProviderNames,
  useImageConnectionMutations,
  type ImageProvider,
} from "./use-image-connections";

const CUSTOM = "CUSTOM_HTTP";
const sites: Record<Exclude<ImageProvider, typeof CUSTOM>, string> = {
  OPENAI_IMAGE: "platform.openai.com",
  CLOUDFLARE_WORKERS_AI: "developers.cloudflare.com/workers-ai",
  GEMINI_IMAGE: "aistudio.google.com",
  OPENROUTER_IMAGE: "openrouter.ai",
  XAI_IMAGE: "console.x.ai",
  FAL_AI: "fal.ai",
};
const endpointHints: Record<ImageProvider, string> = {
  OPENAI_IMAGE: "https://api.openai.com/v1",
  CLOUDFLARE_WORKERS_AI: "<ACCOUNT_ID>",
  GEMINI_IMAGE: "https://generativelanguage.googleapis.com/v1",
  OPENROUTER_IMAGE: "https://openrouter.ai/api/v1",
  XAI_IMAGE: "https://api.x.ai/v1",
  FAL_AI: "https://fal.run",
  CUSTOM_HTTP: "https://api.example.com",
};
const CLOUDFLARE_ACCOUNT_BASE = "https://api.cloudflare.com/client/v4/accounts/";
/** Cloudflare asks for its account ID; a stored account endpoint is shown back as the ID. */
const displayEndpoint = (provider: ImageProvider, endpoint: string) =>
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

/** Where the provider's keys come from, or what a custom connection is. */
function ProviderSite({ provider }: { provider: ImageProvider }) {
  const ui = useAppTranslation();
  return provider === CUSTOM ? ui("Kết nối API ảnh bất kỳ bằng mẫu request") : sites[provider];
}

function displayName(provider: ImageProviderResponse, model: string) {
  return provider.knownModels.find((known) => known.modelName === model)?.displayName ?? model;
}

/** The models a provider generates and edits images with. */
export function ModelSummary({
  provider,
  model,
}: {
  provider: ImageProviderResponse;
  model: string;
}) {
  const ui = useAppTranslation();
  const generate = displayName(provider, model);
  return provider.editModel
    ? ui("Tạo: {{generate}} · Sửa: {{edit}}", { generate, edit: provider.editModel.displayName })
    : ui("Tạo và sửa: {{model}}", { model: generate });
}

/** One image provider: its card and its connection dialog. */
export function ImageConnectionCard({
  provider,
  connection,
  replacements,
  disabled,
  onSelect,
}: {
  provider: ImageProviderResponse;
  connection?: ImageConnectionResponse;
  /** Other connected providers that can take over when this one is disconnected. */
  replacements: ImageProvider[];
  disabled: boolean;
  onSelect: (provider: ImageProvider) => void;
}) {
  const [open, setOpen] = useState(false);
  const configured = !!connection?.credentialConfigured;
  return (
    <ConnectionCard
      logo={<ProviderLogo mark={imageProviderMarks[provider.provider]} />}
      name={imageProviderNames[provider.provider]}
      description={
        connection && configured ? (
          <ModelSummary provider={provider} model={connection.model} />
        ) : (
          <ProviderSite provider={provider.provider} />
        )
      }
      active={!!connection?.active}
      configured={configured}
      disabled={disabled}
      onSelect={() => onSelect(provider.provider)}
      onConfigure={() => setOpen(true)}
    >
      {open && (
        <ImageConnectionDialog
          provider={provider}
          connection={connection}
          replacements={replacements}
          disabled={disabled}
          onClose={() => setOpen(false)}
        />
      )}
    </ConnectionCard>
  );
}

function ImageConnectionDialog({
  provider,
  connection,
  replacements,
  disabled,
  onClose,
}: {
  provider: ImageProviderResponse;
  connection?: ImageConnectionResponse;
  replacements: ImageProvider[];
  disabled: boolean;
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const name = imageProviderNames[provider.provider];
  const isCustom = provider.provider === CUSTOM;
  const configured = !!connection?.credentialConfigured;
  const models = provider.knownModels.filter(
    (model) => !model.deprecated || model.modelName === connection?.model,
  );
  const initialChoice = connection
    ? provider.knownModels.some((model) => model.modelName === connection.model)
      ? connection.model
      : OTHER
    : (models[0]?.modelName ?? OTHER);
  const [disconnecting, setDisconnecting] = useState(false);
  const { save, test, disconnect } = useImageConnectionMutations();
  const form = useAppForm({
    defaultValues: {
      endpoint: displayEndpoint(provider.provider, connection?.endpoint ?? ""),
      key: "",
      choice: initialChoice,
      custom: initialChoice === OTHER ? (connection?.model ?? "") : "",
      template: templateDraft(connection?.template),
    },
    onSubmit: async ({ value }) => {
      test.reset();
      try {
        await save.mutateAsync({
          path: { provider: provider.provider },
          body: {
            endpoint: value.endpoint.trim(),
            model: modelOf(value),
            revision: connection?.revision ?? 0,
            credentialAction: value.key ? "REPLACE" : "KEEP",
            credentialValue: value.key || undefined,
            template: isCustom ? templateOf(value.template) : undefined,
          },
        });
        onClose();
      } catch {
        // The failure stays on the mutation and shows in the form.
      }
    },
  });
  const values = useStore(form.store, (state) => state.values);
  const model = modelOf(values);
  const testable =
    model.length > 0 &&
    (!provider.endpointRequired || values.endpoint.trim().length > 0) &&
    (configured || values.key.trim().length > 0) &&
    (!isCustom || templateComplete(values.template));
  const pathRule = ({ value }: { value: string }) =>
    value.trim().startsWith("/") ? undefined : ui("Đường dẫn phải bắt đầu bằng /");
  const bodyRule = ({ value }: { value: string }) =>
    isJsonObject(value) ? undefined : ui("Body phải là JSON hợp lệ");
  const pointerRule = ({ value }: { value: string }) =>
    value.trim().startsWith("/") ? undefined : ui("Vị trí ảnh phải bắt đầu bằng /");
  const busy = disabled || save.isPending || test.isPending || disconnect.isPending;
  const failure = save.error ?? test.error ?? disconnect.error;
  return (
    <ConnectionDialog open onOpenChange={(next) => !next && onClose()} busy={busy} wide>
      {disconnecting ? (
        <DisconnectStep
          name={name}
          active={!!connection?.active}
          replacements={replacements}
          pending={disconnect.isPending}
          error={disconnect.error ? imageProblem(disconnect.error) : undefined}
          onBack={() => {
            disconnect.reset();
            setDisconnecting(false);
          }}
          onDisconnect={(replacement) =>
            disconnect.mutate({ provider: provider.provider, replacement }, { onSuccess: onClose })
          }
        />
      ) : (
        <ConnectionForm
          title={name}
          description={<ProviderSite provider={provider.provider} />}
          onSubmit={() => void form.handleSubmit()}
          saving={save.isPending}
          saveDisabled={!model}
          busy={busy}
          onTest={() => {
            save.reset();
            test.mutate({
              path: { provider: provider.provider },
              body: {
                endpoint: values.endpoint.trim(),
                model,
                credentialValue: values.key || undefined,
                template: isCustom ? templateOf(values.template) : undefined,
              },
            });
          }}
          testDisabled={!testable}
          tested={test.isSuccess}
          error={failure ? imageProblem(failure) : undefined}
          onClose={onClose}
          notice={
            <p className="font-secondary-body text-content-muted">
              {ui("Kiểm tra kết nối tạo một ảnh thật và có thể tính phí nhà cung cấp.")}
            </p>
          }
          start={
            configured ? (
              <Button
                type="button"
                prominence="tertiary"
                tone="danger"
                disabled={busy}
                onClick={() => {
                  save.reset();
                  test.reset();
                  setDisconnecting(true);
                }}
              >
                <Unplug data-icon="inline-start" aria-hidden="true" />
                {ui("Ngắt kết nối")}
              </Button>
            ) : undefined
          }
        >
          <form.AppField name="endpoint">
            {(field) => (
              <field.TextField
                label={
                  provider.provider === "CLOUDFLARE_WORKERS_AI"
                    ? ui("Account ID")
                    : isCustom
                      ? ui("Địa chỉ API")
                      : provider.endpointRequired
                        ? ui("Địa chỉ tài khoản")
                        : ui("Địa chỉ tùy chỉnh (để trống dùng mặc định)")
                }
                description={
                  provider.provider === "CLOUDFLARE_WORKERS_AI"
                    ? ui(
                        "Chuỗi 32 ký tự trong URL dashboard Cloudflare: dash.cloudflare.com/<ACCOUNT_ID>",
                      )
                    : isCustom
                      ? ui("Đường dẫn của mỗi bước được nối vào sau địa chỉ này.")
                      : undefined
                }
                required={provider.endpointRequired}
                maxLength={2048}
                placeholder={provider.defaultEndpoint ?? endpointHints[provider.provider]}
              />
            )}
          </form.AppField>
          <form.AppField name="key">
            {(field) => (
              <field.TextField
                label={ui("Khóa API")}
                type="password"
                autoComplete="new-password"
                maxLength={8192}
                required={!configured}
                placeholder={configured ? ui("Đã lưu khóa; để trống để giữ nguyên") : ""}
              />
            )}
          </form.AppField>
          <form.AppField name="choice">
            {() => (
              <ModelChoiceField
                models={models}
                custom={
                  <form.AppField name="custom">
                    {(custom) => (
                      <Input
                        aria-label={ui("Tên mô hình")}
                        value={custom.state.value}
                        onChange={(event) => custom.handleChange(event.target.value)}
                        onBlur={custom.handleBlur}
                        required
                        maxLength={200}
                        placeholder={provider.knownModels[0]?.modelName}
                      />
                    )}
                  </form.AppField>
                }
                note={
                  provider.editModel &&
                  ui("Sửa ảnh luôn dùng {{model}} với nhà cung cấp này.", {
                    model: provider.editModel.displayName,
                  })
                }
              />
            )}
          </form.AppField>
          {isCustom && (
            <>
              <Field>
                <FieldLabel htmlFor={`${provider.provider}-preset`}>
                  {ui("Bắt đầu từ mẫu")}
                </FieldLabel>
                <NativeSelect
                  id={`${provider.provider}-preset`}
                  value=""
                  onChange={(event) => {
                    const preset = customImagePresets.find(
                      (entry) => entry.id === event.target.value,
                    );
                    if (preset) form.setFieldValue("template", templateDraft(preset.template));
                  }}
                >
                  <option value="">{ui("Chọn mẫu…")}</option>
                  {customImagePresets.map((preset) => (
                    <option key={preset.id} value={preset.id}>
                      {preset.label}
                    </option>
                  ))}
                </NativeSelect>
              </Field>
              <div className="grid gap-3 sm:grid-cols-2">
                <form.AppField
                  name="template.authHeader"
                  validators={{
                    onChange: ({ value }) =>
                      value.trim() ? undefined : ui("Nhập tên header xác thực"),
                  }}
                >
                  {(field) => (
                    <field.TextField label={ui("Header xác thực")} required maxLength={200} />
                  )}
                </form.AppField>
                <form.AppField name="template.authPrefix">
                  {(field) => (
                    <field.TextField label={ui("Tiền tố khóa")} optional maxLength={200} />
                  )}
                </form.AppField>
              </div>
              <form.AppField name="template.generate.path" validators={{ onChange: pathRule }}>
                {(field) => (
                  <field.TextField
                    label={ui("Đường dẫn tạo ảnh")}
                    required
                    maxLength={2048}
                    placeholder="/images/generations"
                  />
                )}
              </form.AppField>
              <form.AppField name="template.generate.body" validators={{ onChange: bodyRule }}>
                {() => <TemplateBodyField label={ui("Body tạo ảnh (JSON)")} edit={false} />}
              </form.AppField>
              <div className="grid gap-3 sm:grid-cols-2">
                <form.AppField name="template.generate.imageFormat">
                  {() => <ImageFormatField />}
                </form.AppField>
                {values.template.generate.imageFormat !== "RAW" && (
                  <form.AppField
                    name="template.generate.imagePointer"
                    validators={{ onChange: pointerRule }}
                  >
                    {(field) => (
                      <field.TextField
                        label={ui("Vị trí ảnh trong phản hồi (JSON Pointer)")}
                        maxLength={500}
                        placeholder="/data/0/b64_json"
                      />
                    )}
                  </form.AppField>
                )}
              </div>
              <form.AppField name="template.editing">
                {(field) => <field.CheckboxField label={ui("Bật sửa ảnh")} />}
              </form.AppField>
              {values.template.editing && (
                <>
                  <form.AppField name="template.edit.path" validators={{ onChange: pathRule }}>
                    {(field) => (
                      <field.TextField label={ui("Đường dẫn sửa ảnh")} required maxLength={2048} />
                    )}
                  </form.AppField>
                  <form.AppField name="template.edit.body" validators={{ onChange: bodyRule }}>
                    {() => <TemplateBodyField label={ui("Body sửa ảnh (JSON)")} edit />}
                  </form.AppField>
                  <div className="grid gap-3 sm:grid-cols-2">
                    <form.AppField name="template.edit.imageFormat">
                      {() => <ImageFormatField />}
                    </form.AppField>
                    {values.template.edit.imageFormat !== "RAW" && (
                      <form.AppField
                        name="template.edit.imagePointer"
                        validators={{ onChange: pointerRule }}
                      >
                        {(field) => (
                          <field.TextField
                            label={ui("Vị trí ảnh sửa trong phản hồi (JSON Pointer)")}
                            maxLength={500}
                            placeholder="/data/0/b64_json"
                          />
                        )}
                      </form.AppField>
                    )}
                  </div>
                </>
              )}
            </>
          )}
        </ConnectionForm>
      )}
    </ConnectionDialog>
  );
}

function modelOf(values: { choice: string; custom: string }) {
  return values.choice === OTHER ? values.custom.trim() : values.choice;
}

/**
 * The model the provider generates images with: a catalog model, or another one typed into `custom`. A custom
 * control bound to the `choice` field; `note` says which model edits images.
 */
function ModelChoiceField({
  models,
  custom,
  note,
}: {
  models: ImageKnownModelResponse[];
  custom: ReactNode;
  note?: ReactNode;
}) {
  const ui = useAppTranslation();
  const id = useId();
  const { field } = useFieldValidity<string>();
  if (models.length === 0)
    return (
      <FieldSet>
        <FieldLegend variant="label">{ui("Mô hình tạo ảnh")}</FieldLegend>
        {custom}
        {note && <FieldDescription>{note}</FieldDescription>}
      </FieldSet>
    );
  return (
    <FieldSet>
      <FieldLegend variant="label">{ui("Mô hình tạo ảnh")}</FieldLegend>
      <RadioGroup value={field.state.value} onValueChange={field.handleChange}>
        {models.map((known) => (
          <ModelOption key={known.modelName} id={`${id}-${known.modelName}`} model={known} />
        ))}
        <FieldLabel htmlFor={`${id}-other`}>
          <Field orientation="horizontal">
            <RadioGroupItem
              id={`${id}-other`}
              value={OTHER}
              aria-labelledby={`${id}-other-title`}
            />
            <FieldTitle id={`${id}-other-title`}>{ui("Mô hình khác…")}</FieldTitle>
          </Field>
        </FieldLabel>
      </RadioGroup>
      {field.state.value === OTHER && custom}
      {note && <FieldDescription>{note}</FieldDescription>}
    </FieldSet>
  );
}

function ModelOption({ id, model }: { id: string; model: ImageKnownModelResponse }) {
  const ui = useAppTranslation();
  return (
    <FieldLabel htmlFor={id}>
      <Field orientation="horizontal">
        <RadioGroupItem
          id={id}
          value={model.modelName}
          aria-labelledby={`${id}-title`}
          aria-describedby={`${id}-description`}
        />
        <FieldContent className="min-w-0">
          <FieldTitle id={`${id}-title`}>{model.displayName}</FieldTitle>
          <FieldDescription id={`${id}-description`}>
            <span className="block truncate font-mono">{model.modelName}</span>
          </FieldDescription>
        </FieldContent>
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
      </Field>
    </FieldLabel>
  );
}

/** Confirms removing the stored key and, for the provider in use, chooses what replaces it. */
function DisconnectStep({
  name,
  active,
  replacements,
  pending,
  error,
  onBack,
  onDisconnect,
}: {
  name: string;
  active: boolean;
  replacements: ImageProvider[];
  pending: boolean;
  error?: ReturnType<typeof imageProblem>;
  onBack: () => void;
  onDisconnect: (replacement: ImageProvider | null) => void;
}) {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const id = useId();
  const [replacement, setReplacement] = useState<string>(replacements[0] ?? OFF);
  return (
    <div className="flex flex-col gap-5">
      <DialogHeader>
        <DialogTitle>{ui("Ngắt kết nối {{name}}?", { name })}</DialogTitle>
        <DialogDescription>
          {ui("Khóa API sẽ bị xóa khỏi MemoryOS; địa chỉ và mô hình được giữ lại.")}
        </DialogDescription>
      </DialogHeader>
      {active && (
        <FieldSet>
          <FieldLegend variant="label">
            {ui("Nhà cung cấp này đang dùng. Chọn nhà cung cấp thay thế hoặc tắt tạo ảnh.")}
          </FieldLegend>
          <RadioGroup value={replacement} onValueChange={setReplacement}>
            {replacements.map((other) => (
              <FieldLabel key={other} htmlFor={`${id}-${other}`}>
                <Field orientation="horizontal">
                  <RadioGroupItem
                    id={`${id}-${other}`}
                    value={other}
                    aria-labelledby={`${id}-${other}-title`}
                  />
                  <ProviderLogo mark={imageProviderMarks[other]} />
                  <FieldTitle id={`${id}-${other}-title`}>{imageProviderNames[other]}</FieldTitle>
                </Field>
              </FieldLabel>
            ))}
            <FieldLabel htmlFor={`${id}-off`}>
              <Field orientation="horizontal">
                <RadioGroupItem id={`${id}-off`} value={OFF} aria-labelledby={`${id}-off-title`} />
                <FieldTitle id={`${id}-off-title`}>{ui("Tắt tạo ảnh")}</FieldTitle>
              </Field>
            </FieldLabel>
          </RadioGroup>
        </FieldSet>
      )}
      {error && (
        <Alert variant="destructive">
          <AlertTitle>{problemMessage(error)}</AlertTitle>
        </Alert>
      )}
      <DialogFooter>
        <Button type="button" prominence="secondary" disabled={pending} onClick={onBack}>
          {ui("Quay lại")}
        </Button>
        <Button
          type="button"
          tone="danger"
          pending={pending}
          onClick={() =>
            onDisconnect(active && replacement !== OFF ? (replacement as ImageProvider) : null)
          }
        >
          {ui("Ngắt kết nối")}
        </Button>
      </DialogFooter>
    </div>
  );
}
