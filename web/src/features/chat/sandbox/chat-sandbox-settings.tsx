import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { SquareTerminal } from "lucide-react";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Field, FieldContent, FieldLabel, FieldTitle } from "@/components/ui/field";
import { StatusBadge } from "@/components/ui/status-badge";
import { Switch } from "@/components/ui/switch";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  getChatSandboxHealthOptions,
  getChatSandboxSettingsOptions,
  getChatSandboxSettingsQueryKey,
  updateChatSandboxSettingsMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";

/**
 * The Sandbox page (MEM-270): the Tenant switch for the conversation's computer, the sandbox server's live health and
 * the deployment's allowed domains, as Dust's workspace computer settings show its network allowlist.
 */
export function ChatSandboxSettings() {
  const ui = useAppTranslation();
  const manager = useApplicationSession().capabilities.includes("MODELS_MANAGE");
  const problemMessage = useProblemMessage();
  const cache = useQueryClient();
  const settings = useQuery({
    ...getChatSandboxSettingsOptions(),
    enabled: manager,
    retry: false,
  });
  const health = useQuery({
    ...getChatSandboxHealthOptions(),
    enabled: manager && settings.data?.configured === true,
    retry: false,
  });
  const update = useMutation({
    ...updateChatSandboxSettingsMutation(),
    // A rejected change also reloads, so the switch shows the stored state.
    onSettled: () => cache.invalidateQueries({ queryKey: getChatSandboxSettingsQueryKey() }),
  });

  if (!manager)
    return (
      <p role="alert" className="p-6">
        {ui("Bạn không có quyền quản lý mô hình.")}
      </p>
    );
  return (
    <SettingsLayout>
      <PageHeader
        title={ui("Sandbox")}
        icon={<SquareTerminal />}
        description={ui("Máy tính riêng của mỗi hội thoại để chạy lệnh, chạy Python và tạo tệp.")}
      />
      {settings.isError ? (
        <Alert variant="destructive">
          <AlertTitle>{ui("Không tải được cài đặt Sandbox.")}</AlertTitle>
          <div>
            <Button onClick={() => void settings.refetch()}>{ui("Tải lại")}</Button>
          </div>
        </Alert>
      ) : settings.isPending ? (
        <p role="status">{ui("Đang tải…")}</p>
      ) : !settings.data.configured ? (
        <Alert role="note">
          <AlertDescription>{ui("Máy chủ này chưa cấu hình Sandbox.")}</AlertDescription>
        </Alert>
      ) : (
        <section aria-label={ui("Sandbox")} className="flex flex-col gap-6">
          <FieldLabel htmlFor="sandbox-enabled">
            <Field orientation="horizontal">
              <FieldContent>
                <span>{ui("Bật Sandbox")}</span>
              </FieldContent>
              <Switch
                id="sandbox-enabled"
                checked={settings.data.enabled}
                disabled={update.isPending}
                onCheckedChange={(enabled) =>
                  update.mutate({ body: { enabled, revision: settings.data.revision } })
                }
                aria-label={ui("Bật Sandbox")}
              />
            </Field>
          </FieldLabel>
          {update.error ? (
            <Alert variant="destructive">
              <AlertTitle>
                {problemMessage(presentProblem(update.error, "mutation", {}).message)}
              </AlertTitle>
            </Alert>
          ) : null}
          <div className="flex flex-wrap items-center gap-3">
            {health.isPending ? (
              <StatusBadge tone="neutral">{ui("Đang kiểm tra Sandbox…")}</StatusBadge>
            ) : health.data?.connected && health.data.error === "" ? (
              <StatusBadge tone="success">
                {ui("Sandbox hoạt động ({{version}})", { version: health.data.version })}
              </StatusBadge>
            ) : (
              <StatusBadge tone="danger">{ui("Sandbox không hoạt động")}</StatusBadge>
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
          <Field>
            <FieldTitle>{ui("Tên miền được phép truy cập")}</FieldTitle>
            {settings.data.allowedDomains.length === 0 ? (
              <p className="text-content-muted">{ui("Không có")}</p>
            ) : (
              <ul aria-label={ui("Tên miền được phép truy cập")} className="flex flex-wrap gap-2">
                {settings.data.allowedDomains.map((domain) => (
                  <li key={domain}>
                    <Badge variant="outline">{domain}</Badge>
                  </li>
                ))}
              </ul>
            )}
          </Field>
        </section>
      )}
    </SettingsLayout>
  );
}
