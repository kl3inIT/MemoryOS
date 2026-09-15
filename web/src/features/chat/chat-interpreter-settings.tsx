import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { SquareTerminal } from "lucide-react";
import { Button } from "@/components/ui/button";
import { SettingsLayout, PageHeader } from "@/components/ui/settings-layout";
import { StatusBadge } from "@/components/ui/status-badge";
import { Switch } from "@/components/ui/switch";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  getChatInterpreterHealth,
  getChatInterpreterSettings,
  updateChatInterpreterSettings,
} from "@/lib/hey-api/sdk.gen";
import { presentProblem, type ErrorMessage } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";

const notice =
  "rounded-xl border border-border-default bg-surface-sunken px-4 py-3 text-sm text-content-secondary";

/** Onyx admin Code Interpreter page: one Tenant switch and a live service health check. */
export function ChatInterpreterSettings() {
  const ui = useAppTranslation();
  const session = useApplicationSession();
  const manager = session.capabilities.includes("MODELS_MANAGE");
  const problemMessage = useProblemMessage();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ErrorMessage>();
  const settings = useQuery({
    queryKey: ["chat-interpreter", session.actorId, session.authorizationVersion],
    enabled: manager,
    queryFn: async ({ signal }) =>
      (await getChatInterpreterSettings({ signal, throwOnError: true })).data,
    retry: false,
  });
  const health = useQuery({
    queryKey: ["chat-interpreter-health", session.actorId, session.authorizationVersion],
    enabled: manager && settings.data?.configured === true,
    queryFn: async ({ signal }) =>
      (await getChatInterpreterHealth({ signal, throwOnError: true })).data,
    retry: false,
  });

  async function toggle(enabled: boolean) {
    if (!settings.data) return;
    setPending(true);
    setError(undefined);
    try {
      await updateChatInterpreterSettings({
        body: { enabled, revision: settings.data.revision },
        headers: sameOriginMutationHeaders,
        throwOnError: true,
      });
      await settings.refetch();
    } catch (failed) {
      setError(presentProblem(failed, "mutation", {}).message);
      await settings.refetch();
    } finally {
      setPending(false);
    }
  }

  if (!manager)
    return (
      <p role="alert" className="p-6">
        {ui("Bạn không có quyền quản lý mô hình.")}
      </p>
    );
  return (
    <SettingsLayout>
      <PageHeader
        title={ui("Code Interpreter")}
        icon={<SquareTerminal />}
        description={ui("Cho phép trợ lý chạy Python trong sandbox để phân tích dữ liệu và tạo tệp.")}
      />
      {settings.isError ? (
        <div role="alert">
          <p>{ui("Không tải được cài đặt Code Interpreter.")}</p>
          <Button onClick={() => void settings.refetch()}>{ui("Tải lại")}</Button>
        </div>
      ) : settings.isPending ? (
        <p role="status">{ui("Đang tải…")}</p>
      ) : !settings.data.configured ? (
        <p className={notice}>{ui("Máy chủ này chưa cấu hình dịch vụ Code Interpreter.")}</p>
      ) : (
        <section aria-label={ui("Code Interpreter")} className="space-y-4">
          <label className="flex items-center justify-between gap-4 rounded-xl border border-border-default px-4 py-3">
            <span className="space-y-1">
              <span className="block font-medium">{ui("Bật Code Interpreter")}</span>
              <span className="block text-sm text-content-muted">
                {ui("Mô hình hỗ trợ công cụ sẽ có công cụ run_python khi dịch vụ hoạt động.")}
              </span>
            </span>
            <Switch
              checked={settings.data.enabled}
              disabled={pending}
              onCheckedChange={(checked) => void toggle(checked)}
              aria-label={ui("Bật Code Interpreter")}
            />
          </label>
          <div className="flex flex-wrap items-center gap-3">
            {health.isPending ? (
              <StatusBadge tone="neutral">{ui("Đang kiểm tra dịch vụ…")}</StatusBadge>
            ) : health.data?.connected && health.data.error === "" ? (
              <StatusBadge tone="success">
                {ui("Dịch vụ hoạt động (phiên bản {{version}})", { version: health.data.version })}
              </StatusBadge>
            ) : (
              <StatusBadge tone="danger">{ui("Dịch vụ không hoạt động")}</StatusBadge>
            )}
            <Button
              size="sm"
              prominence="secondary"
              disabled={health.isFetching}
              onClick={() => void health.refetch()}
            >
              {ui("Kiểm tra lại")}
            </Button>
          </div>
          {error ? <p role="alert">{problemMessage(error)}</p> : null}
        </section>
      )}
    </SettingsLayout>
  );
}
