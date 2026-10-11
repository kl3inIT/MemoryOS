import { useState } from "react";
import { useStore } from "@tanstack/react-form";
import { ScanText } from "lucide-react";
import { useAppForm } from "@/components/form/app-form";
import {
  ConnectionCard,
  ConnectionDialog,
  ConnectionForm,
} from "@/components/composites/connection-form";
import { DataBoundaryField, DataBoundaryTag } from "@/features/models/data-boundary";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { OcrConnectionResponse, OcrProviderResponse } from "@/lib/hey-api/types.gen";
import { ocrProblem, ocrProviderNames, useOcrConnectionMutations } from "./use-ocr-connections";

const endpointHints: Record<OcrProviderResponse["provider"], string> = {
  PADDLEOCR_VL: "http://ocr.internal:8080",
};

/** One OCR provider: its card and its connection dialog. */
export function OcrConnectionCard({
  provider,
  connection,
  disabled,
  onSelect,
}: {
  provider: OcrProviderResponse;
  connection?: OcrConnectionResponse;
  disabled: boolean;
  onSelect: () => void;
}) {
  const [open, setOpen] = useState(false);
  const configured = !!connection && (!provider.requiresKey || !!connection.credentialConfigured);
  return (
    <ConnectionCard
      logo={<ScanText />}
      name={ocrProviderNames[provider.provider]}
      description={
        connection ? (
          <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <span className="min-w-0 break-all">{connection.endpoint}</span>
            <DataBoundaryTag boundary={connection.dataBoundary} />
          </span>
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
          provider={provider}
          connection={connection}
          disabled={disabled}
          onClose={() => setOpen(false)}
        />
      )}
    </ConnectionCard>
  );
}

function OcrConnectionDialog({
  provider,
  connection,
  disabled,
  onClose,
}: {
  provider: OcrProviderResponse;
  connection?: OcrConnectionResponse;
  disabled: boolean;
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const name = ocrProviderNames[provider.provider];
  const keySaved = !!connection?.credentialConfigured;
  const { save, test } = useOcrConnectionMutations();
  const form = useAppForm({
    defaultValues: {
      endpoint: connection?.endpoint ?? "",
      key: "",
      dataBoundary: connection?.dataBoundary ?? provider.dataBoundary,
    },
    onSubmit: async ({ value }) => {
      test.reset();
      try {
        await save.mutateAsync({
          path: { provider: provider.provider },
          body: {
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
  const testable =
    values.endpoint.trim().length > 0 &&
    (!provider.requiresKey || keySaved || values.key.trim().length > 0);
  const busy = disabled || save.isPending || test.isPending;
  const failure = save.error ?? test.error;
  return (
    <ConnectionDialog open onOpenChange={(next) => !next && onClose()} busy={busy}>
      <ConnectionForm
        title={name}
        description={<DataBoundaryTag boundary={values.dataBoundary} />}
        onSubmit={() => void form.handleSubmit()}
        saving={save.isPending}
        saveDisabled={!values.endpoint.trim()}
        busy={busy}
        onTest={() => {
          save.reset();
          test.mutate({
            path: { provider: provider.provider },
            body: { endpoint: values.endpoint.trim(), credentialValue: values.key || undefined },
          });
        }}
        testDisabled={!testable}
        tested={test.isSuccess}
        error={failure ? ocrProblem(failure) : undefined}
        onClose={onClose}
      >
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
          {(field) => <DataBoundaryField value={field.state.value} onChange={field.handleChange} />}
        </form.Field>
      </ConnectionForm>
    </ConnectionDialog>
  );
}
