import { useMutation, useQueryClient } from "@tanstack/react-query";
import { PlugZap } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";
import { Textarea } from "@/components/ui/textarea";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { createSearchFutureGenerationMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { EmbeddingProviderResponse } from "@/lib/hey-api/types.gen";
import { CatalogDialog } from "@/components/composites/catalog-dialog";
import { DataBoundaryTag } from "@/features/models/data-boundary";
import { useEmbeddingTest } from "./embedding-connection";
import { ConnectionOutcome } from "./embedding-provider-editor";
import {
  applyPreset,
  draftFrom,
  generationRequest,
  matchingPreset,
  refreshSearchSettings,
  searchSettingsProblem,
  type Generation,
  type GenerationDraft,
  type ModelPreset,
} from "./search-settings";

const custom = "";

/** Choose the next model; confirming starts the rebuild while search keeps using the present index. */
export function ChangeModelDialog({
  present,
  providers,
  presets,
  onClose,
}: {
  present: Generation;
  providers: EmbeddingProviderResponse[];
  presets: ModelPreset[];
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const client = useQueryClient();
  const connection = useEmbeddingTest();
  const create = useMutation({
    ...createSearchFutureGenerationMutation(),
    onSuccess: () => refreshSearchSettings(client),
  });
  const [draft, setDraft] = useState<GenerationDraft>(() => draftFrom(present));
  const [confirming, setConfirming] = useState(false);
  const parsed = generationRequest(draft);
  const problems = new Set(parsed.problems);
  const preset = matchingPreset(draft, presets);
  const provider = providers.find((entry) => entry.id === draft.providerId);
  const unchanged =
    parsed.request != null &&
    parsed.request.providerId === present.providerId &&
    parsed.request.model === present.model &&
    parsed.request.dimensions === present.dimensions &&
    parsed.request.queryPrefix === present.queryPrefix &&
    parsed.request.documentPrefix === present.documentPrefix;

  function update(next: Partial<GenerationDraft>) {
    setDraft((current) => ({ ...current, ...next }));
    connection.reset();
  }

  return (
    <CatalogDialog title={ui("Đổi model embedding")} onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          if (parsed.request && !unchanged) setConfirming(true);
        }}
      >
        <FieldGroup>
          <Field data-invalid={problems.has("provider") || undefined}>
            <FieldLabel htmlFor="generation-provider">{ui("Provider embedding")}</FieldLabel>
            <NativeSelect
              id="generation-provider"
              value={draft.providerId}
              aria-invalid={problems.has("provider")}
              onChange={(event) => update({ providerId: event.target.value })}
            >
              {providers.map((entry) => (
                <option key={entry.id} value={entry.id}>
                  {entry.name}
                </option>
              ))}
            </NativeSelect>
            {provider && (
              <FieldDescription>
                <span className="flex min-w-0 flex-wrap items-center gap-2">
                  <DataBoundaryTag boundary={provider.dataBoundary} />
                  <span className="min-w-0 break-all">{provider.endpoint}</span>
                </span>
              </FieldDescription>
            )}
          </Field>
          <Field>
            <FieldLabel htmlFor="generation-preset">{ui("Model đã biết")}</FieldLabel>
            <NativeSelect
              id="generation-preset"
              value={preset?.model ?? custom}
              onChange={(event) => {
                const chosen = presets.find((entry) => entry.model === event.target.value);
                if (chosen) {
                  setDraft((current) => applyPreset(current, chosen));
                  connection.reset();
                }
              }}
            >
              <option value={custom}>{ui("Tuỳ chỉnh")}</option>
              {presets.map((entry) => (
                <option key={entry.model} value={entry.model}>
                  {entry.label}
                </option>
              ))}
            </NativeSelect>
          </Field>
          <div className="grid gap-3 sm:grid-cols-4">
            <Field
              className="min-w-0 sm:col-span-3"
              data-invalid={problems.has("model") || undefined}
            >
              <FieldLabel htmlFor="generation-model">{ui("Tên model")}</FieldLabel>
              <Input
                id="generation-model"
                required
                spellCheck={false}
                value={draft.model}
                aria-invalid={problems.has("model")}
                onChange={(event) => update({ model: event.target.value })}
              />
            </Field>
            <Field data-invalid={problems.has("dimensions") || undefined}>
              <FieldLabel htmlFor="generation-dimensions">{ui("Số chiều")}</FieldLabel>
              <Input
                id="generation-dimensions"
                required
                inputMode="numeric"
                value={draft.dimensions}
                aria-invalid={problems.has("dimensions")}
                onChange={(event) => update({ dimensions: event.target.value })}
              />
            </Field>
          </div>
          <Field>
            <FieldLabel htmlFor="generation-query-prefix">{ui("Tiền tố câu hỏi")}</FieldLabel>
            <Textarea
              id="generation-query-prefix"
              variant="mono"
              rows={2}
              spellCheck={false}
              value={draft.queryPrefix}
              onChange={(event) => update({ queryPrefix: event.target.value })}
            />
          </Field>
          <Field>
            <FieldLabel htmlFor="generation-document-prefix">{ui("Tiền tố tài liệu")}</FieldLabel>
            <Textarea
              id="generation-document-prefix"
              variant="mono"
              rows={1}
              spellCheck={false}
              value={draft.documentPrefix}
              onChange={(event) => update({ documentPrefix: event.target.value })}
            />
          </Field>
          <Field className="sm:w-48" data-invalid={problems.has("score") || undefined}>
            <FieldLabel htmlFor="generation-score">{ui("Ngưỡng ngữ nghĩa")}</FieldLabel>
            <Input
              id="generation-score"
              required
              type="number"
              min={0}
              max={1}
              step={0.01}
              value={draft.minimumSemanticScore}
              aria-invalid={problems.has("score")}
              onChange={(event) => update({ minimumSemanticScore: event.target.value })}
            />
          </Field>
        </FieldGroup>
        <ConnectionOutcome outcome={connection.outcome} />
        <div className="flex flex-wrap justify-end gap-2">
          <Button
            prominence="secondary"
            className="mr-auto"
            pending={connection.pending}
            disabled={!parsed.request}
            onClick={() => {
              if (parsed.request)
                void connection.run({
                  providerId: parsed.request.providerId,
                  endpoint: null,
                  apiKey: null,
                  model: parsed.request.model,
                  dimensions: parsed.request.dimensions,
                });
            }}
          >
            <PlugZap data-icon="inline-start" aria-hidden="true" />
            {ui("Kiểm tra")}
          </Button>
          <Button prominence="secondary" onClick={onClose}>
            {ui("Đóng")}
          </Button>
          <Button type="submit" disabled={!parsed.request || unchanged}>
            {ui("Dựng lại index")}
          </Button>
        </div>
      </form>
      {confirming && parsed.request && (
        <ConfirmDialog
          open
          onOpenChange={(open) => {
            if (!open) setConfirming(false);
          }}
          title={ui(appText("Dựng lại index với {{model}}?", { model: parsed.request.model }))}
          description={ui(
            appText("Tìm kiếm vẫn dùng {{current}} cho tới khi chuyển.", {
              current: present.model,
            }),
          )}
          confirmTone="default"
          confirmLabel={ui("Bắt đầu dựng lại")}
          pendingLabel={ui("Đang bắt đầu")}
          onConfirm={async () => {
            await create.mutateAsync({ body: parsed.request! });
            onClose();
          }}
          errorMessage={(cause) => searchSettingsProblem(cause, "create")}
        />
      )}
    </CatalogDialog>
  );
}
