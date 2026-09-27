import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Gauge, Mic2, Volume2 } from "lucide-react";
import { SectionHeader } from "@/components/composites/section-header";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { Button } from "@/components/ui/button";
import { Slider } from "@/components/ui/slider";
import { Switch } from "@/components/ui/switch";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  getChatVoiceSettingsQueryKey,
  updateChatVoiceSettingsMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { useVoiceAvailability } from "./use-voice-availability";
import { useVoiceSettings } from "./use-voice-settings";

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
  const [speedDraft, setSpeedDraft] = useState<number>();
  const mutation = useMutation({
    ...updateChatVoiceSettingsMutation(),
    onSuccess: (data) => cache.setQueryData(getChatVoiceSettingsQueryKey(), data),
    onSettled: () => setSpeedDraft(undefined),
  });
  const save = (body: NonNullable<typeof mutation.variables>["body"]) => mutation.mutate({ body });
  if (!dictation && !readAloud) return null;
  const saved = settings.data;
  const pending = mutation.isPending ? mutation.variables.body : undefined;
  const autoSend = pending?.autoSend ?? saved?.autoSend ?? false;
  const autoPlayback = pending?.autoPlayback ?? saved?.autoPlayback ?? false;
  const speed = speedDraft ?? pending?.playbackSpeed ?? saved?.playbackSpeed ?? 1;
  const disabled = !settings.isSuccess || mutation.isPending;
  return (
    <section aria-labelledby="voice-settings-heading" className="flex max-w-2xl flex-col gap-4">
      <SectionHeader
        id="voice-settings-heading"
        title={ui("Giọng nói")}
        description={ui(
          "Điều khiển cách micro và phần đọc câu trả lời phối hợp trong cuộc trò chuyện.",
        )}
      />
      <SettingRows>
        {dictation && (
          <SettingRow
            htmlFor="voice-auto-send"
            descriptionId="voice-auto-send-description"
            icon={<Mic2 />}
            title={ui("Tự động gửi khi dừng ghi âm")}
            description={ui("Câu hỏi được gửi ngay khi văn bản nhận dạng xong, không cần bấm Gửi.")}
            control={
              <Switch
                id="voice-auto-send"
                aria-describedby="voice-auto-send-description"
                checked={autoSend}
                disabled={disabled}
                onCheckedChange={(checked) => save({ autoSend: checked })}
              />
            }
          />
        )}
        {readAloud && (
          <SettingRow
            htmlFor="voice-auto-playback"
            descriptionId="voice-auto-playback-description"
            icon={<Volume2 />}
            title={ui("Tự động đọc câu trả lời")}
            description={ui(
              "Câu trả lời mới được đọc ngay khi đang được tạo. Nếu bạn vừa hỏi bằng micro, micro sẽ tự bật lại sau khi đọc xong.",
            )}
            control={
              <Switch
                id="voice-auto-playback"
                aria-describedby="voice-auto-playback-description"
                checked={autoPlayback}
                disabled={disabled}
                onCheckedChange={(checked) => save({ autoPlayback: checked })}
              />
            }
          />
        )}
        {readAloud && (
          <SettingRow
            htmlFor="voice-speed"
            descriptionId="voice-speed-description"
            icon={<Gauge />}
            title={ui("Tốc độ đọc")}
            description={ui("Áp dụng khi đọc câu trả lời thành tiếng.")}
            className="flex-col items-stretch sm:flex-row sm:items-center"
            control={
              <div className="flex w-full items-center gap-3 sm:w-64">
                <Slider
                  id="voice-speed"
                  aria-label={ui("Tốc độ đọc")}
                  aria-describedby="voice-speed-description"
                  className="min-w-0 flex-1"
                  min={0.5}
                  max={2}
                  step={0.1}
                  value={[speed]}
                  disabled={disabled}
                  onValueChange={([value = speed]) => setSpeedDraft(tenths(value))}
                  onValueCommit={([value = speed]) => {
                    if (tenths(value) === saved?.playbackSpeed) setSpeedDraft(undefined);
                    else save({ playbackSpeed: tenths(value) });
                  }}
                />
                <output
                  htmlFor="voice-speed"
                  className="w-10 shrink-0 text-right font-main-ui-action text-content-primary tabular-nums"
                >
                  {speedLabel(speed)}
                </output>
              </div>
            }
          />
        )}
      </SettingRows>
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
