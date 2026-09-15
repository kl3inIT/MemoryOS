import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Slider } from "@/components/ui/slider";
import { Switch } from "@/components/ui/switch";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { sameOriginMutationHeaders } from "@/lib/api";
import { updateChatVoiceSettings } from "@/lib/hey-api/sdk.gen";
import type { VoiceSettingsRequest } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { useVoiceAvailability } from "./use-voice-availability";
import { useVoiceSettings, useVoiceSettingsKey } from "./use-voice-settings";

/**
 * Personal voice preferences in General settings. Each control appears only while the Tenant has the provider that uses
 * it: Auto-Send with speech to text, reading speed with text to speech.
 */
export function VoiceSettingsSection() {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const cache = useQueryClient();
  const availability = useVoiceAvailability().data;
  const dictation = availability?.sttAvailable === true;
  const readAloud = availability?.ttsAvailable === true;
  const settings = useVoiceSettings(dictation || readAloud);
  const key = useVoiceSettingsKey();
  const [speedDraft, setSpeedDraft] = useState<number>();
  const mutation = useMutation({
    mutationFn: async (change: VoiceSettingsRequest) =>
      (
        await updateChatVoiceSettings({
          body: change,
          headers: sameOriginMutationHeaders,
          throwOnError: true,
        })
      ).data,
    onSuccess: (data) => cache.setQueryData(key, data),
    onSettled: () => setSpeedDraft(undefined),
  });
  if (!dictation && !readAloud) return null;
  const saved = settings.data;
  const pending = mutation.isPending ? mutation.variables : undefined;
  const autoSend = pending?.autoSend ?? saved?.autoSend ?? false;
  const autoPlayback = pending?.autoPlayback ?? saved?.autoPlayback ?? false;
  const speed = speedDraft ?? pending?.playbackSpeed ?? saved?.playbackSpeed ?? 1;
  const disabled = !settings.isSuccess || mutation.isPending;
  return (
    <section aria-labelledby="voice-settings-heading" className="flex max-w-2xl flex-col gap-6">
      <h2 id="voice-settings-heading" className="font-heading-h3 text-content-primary">
        {ui("Giọng nói")}
      </h2>
      {dictation && (
        <div className="flex items-start justify-between gap-6">
          <div className="min-w-0">
            <label htmlFor="voice-auto-send" className="font-main-ui-body text-content-primary">
              {ui("Tự động gửi khi dừng ghi âm")}
            </label>
            <p id="voice-auto-send-description" className="mt-1 text-content-muted">
              {ui("Câu hỏi được gửi ngay khi văn bản nhận dạng xong, không cần bấm Gửi.")}
            </p>
          </div>
          <Switch
            id="voice-auto-send"
            aria-describedby="voice-auto-send-description"
            checked={autoSend}
            disabled={disabled}
            onCheckedChange={(checked) => mutation.mutate({ autoSend: checked })}
          />
        </div>
      )}
      {readAloud && (
        <div className="flex items-start justify-between gap-6">
          <div className="min-w-0">
            <label htmlFor="voice-auto-playback" className="font-main-ui-body text-content-primary">
              {ui("Tự động đọc câu trả lời")}
            </label>
            <p id="voice-auto-playback-description" className="mt-1 text-content-muted">
              {ui(
                "Câu trả lời mới được đọc ngay khi đang được tạo. Nếu bạn vừa hỏi bằng micro, micro sẽ tự bật lại sau khi đọc xong.",
              )}
            </p>
          </div>
          <Switch
            id="voice-auto-playback"
            aria-describedby="voice-auto-playback-description"
            checked={autoPlayback}
            disabled={disabled}
            onCheckedChange={(checked) => mutation.mutate({ autoPlayback: checked })}
          />
        </div>
      )}
      {readAloud && (
        <div className="flex flex-col gap-3">
          <div className="flex items-start justify-between gap-6">
            <div className="min-w-0">
              <p className="font-main-ui-body text-content-primary">{ui("Tốc độ đọc")}</p>
              <p id="voice-speed-description" className="mt-1 text-content-muted">
                {ui("Áp dụng khi đọc câu trả lời thành tiếng.")}
              </p>
            </div>
            <output
              htmlFor="voice-speed"
              className="shrink-0 font-main-ui-action text-content-primary tabular-nums"
            >
              {speedLabel(speed)}
            </output>
          </div>
          <Slider
            id="voice-speed"
            aria-label={ui("Tốc độ đọc")}
            aria-describedby="voice-speed-description"
            className="max-w-sm"
            min={0.5}
            max={2}
            step={0.1}
            value={[speed]}
            disabled={disabled}
            onValueChange={([value]) => setSpeedDraft(tenths(value))}
            onValueCommit={([value]) => {
              if (tenths(value) === saved?.playbackSpeed) setSpeedDraft(undefined);
              else mutation.mutate({ playbackSpeed: tenths(value) });
            }}
          />
        </div>
      )}
      {settings.isError && (
        <div role="alert" className="flex flex-wrap items-center gap-3">
          <p className="text-content-secondary">{ui("Không tải được cài đặt giọng nói.")}</p>
          <Button size="sm" prominence="secondary" onClick={() => void settings.refetch()}>
            {ui("Tải lại")}
          </Button>
        </div>
      )}
      {mutation.isError && (
        <p role="alert" className="text-status-danger-content">
          {problemMessage(presentProblem(mutation.error, "mutation").message)}
        </p>
      )}
    </section>
  );
}

/** The server stores speed in tenths. */
function tenths(value: number) {
  return Math.round(value * 10) / 10;
}

/** One decimal and a multiplication sign, such as 1.2×; numerals need no translation. */
function speedLabel(speed: number) {
  return `${speed.toFixed(1)}×`;
}
