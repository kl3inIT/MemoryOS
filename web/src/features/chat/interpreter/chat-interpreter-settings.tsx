import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { SquareTerminal } from "lucide-react";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import { StatusBadge } from "@/components/ui/status-badge";
import { Switch } from "@/components/ui/switch";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  getChatInterpreterHealthOptions,
  getChatInterpreterSettingsOptions,
  getChatInterpreterSettingsQueryKey,
  updateChatInterpreterSettingsMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";

/** Onyx admin Code Interpreter page: one Tenant switch and a live service health check. */
export function ChatInterpreterSettings() {
  const ui = useAppTranslation();
  const manager = useApplicationSession().capabilities.includes("MODELS_MANAGE");
  const problemMessage = useProblemMessage();
  const cache = useQueryClient();
  const settings = useQuery({
    ...getChatInterpreterSettingsOptions(),
    enabled: manager,
    retry: false,
  });
  const health = useQuery({
    ...getChatInterpreterHealthOptions(),
    enabled: manager && settings.data?.configured === true,
    retry: false,
  });
  const update = useMutation({
    ...updateChatInterpreterSettingsMutation(),
    // A rejected change also reloads, so the switch shows the stored state.
    onSettled: () => cache.invalidateQueries({ queryKey: getChatInterpreterSettingsQueryKey() }),
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
        title={ui("Code Interpreter")}
        icon={<SquareTerminal />}
        description={ui(
          "Cho phép trợ lý chạy Python trong sandbox để phân tích dữ liệu và tạo tệp.",
        )}
      />
      {settings.isError ? (
        <Alert variant="destructive">
          <AlertTitle>{ui("Không tải được cài đặt Code Interpreter.")}</AlertTitle>
          <div>
            <Button onClick={() => void settings.refetch()}>{ui("Tải lại")}</Button>
          </div>
        </Alert>
      ) : settings.isPending ? (
        <p role="status">{ui("Đang tải…")}</p>
      ) : !settings.data.configured ? (
        <Alert role="note">
          <AlertDescription>
            {ui("Máy chủ này chưa cấu hình dịch vụ Code Interpreter.")}
          </AlertDescription>
        </Alert>
      ) : (
        <section aria-label={ui("Code Interpreter")} className="flex flex-col gap-4">
          <FieldLabel htmlFor="code-interpreter-enabled">
            <Field orientation="horizontal">
              <FieldContent>
                <span>{ui("Bật Code Interpreter")}</span>
                <FieldDescription>
                  {ui("Mô hình hỗ trợ công cụ sẽ có công cụ run_python khi dịch vụ hoạt động.")}
                </FieldDescription>
              </FieldContent>
              <Switch
                id="code-interpreter-enabled"
                checked={settings.data.enabled}
                disabled={update.isPending}
                onCheckedChange={(enabled) =>
                  update.mutate({ body: { enabled, revision: settings.data.revision } })
                }
                aria-label={ui("Bật Code Interpreter")}
              />
            </Field>
          </FieldLabel>
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
          {update.error ? (
            <Alert variant="destructive">
              <AlertTitle>
                {problemMessage(presentProblem(update.error, "mutation", {}).message)}
              </AlertTitle>
            </Alert>
          ) : null}
        </section>
      )}
    </SettingsLayout>
  );
}
