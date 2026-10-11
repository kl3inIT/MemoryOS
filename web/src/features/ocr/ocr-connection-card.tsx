import { useState } from "react";
import { useStore } from "@tanstack/react-form";
import { Cloud, ScanText, Sparkles } from "lucide-react";
import { useAppForm } from "@/components/form/app-form";
import {
  ConnectionCard,
  ConnectionDialog,
  ConnectionForm,
} from "@/components/composites/connection-form";
import { Field, FieldLabel } from "@/components/ui/field";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { DataBoundaryField, DataBoundaryTag } from "@/features/models/data-boundary";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { OcrConnectionResponse, OcrProviderResponse } from "@/lib/hey-api/types.gen";
import {
  ocrProblem,
  useOcrConnectionMutations,
  type OcrProvider,
  type VisionModel,
} from "./use-ocr-connections";

const endpointHints: Partial<Record<OcrProvider, string>> = {
  PADDLEOCR_VL: "http://ocr.internal:8080",
};
/** Product names are proper nouns; a model's connection is named for what it is. */
const names = {
  PADDLEOCR_VL: "PaddleOCR-VL",
  AIHAY: "AI Hay",
  MODEL: "Model AI",
} as const satisfies Record<OcrProvider, string>;
const logos: Record<OcrProvider, typeof Cloud> = {
  PADDLEOCR_VL: ScanText,
  AIHAY: Cloud,
  MODEL: Sparkles,
};

/** One OCR reader type: its card and its connection dialog. */
export function OcrConnectionCard({
  provider,
  connection,
  models,
  disabled,
  onSelect,
}: {
  provider: OcrProviderResponse;
  connection?: OcrConnectionResponse;
  /** The models a connection that reads with a model may name. */
  models: VisionModel[];
  disabled: boolean;
  onSelect: () => void;
}) {
  const ui = useAppTranslation();
  const [open, setOpen] = useState(false);
  const model = models.find((candidate) => candidate.id === connection?.modelConfigurationId);
  const configured = provider.usesModel
    ? !!connection?.modelConfigurationId
    : !!connection && (!provider.requiresKey || !!connection.credentialConfigured);
  const name = ui(names[provider.provider]);
  const Logo = logos[provider.provider];
  return (
    <ConnectionCard
      logo={<Logo />}
      name={name}
      description={
        connection ? (
          <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <span className="min-w-0 break-all">
              {provider.usesModel
                ? model
                  ? [model.name, model.providerName].join(" · ")
                  : ui("Model đọc được ảnh")
                : connection.endpoint}
            </span>
            <DataBoundaryTag boundary={connection.dataBoundary} />
          </span>
        ) : provider.usesModel ? (
          ui("Model đọc được ảnh")
        ) : (
          <DataBoundaryTag boundary={provider.dataBoundary} />
        )
      }
      active={!!connection?.active}
      configured={configured}
      disabled={disabled}
      onSelect={onSelect}
      onConfigure={() => setOpen(true)}
    >
      {open && (
        <OcrConnectionDialog
          name={name}
          provider={provider}
          connection={connection}
          models={models}
          disabled={disabled}
          onClose={() => setOpen(false)}
        />
      )}
    </ConnectionCard>
  );
}

