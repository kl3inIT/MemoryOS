import { useState } from "react";
import { AudioWaveform, CheckCircle2, Cloud, Server, Trash2 } from "lucide-react";
import { ProviderCard } from "@/components/provider-logos/provider-card";
import { ProviderLogo } from "@/components/provider-logos/provider-logo";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import { StatusBadge } from "@/components/ui/status-badge";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { sameOriginMutationHeaders } from "@/lib/api";
import { deleteChatVoiceConnection } from "@/lib/hey-api/sdk.gen";
import type { VoiceConnectionResponse, VoiceProviderResponse } from "@/lib/hey-api/types.gen";
import { VoiceProviderDialog } from "./voice-provider-dialog";
import {
  connectionServes,
  endpointHost,
  isDefault,
  voiceProblem,
  type VoiceFunction,
  type VoiceProviderId,
} from "./voice-providers";

type Translate = ReturnType<typeof useAppTranslation>;

/** Product names stay untranslated; only the self-hosted protocol has a descriptive name. */
function providerName(provider: VoiceProviderId, ui: Translate) {
  switch (provider) {
    case "OPENAI":
      return "OpenAI";
    case "ELEVENLABS":
      return "ElevenLabs";
    case "AZURE":
      return "Azure AI Speech";
    default:
      return ui("Tương thích OpenAI");
  }
}

function providerSummary(provider: VoiceProviderId, fn: VoiceFunction, ui: Translate) {
  switch (provider) {
    case "OPENAI":
      return fn === "STT" ? ui("Whisper và GPT-4o Transcribe") : ui("TTS-1 và TTS-1 HD");
    case "ELEVENLABS":
      return fn === "STT" ? "Scribe v2, Scribe v1" : "Multilingual v2, Flash v2.5, Turbo v2.5";
    case "AZURE":
      return fn === "STT"
        ? ui("Nhận dạng tiếng Việt và tiếng Anh qua REST")
        : ui("Giọng Neural tiếng Việt và tiếng Anh");
    default:
      return ui("Máy chủ tự vận hành có API âm thanh tương thích OpenAI, ví dụ Speaches");
  }
}

/** Only OpenAI has a brand mark in the application; other providers use a neutral icon. */
function ProviderIcon({ provider }: { provider: VoiceProviderId }) {
  if (provider === "OPENAI") return <ProviderLogo mark="OPENAI" className="size-5" />;
  const Icon = provider === "ELEVENLABS" ? AudioWaveform : provider === "AZURE" ? Cloud : Server;
  return <Icon className="size-5 text-content-secondary" aria-hidden="true" />;
}

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
  const name = providerName(provider.provider, ui);
  const active = isDefault(connection, fn);
  const ready = connectionServes(provider, fn, connection);
  const detail = connection
    ? [
        ...(fn === "STT" ? [connection.sttModel] : [connection.ttsModel, connection.ttsVoice]),
        endpointHost(connection.endpoint || provider.defaultEndpoint),
      ]
        .filter(Boolean)
        .join(" · ")
    : providerSummary(provider.provider, fn, ui);
  return (
    <ProviderCard
      as="li"
      aria-label={name}
      className="h-full rounded-xl bg-surface-raised"
      logo={<ProviderIcon provider={provider.provider} />}
      name={
        <>
          <span>{name}</span>
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
        </>
      }
      description={detail}
      selected={active}
      actions={
        <>
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
        </>
      }
    >
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
    </ProviderCard>
  );
}
