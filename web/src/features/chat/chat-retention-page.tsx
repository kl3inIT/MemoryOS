import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CalendarClock } from "lucide-react";
import { AppShell } from "@/components/app-shell/app-shell";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { sameOriginMutationHeaders } from "@/lib/api";
import { getChatSettings, previewChatRetention, saveChatRetention } from "@/lib/hey-api/sdk.gen";
import type { ChatSettingsResponse } from "@/lib/hey-api/types.gen";
import { chatActionError } from "./chat-action-utils";

const MAX_DAYS = 3650;

/**
 * Chat retention for the Tenant (MEM-153, after Onyx's `maximum_chat_retention_days`): a conversation nobody
 * has touched for longer than the policy is deleted for its owner. Because that is not undoable, the page says
 * how many conversations the number on screen would delete before it can be saved.
 */
export function ChatRetentionPage() {
  const ui = useAppTranslation();
  const { actorId, authorizationVersion } = useApplicationSession();
  const settings = useQuery({
    queryKey: ["chat-settings", actorId, authorizationVersion],
    queryFn: ({ signal }) =>
      getChatSettings({ signal, throwOnError: true }).then((answer) => answer.data),
  });
  return (
    <AppShell area="admin" adminPage="retention" pageTitle={ui("Lưu giữ hội thoại")}>
      <SettingsLayout>
        <PageHeader
          icon={<CalendarClock />}
          title={ui("Lưu giữ hội thoại")}
          description={ui(
            "Hội thoại không có hoạt động nào trong số ngày này sẽ bị xoá như chính người sở hữu xoá nó. Tệp tải lên giữ vòng đời riêng.",
          )}
        />
        {settings.isPending && <Skeleton className="h-40 w-full max-w-2xl rounded-xl" />}
        {settings.isError && (
          <Alert variant="destructive">
            <AlertTitle>{ui("Không tải được cài đặt hội thoại.")}</AlertTitle>
            <AlertDescription>
              <Button size="sm" prominence="internal" onClick={() => void settings.refetch()}>
                {ui("Thử lại")}
              </Button>
            </AlertDescription>
          </Alert>
        )}
        {/* Keyed on what was saved, so the form starts from it without an effect writing state. */}
        {settings.isSuccess && (
          <RetentionForm
            key={`${settings.data.revision}:${settings.data.chatRetentionDays ?? "off"}`}
            settings={settings.data}
          />
        )}
      </SettingsLayout>
    </AppShell>
  );
}

function RetentionForm({ settings }: { settings: ChatSettingsResponse }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [enabled, setEnabled] = useState(settings.chatRetentionDays != null);
  const [days, setDays] = useState(String(settings.chatRetentionDays ?? 90));
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<string>();
  const [saved, setSaved] = useState(false);

  const parsed = Number.parseInt(days, 10);
  const valid = Number.isInteger(parsed) && parsed >= 1 && parsed <= MAX_DAYS;
  const preview = useQuery({
    queryKey: ["chat-retention-preview", actorId, authorizationVersion, parsed],
    queryFn: ({ signal }) =>
      previewChatRetention({ query: { days: parsed }, signal, throwOnError: true }).then(
        (answer) => answer.data,
      ),
    enabled: enabled && valid,
  });

  const save = async () => {
    setSaving(true);
    setFailure(undefined);
    setSaved(false);
    try {
      await saveChatRetention({
        // Leaving the field out is what clears the policy; there is no partial update to confuse it with.
        body: {
          ...(enabled && valid ? { chatRetentionDays: parsed } : {}),
          revision: settings.revision,
        },
        headers: sameOriginMutationHeaders,
        signal: AbortSignal.timeout(30000),
        throwOnError: true,
      });
      setSaved(true);
      await cache.invalidateQueries({ queryKey: ["chat-settings"] });
      await cache.invalidateQueries({ queryKey: ["chat-retention-preview"] });
    } catch (cause) {
      setFailure(chatActionError(cause));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form
      className="flex max-w-2xl flex-col gap-4 rounded-xl border border-border-subtle p-4"
      onSubmit={(event) => {
        event.preventDefault();
        void save();
      }}
    >
      <label className="flex items-center justify-between gap-3 font-main-ui-body">
        <span>
          <span className="block text-content-primary">{ui("Bật chính sách lưu giữ")}</span>
          <span className="block font-secondary-body text-content-muted">
            {ui("Khi tắt, hội thoại được giữ cho tới khi người sở hữu xoá.")}
          </span>
        </span>
        <Switch checked={enabled} onCheckedChange={setEnabled} />
      </label>
      {enabled && (
        <div className="flex flex-col gap-2">
          <Label htmlFor="retention-days">{ui("Số ngày không hoạt động")}</Label>
          <Input
            id="retention-days"
            type="number"
            min={1}
            max={MAX_DAYS}
            value={days}
            className="w-40"
            onChange={(event) => setDays(event.target.value)}
          />
          {!valid && (
            <p role="alert" className="font-secondary-body text-status-danger-content">
              {ui("Số ngày phải từ 1 đến {{max}}.", { max: MAX_DAYS })}
            </p>
          )}
          {valid && preview.isSuccess && (
            <Alert variant={preview.data.affected > 0 ? "destructive" : "default"}>
              <AlertTitle>
                {preview.data.affected > 0
                  ? ui("{{count}} hội thoại sẽ bị xoá khi lưu.", { count: preview.data.affected })
                  : ui("Không có hội thoại nào bị xoá ngay.")}
              </AlertTitle>
            </Alert>
          )}
        </div>
      )}
      {failure && (
        <Alert variant="destructive">
          <AlertTitle>{failure}</AlertTitle>
        </Alert>
      )}
      {saved && (
        <Alert variant="success">
          <AlertTitle>{ui("Đã lưu chính sách lưu giữ.")}</AlertTitle>
        </Alert>
      )}
      <Button type="submit" className="self-start" pending={saving} disabled={enabled && !valid}>
        {ui("Lưu chính sách")}
      </Button>
    </form>
  );
}