function OcrConnectionDialog({
  name,
  provider,
  connection,
  models,
  disabled,
  onClose,
}: {
  name: string;
  provider: OcrProviderResponse;
  connection?: OcrConnectionResponse;
  models: VisionModel[];
  disabled: boolean;
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const keySaved = !!connection?.credentialConfigured;
  const { save, test } = useOcrConnectionMutations();
  const form = useAppForm({
    defaultValues: {
      endpoint: connection?.endpoint ?? provider.defaultEndpoint ?? "",
      key: "",
      dataBoundary: connection?.dataBoundary ?? provider.dataBoundary,
      model: connection?.modelConfigurationId ?? "",
    },
    onSubmit: async ({ value }) => {
      test.reset();
      try {
        await save.mutateAsync({
          path: { provider: provider.provider },
          body: provider.usesModel
            ? {
                endpoint: "",
                modelConfigurationId: value.model,
                revision: connection?.revision ?? 0,
                credentialAction: "KEEP",
              }
            : {
                endpoint: value.endpoint.trim(),
                dataBoundary: value.dataBoundary,
                revision: connection?.revision ?? 0,
                credentialAction: value.key ? "REPLACE" : "KEEP",
                credentialValue: value.key || undefined,
              },
        });
        onClose();
      } catch {
        // The failure stays on the mutation and shows in the form.
      }
    },
  });
  const values = useStore(form.store, (state) => state.values);
  const chosen = models.find((candidate) => candidate.id === values.model);
  const ready = provider.usesModel
    ? !!values.model
    : values.endpoint.trim().length > 0 &&
      (!provider.requiresKey || keySaved || values.key.trim().length > 0);
  const busy = disabled || save.isPending || test.isPending;
  const failure = save.error ?? test.error;
  // A model's connection goes where its provider goes; a service's is what the administrator states.
  const boundary = provider.usesModel
    ? (chosen?.dataBoundary ?? provider.dataBoundary)
    : values.dataBoundary;
  return (
    <ConnectionDialog open onOpenChange={(next) => !next && onClose()} busy={busy}>
      <ConnectionForm
        title={name}
        description={<DataBoundaryTag boundary={boundary} />}
        onSubmit={() => void form.handleSubmit()}
        saving={save.isPending}
        saveDisabled={!ready}
        busy={busy}
        onTest={() => {
          save.reset();
          test.mutate({
            path: { provider: provider.provider },
            body: provider.usesModel
              ? { endpoint: "", modelConfigurationId: values.model }
              : { endpoint: values.endpoint.trim(), credentialValue: values.key || undefined },
          });
        }}
        testDisabled={!ready}
        tested={test.isSuccess}
        error={failure ? ocrProblem(failure) : undefined}
        onClose={onClose}
        notice={
          provider.requiresKey ? (
            <p className="font-secondary-body text-content-muted">
              {ui("Kiểm tra kết nối đọc một ảnh thật và có thể tính phí nhà cung cấp.")}
            </p>
          ) : undefined
        }
      >
        {provider.usesModel ? (
          <form.Field name="model">
            {(field) => (
              <ModelField models={models} value={field.state.value} onChange={field.handleChange} />
            )}
          </form.Field>
        ) : (
          <>
            <form.AppField name="endpoint">
              {(field) => (
                <field.TextField
                  label={ui("Địa chỉ dịch vụ")}
                  required
                  maxLength={2048}
                  placeholder={endpointHints[provider.provider]}
                />
              )}
            </form.AppField>
            {provider.requiresKey && (
              <form.AppField name="key">
                {(field) => (
                  <field.TextField
                    label={ui("Khóa API")}
                    type="password"
                    autoComplete="new-password"
                    maxLength={8192}
                    required={!keySaved}
                    placeholder={keySaved ? ui("Đã lưu khóa; để trống để giữ nguyên") : ""}
                  />
                )}
              </form.AppField>
            )}
            <form.Field name="dataBoundary">
              {(field) => (
                <DataBoundaryField value={field.state.value} onChange={field.handleChange} />
              )}
            </form.Field>
          </>
        )}
      </ConnectionForm>
    </ConnectionDialog>
  );
}

/** The model a connection reads with, among the models that read images, grouped by their provider. */
function ModelField({
  models,
  value,
  onChange,
}: {
  models: VisionModel[];
  value: string;
  onChange: (value: string) => void;
}) {
  const ui = useAppTranslation();
  const providers = [...new Set(models.map((model) => model.providerName))];
  return (
    <Field>
      <FieldLabel htmlFor="ocr-model">{ui("Model")}</FieldLabel>
      <Select value={value} onValueChange={onChange}>
        <SelectTrigger id="ocr-model" aria-label={ui("Model")} className="w-full">
          <SelectValue placeholder={ui("Chọn model đọc được ảnh")} />
        </SelectTrigger>
        <SelectContent>
          {providers.map((provider) => (
            <SelectGroup key={provider}>
              <SelectLabel>{provider}</SelectLabel>
              {models
                .filter((model) => model.providerName === provider)
                .map((model) => (
                  <SelectItem key={model.id} value={model.id}>
                    {model.name}
                  </SelectItem>
                ))}
            </SelectGroup>
          ))}
        </SelectContent>
      </Select>
    </Field>
  );
}
