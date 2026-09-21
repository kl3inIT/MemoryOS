import { useState } from "react";
import { Settings2 } from "lucide-react";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { IconButton } from "@/components/ui/icon-button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ChatRetentionSection } from "./chat-retention-section";
import { type LibraryCategory } from "./chat-library";
import { StorageMeter, type LibraryUsage } from "./chat-storage-meter";

/**
 * The library's own settings, on the library rather than somewhere else: what the account has stored and how
 * long its owner keeps their conversations. Everything here belongs to the person looking at it — the storage
 * ceiling is a deployment setting the panel only states, and the retention number deletes nothing but their
 * own history — so the panel needs no administrator and offers nothing an administrator would have to set.
 */
export function LibrarySettingsButton({
  usage,
  usageFailed = false,
  trashDays,
  onCategory,
}: {
  usage?: LibraryUsage;
  usageFailed?: boolean;
  /** How long a deleted file stays restorable in this deployment; absent while it is being read. */
  trashDays?: number;
  onCategory: (category: LibraryCategory) => void;
}) {
  const ui = useAppTranslation();
  const [open, setOpen] = useState(false);
  return (
    <>
      <IconButton
        prominence="secondary"
        aria-label={ui("Cài đặt thư viện")}
        title={ui("Cài đặt thư viện")}
        onClick={() => setOpen(true)}
      >
        <Settings2 />
      </IconButton>
      <Dialog open={open} onOpenChange={setOpen}>
        {/* A dialog over the middle of the list, not a drawer at the edge: the panel is about what the list
            already shows, so it reads better in front of it than beside it. */}
        <DialogContent className="sm:max-w-xl">
          <DialogHeader>
            <DialogTitle>{ui("Cài đặt thư viện")}</DialogTitle>
            <DialogDescription>
              {ui("Dung lượng bạn đang dùng và cách hội thoại của bạn được lưu giữ.")}
            </DialogDescription>
          </DialogHeader>
          <div className="flex min-h-0 flex-col gap-8">
            {usageFailed && (
              <Alert variant="destructive">
                <AlertTitle>{ui("Không tải được dung lượng đã dùng.")}</AlertTitle>
              </Alert>
            )}
            {!usage && !usageFailed && <Skeleton className="h-40 w-full rounded-xl" />}
            {usage && (
              <StorageMeter
                usage={usage}
                // The list is already on screen behind the panel, so a category narrows it instead of navigating.
                onCategory={(category) => {
                  onCategory(category);
                  setOpen(false);
                }}
              />
            )}
            {trashDays !== undefined && trashDays > 0 && (
              <p className="font-secondary-body text-content-muted">
                {ui("Tệp đã xoá được giữ {{days}} ngày rồi xoá vĩnh viễn.", { days: trashDays })}
              </p>
            )}
            <ChatRetentionSection />
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}
