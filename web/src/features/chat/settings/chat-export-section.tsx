import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Download } from "lucide-react";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { i18n } from "@/i18n/index";
import {
  listChatExportsOptions,
  listChatExportsQueryKey,
  requestChatExportMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChatExport } from "@/lib/hey-api/types.gen";
import { actionErrorText } from "@/lib/action-errors";
import { fileSize } from "@/lib/file-size";

/** The owner's own route; a signed URL would let an export leave the person it belongs to. */
function exportContentUrl(id: string) {
  return `/api/chat/exports/${id}/content`;
}

/**
 * "Export my data" (MEM-153), after ChatGPT's Data controls: one request packs every conversation the person
 * has not deleted, as JSON and as a readable page, with the files their library lists. The section polls the
 * export it asked for and then hands over the download, saying what was left out rather than hiding it.
 */
export function ChatExportSection() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const exports = useQuery({
    ...listChatExportsOptions(),
    refetchInterval: (current) =>
      current.state.data?.some((item) => item.status === "PENDING" || item.status === "RUNNING")
        ? 3000
        : false,
  });
  const latest: ChatExport | undefined = exports.data?.[0];
  const packing = latest?.status === "PENDING" || latest?.status === "RUNNING";

  const request = useMutation({
    ...requestChatExportMutation(),
    onSuccess: () => cache.invalidateQueries({ queryKey: listChatExportsQueryKey() }),
  });

  return (
    <section aria-labelledby="chat-export-heading" className="flex max-w-2xl flex-col gap-3">
      <h2 id="chat-export-heading" className="font-heading-h3 text-content-primary">
        {ui("Dữ liệu của tôi")}
      </h2>
      <SettingRows>
        <SettingRow
          icon={<Download />}
          title={ui("Xuất dữ liệu")}
          description={ui(
            "Một tệp ZIP gồm mọi hội thoại của bạn (JSON và trang HTML đọc được) cùng tệp trong thư viện.",
          )}
          control={
            <Button
              prominence="secondary"
              pending={request.isPending || packing}
              onClick={() => request.mutate({ signal: AbortSignal.timeout(30000) })}
            >
              {packing ? ui("Đang chuẩn bị…") : ui("Xuất dữ liệu")}
            </Button>
          }
        />
      </SettingRows>
      {packing && (
        <div role="status" className="flex flex-col gap-1">
          <Progress
            value={latest?.status === "RUNNING" ? 66 : 25}
            aria-label={ui("Đang chuẩn bị…")}
          />
          <p className="font-secondary-body text-content-muted">
            {ui("Bản xuất đang được đóng gói. Bạn có thể rời trang và quay lại sau.")}
          </p>
        </div>
      )}
      {request.isError && (
        <Alert variant="destructive">
          <AlertTitle>{actionErrorText(request.error)}</AlertTitle>
        </Alert>
      )}
      {latest?.status === "READY" && (
        <Alert variant="success">
          <AlertTitle>
            {ui("Bản xuất đã sẵn sàng: {{sessions}} hội thoại, {{files}} tệp · {{size}}", {
              sessions: latest.sessionCount ?? 0,
              files: latest.fileCount ?? 0,
              size: fileSize(latest.sizeBytes ?? 0, i18n.language),
            })}
          </AlertTitle>
          <AlertDescription>
            <div className="flex flex-col gap-2">
              {latest.expiresAt && (
                <span>
                  {ui("Liên kết tải hết hạn {{date}}.", {
                    date: new Date(latest.expiresAt).toLocaleString(i18n.language),
                  })}
                </span>
              )}
              {latest.skipped.length > 0 && (
                <span>
                  {ui("Không đưa vào {{count}} tệp: {{names}}", {
                    count: latest.skipped.length,
                    names: latest.skipped.slice(0, 5).join(", "),
                  })}
                </span>
              )}
              <Button size="sm" className="self-start" asChild>
                <a href={exportContentUrl(latest.id)} download>
                  {ui("Tải bản xuất")}
                </a>
              </Button>
            </div>
          </AlertDescription>
        </Alert>
      )}
      {latest?.status === "FAILED" && (
        <Alert variant="destructive">
          <AlertTitle>{latest.failure ?? ui("Không đóng gói được bản xuất.")}</AlertTitle>
        </Alert>
      )}
    </section>
  );
}
