import { Download, Trash2, X } from "lucide-react";
import { Alert, AlertAction, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { archiveContentUrl, type LibraryArchive } from "./use-library-archive";

/**
 * What the page itself must keep on screen: the ZIP a selection is waiting for, and the files a command
 * refused one by one. What simply succeeded is said by the application's own notifications instead, as every
 * other page says it.
 */
export function LibraryNotices({
  archive,
  refusals,
  onDismiss,
}: {
  archive: LibraryArchive;
  refusals: string[];
  onDismiss: () => void;
}) {
  const ui = useAppTranslation();
  return (
    <>
      {archive.state.phase === "packing" && (
        <Alert>
          <Download />
          <AlertTitle>
            {ui("Đang đóng gói {{count}} tệp thành ZIP…", { count: archive.state.fileCount })}
          </AlertTitle>
        </Alert>
      )}
      {archive.state.phase === "ready" && (
        <Alert variant="success">
          <Download />
          <AlertTitle>{ui("ZIP đã sẵn sàng và đang được tải về.")}</AlertTitle>
          <AlertDescription>
            {archive.state.archive.skipped.length > 0 &&
              ui("Bỏ qua {{count}} tệp không còn khả dụng: {{names}}", {
                count: archive.state.archive.skipped.length,
                names: archive.state.archive.skipped.join(", "),
              })}
            <Button size="sm" prominence="internal" asChild>
              <a
                href={archiveContentUrl(archive.state.archive.id)}
                download
                onClick={() => archive.reset()}
              >
                {ui("Tải lại ZIP")}
              </a>
            </Button>
          </AlertDescription>
        </Alert>
      )}
      {archive.state.phase === "failed" && (
        <Alert variant="destructive">
          <AlertTitle>
            {archive.state.message ||
              ui("Không đóng gói được ZIP. Hãy chọn ít tệp hơn rồi thử lại.")}
          </AlertTitle>
        </Alert>
      )}
      {refusals.length > 0 && (
        <Alert variant="destructive">
          <AlertTitle>{ui("Một số tệp không xoá được")}</AlertTitle>
          <AlertAction>
            <IconButton size="sm" prominence="internal" aria-label={ui("Đóng")} onClick={onDismiss}>
              <X />
            </IconButton>
          </AlertAction>
          <AlertDescription>
            <ul className="flex flex-col gap-1">
              {refusals.map((refusal) => (
                <li key={refusal}>{refusal}</li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}
    </>
  );
}

/** How long the trash keeps a file, and the one command that applies to all of it. */
export function TrashBanner({ days, onEmpty }: { days?: number; onEmpty: () => Promise<void> }) {
  const ui = useAppTranslation();
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border-subtle bg-surface-subtle px-3 py-2">
      <p className="font-secondary-body text-content-secondary">
        {days === 0
          ? ui("Máy chủ này xoá tệp ngay, không giữ trong thùng rác.")
          : ui("Tệp đã xoá được giữ {{days}} ngày rồi xoá vĩnh viễn.", { days: days ?? 30 })}
      </p>
      <ConfirmDialog
        trigger={
          <Button size="sm" tone="danger" prominence="secondary">
            <Trash2 data-icon="inline-start" />
            {ui("Dọn sạch thùng rác")}
          </Button>
        }
        title={ui("Dọn sạch thùng rác?")}
        description={ui("Mọi tệp trong thùng rác sẽ bị xoá vĩnh viễn và không thể khôi phục.")}
        confirmLabel={ui("Dọn sạch")}
        pendingLabel={ui("Đang dọn…")}
        confirmTone="danger"
        onConfirm={onEmpty}
      />
    </div>
  );
}
