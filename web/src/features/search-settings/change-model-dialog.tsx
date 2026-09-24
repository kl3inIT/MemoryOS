import { useQueryClient } from "@tanstack/react-query";
import { PlugZap } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { createSearchFutureGeneration } from "@/lib/hey-api/sdk.gen";
import type { EmbeddingProviderResponse } from "@/lib/hey-api/types.gen";
import { CatalogDialog } from "@/features/models/catalog-dialog";
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
        className="space-y-4"
        onSubmit={(event) => {
          event.preventDefault();
          if (parsed.request && !unchanged) setConfirming(true);
        }}
      >
        <label className="block space-y-1">
          {ui("Provider embedding")}
          <Select
            value={draft.providerId}
            aria-invalid={problems.has("provider")}
            onChange={(event) => update({ providerId: event.target.value })}
          >
            {providers.map((entry) => (
              <option key={entry.id} value={entry.id}>
                {entry.name}
              </option>
            ))}
          </Select>
        </label>
        {provider && (
          <p className="-mt-2 flex min-w-0 flex-wrap items-center gap-2 font-secondary-body text-content-muted">
            <DataBoundaryTag boundary={provider.dataBoundary} />
            <span className="min-w-0 break-all">{provider.endpoint}</span>
          </p>
        )}
        <label className="block space-y-1">
          {ui("Model đã biết")}
          <Select
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
          </Select>
        </label>
        <div className="grid gap-3 sm:grid-cols-[1fr_8rem]">
          <label className="block min-w-0 space-y-1">
            {ui("Tên model")}
            <Input
              required
              spellCheck={false}
              value={draft.model}
              aria-invalid={problems.has("model")}
              onChange={(event) => update({ model: event.target.value })}
            />
          </label>
          <label className="block space-y-1">
            {ui("Số chiều")}
            <Input
              required
              inputMode="numeric"
              value={draft.dimensions}
              aria-invalid={problems.has("dimensions")}
              onChange={(event) => update({ dimensions: event.target.value })}
            />
          </label>
        </div>
        <label className="block space-y-1">
          {ui("Tiền tố câu hỏi")}
          <Textarea
            rows={2}
            spellCheck={false}
            className="font-mono text-xs md:text-xs"
            value={draft.queryPrefix}
            onChange={(event) => update({ queryPrefix: event.target.value })}
          />
        </label>
        <label className="block space-y-1">
          {ui("Tiền tố tài liệu")}
          <Textarea
            rows={1}
            spellCheck={false}
            className="font-mono text-xs md:text-xs"
            value={draft.documentPrefix}
            onChange={(event) => update({ documentPrefix: event.target.value })}
          />
        </label>
        <label className="block space-y-1 sm:w-48">
          {ui("Ngưỡng ngữ nghĩa")}
          <Input
            required
            type="number"
            min={0}
            max={1}
            step={0.01}
            value={draft.minimumSemanticScore}
            aria-invalid={problems.has("score")}
            onChange={(event) => update({ minimumSemanticScore: event.target.value })}
          />
        </label>
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
            <PlugZap aria-hidden="true" />
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
            await createSearchFutureGeneration({
              body: parsed.request!,
            });
            await refreshSearchSettings(client);
            onClose();
          }}
          errorMessage={(cause) => searchSettingsProblem(cause, "create")}
        />
      )}
    </CatalogDialog>
  );
}
