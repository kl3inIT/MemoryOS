import { useState } from "react";
import { CheckCircle2, Server, Trash2 } from "lucide-react";
import { ProviderLogo } from "@/components/provider-logos/provider-logo";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import { StatusBadge } from "@/components/ui/status-badge";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { sameOriginMutationHeaders } from "@/lib/api";
import { deleteChatVoiceConnection } from "@/lib/hey-api/sdk.gen";
import type { VoiceConnectionResponse, VoiceProviderResponse } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { VoiceProviderDialog } from "./voice-provider-dialog";
import {
  connectionServes,
  endpointHost,
  isDefault,
  voiceProblem,
  type VoiceFunction,
  type VoiceProviderId,
} from "./voice-providers";

export function VoiceProviderCard({
  fn,
  provider,
  connection,
  autoSelect,
  disabled,
  onSelect,
  onChanged,
}: {
  fn: VoiceFunction;
  provider: VoiceProviderResponse;
  connection?: VoiceConnectionResponse;
  /** No default exists for this function yet, so a successful connection becomes the default. */
  autoSelect: boolean;
  disabled: boolean;
  onSelect: (provider: VoiceProviderId) => Promise<void>;
  onChanged: () => Promise<void>;
}) {
  const ui = useAppTranslation();
  const [open, setOpen] = useState(false);
  const name = provider.provider === "OPENAI" ? "OpenAI" : ui("Tương thích OpenAI");
  const active = isDefault(connection, fn);
  const ready = connectionServes(provider, fn, connection);
  const detail = connection
    ? [
        ...(fn === "STT" ? [connection.sttModel] : [connection.ttsModel, connection.ttsVoice]),
        endpointHost(connection.endpoint || provider.defaultEndpoint),
      ]
        .filter(Boolean)
        .join(" · ")
    : provider.provider === "OPENAI"
      ? fn === "STT"
        ? ui("Whisper và GPT-4o Transcribe")
        : ui("TTS-1 và TTS-1 HD")
      : ui("Máy chủ tự vận hành có API âm thanh tương thích OpenAI, ví dụ Speaches");
  return (
    <li
      aria-label={name}
      className={cn(
        "flex flex-wrap items-center gap-x-3 gap-y-2 px-4 py-3.5",
        active && "bg-surface-sunken",
      )}
    >
      <span className="flex size-9 shrink-0 items-center justify-center rounded-lg border border-border-subtle bg-surface-base">
        {provider.provider === "OPENAI" ? (
          <ProviderLogo mark="OPENAI" className="size-5" />
        ) : (
          <Server className="size-5 text-content-secondary" aria-hidden="true" />
        )}
      </span>
      <div className="mr-auto min-w-0 flex-1 basis-48">
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="font-main-ui-action text-content-primary">{name}</h3>
          {active ? (
            <StatusBadge tone="success" className="gap-1 text-xs">
              <CheckCircle2 className="size-3.5" aria-hidden="true" />
              {ui("Mặc định")}
            </StatusBadge>
          ) : connection && !ready ? (
            <StatusBadge tone="warning" className="text-xs">
              {ui("Cần cấu hình thêm")}
            </StatusBadge>
          ) : connection ? (
            <StatusBadge tone="neutral" className="text-xs">
              {ui("Đã kết nối")}
            </StatusBadge>
          ) : null}
        </div>
        <p className="mt-0.5 truncate text-sm text-content-muted">{detail}</p>
      </div>
      <div className="flex items-center gap-1.5">
        {ready && !active && (
          <Button
            size="sm"
            prominence="secondary"
            disabled={disabled}
            onClick={() => void onSelect(provider.provider)}
          >
            {ui("Đặt làm mặc định")}
          </Button>
        )}
        <Button
          size="sm"
          prominence={connection ? "internal" : "secondary"}
          disabled={disabled}
          onClick={() => setOpen(true)}
        >
          {connection ? ui("Cấu hình") : ui("Kết nối")}
        </Button>
        {connection && (
          <ConfirmDialog
            trigger={
              <IconButton
                size="sm"
                tone="danger"
                disabled={disabled}
                aria-label={ui("Ngắt kết nối {{name}}", { name })}
                title={ui("Ngắt kết nối {{name}}", { name })}
              >
                <Trash2 />
              </IconButton>
            }
            title={ui("Ngắt kết nối {{name}}?", { name })}
            description={ui(
              "Khóa và cấu hình của {{name}} sẽ bị xóa khỏi cả nhận dạng giọng nói lẫn đọc thành tiếng. Chức năng đang dùng {{name}} làm mặc định sẽ tắt cho đến khi bạn chọn nhà cung cấp khác.",
              { name },
            )}
            confirmLabel={ui("Ngắt kết nối")}
            pendingLabel={ui("Đang ngắt kết nối…")}
            errorMessage={voiceProblem}
            onConfirm={async () => {
              await deleteChatVoiceConnection({
                path: { provider: provider.provider },
                query: { revision: connection.revision ?? 0 },
                headers: sameOriginMutationHeaders,
                throwOnError: true,
              });
              await onChanged();
            }}
          />
        )}
      </div>
      <VoiceProviderDialog
        open={open}
        onOpenChange={setOpen}
        fn={fn}
        name={name}
        provider={provider}
        connection={connection}
        autoSelect={autoSelect}
        onSaved={onChanged}
      />
    </li>
  );
}
