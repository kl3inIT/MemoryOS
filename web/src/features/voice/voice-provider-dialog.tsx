import { useStore } from "@tanstack/react-form";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useAppForm } from "@/components/form/app-form";
import { useFieldValidity } from "@/components/form/form-context";
import { ConnectionDialog, ConnectionForm } from "@/components/composites/connection-form";
import {
  Field,
  FieldDescription,
  FieldError,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  saveChatVoiceConnectionMutation,
  selectChatVoiceProviderMutation,
  testChatVoiceConnectionMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { VoiceConnectionResponse, VoiceProviderResponse } from "@/lib/hey-api/types.gen";
import { canServe, invalidateVoice, voiceProblem, type VoiceFunction } from "./voice-providers";

/** Saving a connection and, once no default exists, making an existing one the default. */
function useVoiceConnectionMutations() {
  const cache = useQueryClient();
  return {
    save: useMutation({
      ...saveChatVoiceConnectionMutation(),
      onSuccess: () => invalidateVoice(cache),
    }),
    select: useMutation({
      ...selectChatVoiceProviderMutation(),
      onSuccess: () => invalidateVoice(cache),
    }),
    test: useMutation(testChatVoiceConnectionMutation()),
  };
}

/**
 * Connect or edit one provider for one function. The server verifies the credential before it stores anything; the
 * other function's model and voice are sent back unchanged.
 */
export function VoiceProviderDialog({
  fn,
  name,
  provider,
  connection,
  autoSelect,
  onClose,
}: {
  fn: VoiceFunction;
  name: string;
  provider: VoiceProviderResponse;
  connection?: VoiceConnectionResponse;
  autoSelect: boolean;
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const { save, select, test } = useVoiceConnectionMutations();
  const form = useAppForm({
    defaultValues: {
      endpoint: connection?.endpoint ?? "",
      key: "",
      removeKey: false,
      model:
        fn === "STT"
          ? connection?.sttModel || provider.sttModels[0] || ""
          : connection?.ttsModel || provider.ttsModels[0] || "",
      voice: connection?.ttsVoice || provider.voices[0] || "",
    },
    onSubmit: async ({ value }) => {
      test.reset();
      select.reset();
      const body = {
        endpoint: value.endpoint.trim(),
        sttModel: fn === "STT" ? value.model.trim() : (connection?.sttModel ?? ""),
        ttsModel: fn === "TTS" ? value.model.trim() : (connection?.ttsModel ?? ""),
        ttsVoice: fn === "TTS" ? value.voice.trim() : (connection?.ttsVoice ?? ""),
      };
      const becomesDefault =
        autoSelect &&
        canServe(provider, fn, {
          ...body,
          credential: value.key !== "" || (!!connection?.credentialConfigured && !value.removeKey),
        });
      try {
        await save.mutateAsync({
          path: { provider: provider.provider },
          body: {
            ...body,
            credentialAction: value.key ? "REPLACE" : value.removeKey ? "REMOVE" : "KEEP",
            credentialValue: value.key || undefined,
            activate: becomesDefault && !connection ? fn : undefined,
            revision: connection?.revision ?? 0,
          },
        });
        // The server activates only newly created rows; an existing row is selected explicitly.
        if (becomesDefault && connection)
          await select.mutateAsync({ body: { function: fn, provider: provider.provider } });
        onClose();
      } catch {
        // The failure stays on its mutation and shows in the form.
      }
    },
  });
  const removeKey = useStore(form.store, (state) => state.values.removeKey);
  const submitting = useStore(form.store, (state) => state.isSubmitting);
  const busy = submitting || test.isPending;
  const failure = save.error ?? select.error ?? test.error;
  const azure = provider.provider === "AZURE";

  return (
    <ConnectionDialog open onOpenChange={(next) => !next && onClose()} busy={busy} wide>
      <ConnectionForm
        title={connection ? ui("Cấu hình {{name}}", { name }) : ui("Kết nối {{name}}", { name })}
        description={
          fn === "STT"
            ? ui("Dùng để nhận dạng giọng nói trong Chat và Tìm kiếm.")
            : ui("Dùng để đọc câu trả lời thành tiếng.")
        }
        onSubmit={() => void form.handleSubmit()}
        saving={submitting}
        busy={busy}
        onTest={
          connection
            ? () => {
                save.reset();
                select.reset();
                test.mutate({ path: { provider: provider.provider } });
              }
            : undefined
        }
        tested={test.isSuccess}
        error={failure ? voiceProblem(failure) : undefined}
        onClose={onClose}
      >
        <FieldSet>
          <FieldLegend variant="label">{ui("Thông tin kết nối")}</FieldLegend>
          <form.AppField name="endpoint">
            {(field) => (
              <field.TextField
                label={
                  azure
                    ? ui("Địa chỉ tài nguyên Speech")
                    : provider.requiresEndpoint
                      ? ui("Địa chỉ máy chủ")
                      : ui("Địa chỉ API")
                }
                required={provider.requiresEndpoint}
                maxLength={2048}
                inputMode="url"
                placeholder={
                  provider.defaultEndpoint ||
                  (azure
                    ? "https://your-resource.cognitiveservices.azure.com"
                    : "http://speaches.internal:8000/v1")
                }
                description={
                  azure
                    ? ui("Endpoint của tài nguyên Azure AI Speech, trong mục Keys and Endpoint.")
                    : provider.requiresEndpoint
                      ? ui("Địa chỉ gốc của API tương thích OpenAI, thường kết thúc bằng /v1.")
                      : ui("Để trống để dùng địa chỉ mặc định của nhà cung cấp.")
                }
              />
            )}
          </form.AppField>
          <form.AppField name="key">
            {(field) => (
              <field.TextField
                label={provider.requiresKey ? ui("Khóa API") : ui("Khóa API (không bắt buộc)")}
                type="password"
                autoComplete="new-password"
                maxLength={8192}
                required={provider.requiresKey && !connection?.credentialConfigured}
                disabled={removeKey}
                placeholder={
                  connection?.credentialConfigured ? ui("Đã lưu khóa; để trống để giữ nguyên") : ""
                }
              />
            )}
          </form.AppField>
          {!provider.requiresKey && connection?.credentialConfigured && (
            <form.AppField
              name="removeKey"
              listeners={{ onChange: ({ value }) => value && form.setFieldValue("key", "") }}
            >
              {(field) => <field.CheckboxField label={ui("Xóa khóa đã lưu")} />}
            </form.AppField>
          )}
        </FieldSet>
        <FieldSet>
          <FieldLegend variant="label">{ui("Mô hình và giọng")}</FieldLegend>
          <form.AppField name="model">
            {() => (
              <ChoiceField
                label={fn === "STT" ? ui("Mô hình nhận dạng") : ui("Mô hình giọng nói")}
                options={fn === "STT" ? provider.sttModels : provider.ttsModels}
                hint={ui("Nhập đúng tên mô hình mà máy chủ cung cấp.")}
              />
            )}
          </form.AppField>
          {fn === "TTS" && (
            <form.AppField name="voice">
              {() => (
                <ChoiceField
                  label={ui("Giọng đọc")}
                  options={provider.voices}
                  hint={
                    provider.provider === "ELEVENLABS"
                      ? ui("Voice ID trong thư viện giọng của ElevenLabs.")
                      : ui("Nhập đúng tên giọng mà máy chủ cung cấp.")
                  }
                />
              )}
            </form.AppField>
          )}
        </FieldSet>
      </ConnectionForm>
    </ConnectionDialog>
  );
}

/** Known identifiers as a list; OpenAI-compatible models and ElevenLabs voice IDs are typed. */
function ChoiceField({ label, options, hint }: { label: string; options: string[]; hint: string }) {
  const { field, invalid, errors } = useFieldValidity<string>();
  const value = field.state.value;
  const choices =
    options.length > 0 && value && !options.includes(value) ? [value, ...options] : options;
  const control = {
    id: field.name,
    name: field.name,
    value,
    required: true,
    "aria-invalid": invalid || undefined,
    onBlur: field.handleBlur,
  };
  return (
    <Field data-invalid={invalid || undefined}>
      <FieldLabel htmlFor={field.name}>{label}</FieldLabel>
      {choices.length > 0 ? (
        <NativeSelect {...control} onChange={(event) => field.handleChange(event.target.value)}>
          {choices.map((choice) => (
            <option key={choice} value={choice}>
              {choice}
            </option>
          ))}
        </NativeSelect>
      ) : (
        <>
          <Input
            {...control}
            maxLength={200}
            onChange={(event) => field.handleChange(event.target.value)}
          />
          <FieldDescription>{hint}</FieldDescription>
        </>
      )}
      {invalid ? <FieldError errors={errors} /> : null}
    </Field>
  );
}
