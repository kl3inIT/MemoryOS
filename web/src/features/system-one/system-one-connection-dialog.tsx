import { useStore } from "@tanstack/react-form";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useAppForm } from "@/components/form/app-form";
import { ConnectionDialog, ConnectionForm } from "@/components/composites/connection-form";
import { DataBoundaryField } from "@/features/models/data-boundary";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  createSystemOneConnectionMutation,
  saveSystemOneConnectionMutation,
  testSystemOneConnectionMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SystemOneConnection, SystemOneType } from "@/lib/hey-api/types.gen";
import {
  endpointPlaceholder,
  invalidateSystemOne,
  modelPlaceholder,
  providerNames,
  systemOneProblem,
} from "./system-one";

/** A price as the field shows it; empty when unknown. */
function priceText(price: number | undefined) {
  return price === undefined ? "" : String(price);
}

/** Add a connection of one type, or change a saved one. Only a saved connection can be tested. */
export function SystemOneConnectionDialog({
  type,
  connection,
  takenNames,
  onClose,
}: {
  type: SystemOneType;
  connection?: SystemOneConnection;
  /** The other connections' names, lower-cased: a name is unique in the organization. */
  takenNames: readonly string[];
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const create = useMutation({
    ...createSystemOneConnectionMutation(),
    onSuccess: () => invalidateSystemOne(cache),
  });
  const save = useMutation({
    ...saveSystemOneConnectionMutation(),
    onSuccess: () => invalidateSystemOne(cache),
  });
  const test = useMutation(testSystemOneConnectionMutation());
  const provider = providerNames[type.provider];
  // A second connection of a type starts with a name that is still free.
  let suggested = provider;
  for (let count = 2; takenNames.includes(suggested.toLowerCase()); count++)
    suggested = `${provider} ${count}`;
  const form = useAppForm({
    defaultValues: {
      name: connection?.name ?? suggested,
      endpoint: connection?.endpoint ?? "",
      key: "",
      removeKey: false,
      model: connection?.model ?? type.defaultModel,
      dataBoundary: connection?.dataBoundary ?? (type.requiresKey ? "EXTERNAL" : "INTERNAL"),
      inputPrice: priceText(connection ? connection.inputPrice : type.inputPrice),
    },
    onSubmit: async ({ value }) => {
      test.reset();
      const price = value.inputPrice.trim().replace(",", ".");
      const body = {
        name: value.name.trim(),
        endpoint: value.endpoint.trim(),
        model: value.model.trim(),
        credentialAction: value.key ? "REPLACE" : value.removeKey ? "REMOVE" : "KEEP",
        credentialValue: value.key || undefined,
        dataBoundary: value.dataBoundary,
        inputPrice: price === "" ? undefined : Number(price),
        revision: connection?.revision ?? 0,
      } as const;
      try {
        if (connection) await save.mutateAsync({ path: { id: connection.id }, body });
        else await create.mutateAsync({ path: { provider: type.provider }, body });
        onClose();
      } catch {
        // The failure stays on its mutation and shows in the form.
      }
    },
  });
  const removeKey = useStore(form.store, (state) => state.values.removeKey);
  const submitting = useStore(form.store, (state) => state.isSubmitting);
  const busy = submitting || test.isPending;
  const failure = create.error ?? save.error ?? test.error;

  return (
    <ConnectionDialog open onOpenChange={(next) => !next && onClose()} busy={busy} wide>
      <ConnectionForm
        title={
          connection
            ? ui("Cấu hình {{name}}", { name: connection.name })
            : ui("Kết nối {{name}}", { name: provider })
        }
        description={ui("Dùng để phân loại câu hỏi trước khi trợ lý trả lời.")}
        onSubmit={() => void form.handleSubmit()}
        saving={submitting}
        submitLabel={connection ? undefined : ui("Kết nối")}
        busy={busy}
        onTest={
          connection
            ? () => {
                save.reset();
                test.mutate({ path: { id: connection.id } });
              }
            : undefined
        }
        tested={test.isSuccess}
        error={failure ? systemOneProblem(failure) : undefined}
        onClose={onClose}
      >
        <form.AppField
          name="name"
          validators={{
            onChange: ({ value }) =>
              takenNames.includes(value.trim().toLowerCase())
                ? ui("Đã có kết nối mang tên này.")
                : undefined,
          }}
        >
          {(field) => <field.TextField label={ui("Name")} required maxLength={80} />}
        </form.AppField>
        {type.endpoint !== "FIXED" && (
          <form.AppField name="endpoint">
            {(field) => (
              <field.TextField
                label={type.endpoint === "ACCOUNT" ? ui("Account ID") : ui("Endpoint")}
                required
                maxLength={2048}
                inputMode={type.endpoint === "URL" ? "url" : undefined}
                placeholder={endpointPlaceholder[type.provider]}
              />
            )}
          </form.AppField>
        )}
        <form.AppField name="key">
          {(field) => (
            <field.TextField
              label={type.requiresKey ? ui("Khóa API") : ui("Khóa API (không bắt buộc)")}
              type="password"
              autoComplete="new-password"
              maxLength={8192}
              required={type.requiresKey && !connection?.credentialConfigured}
              disabled={removeKey}
              placeholder={
                connection?.credentialConfigured ? ui("Đã lưu khóa; để trống để giữ nguyên") : ""
              }
            />
          )}
        </form.AppField>
        {!type.requiresKey && connection?.credentialConfigured && (
          <form.AppField
            name="removeKey"
            listeners={{ onChange: ({ value }) => value && form.setFieldValue("key", "") }}
          >
            {(field) => <field.CheckboxField label={ui("Xóa khóa đã lưu")} />}
          </form.AppField>
        )}
        <form.AppField name="model">
          {(field) => (
            <field.TextField
              label={ui("Model")}
              required={type.defaultModel === ""}
              maxLength={200}
              placeholder={type.defaultModel || modelPlaceholder[type.provider]}
            />
          )}
        </form.AppField>
        <form.AppField
          name="inputPrice"
          validators={{
            onChange: ({ value }) => {
              const text = value.trim().replace(",", ".");
              return text === "" || (Number.isFinite(Number(text)) && Number(text) >= 0)
                ? undefined
                : ui("Nhập một số không âm.");
            },
          }}
        >
          {(field) => (
            <field.TextField
              label={ui("Giá input (USD / 1M token)")}
              inputMode="decimal"
              maxLength={16}
            />
          )}
        </form.AppField>
        <form.Field name="dataBoundary">
          {(field) => <DataBoundaryField value={field.state.value} onChange={field.handleChange} />}
        </form.Field>
      </ConnectionForm>
    </ConnectionDialog>
  );
}
