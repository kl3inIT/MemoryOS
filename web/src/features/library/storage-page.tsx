import type { ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { HardDrive } from "lucide-react";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Skeleton } from "@/components/ui/skeleton";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { chatLibraryKey, loadLibraryUsage, loadTrashWindow } from "./library";
import { StorageMeter } from "./storage-meter";

/**
 * Everything about what this person stores and how long it lasts, in their own settings: the meter and its
 * categories (the same component the library's own panel shows), how long a deleted file waits in the trash,
 * and the retention window for their conversations. The page holds at least what the library panel holds, so
 * whichever way someone arrives they find the whole picture rather than half of it.
 */
export function StoragePage({
  retention,
}: {
  /** How long the owner keeps their conversations, which Chat supplies. */
  retention?: ReactNode;
}) {
  const ui = useAppTranslation();
  const { actorId, authorizationVersion } = useApplicationSession();
  const usage = useQuery({
    queryKey: ["chat-library-usage", actorId, authorizationVersion],
    queryFn: ({ signal }) => loadLibraryUsage(signal),
  });
  const trashWindow = useQuery({
    queryKey: [...chatLibraryKey, actorId, authorizationVersion, "trash-window"],
    queryFn: ({ signal }) => loadTrashWindow(signal),
    staleTime: 5 * 60_000,
  });

  return (
    <SettingsLayout>
      <PageHeader
        icon={<HardDrive />}
        title={ui("Bộ nhớ lưu trữ")}
        description={ui("Tuỳ chọn cho các tệp của bạn.")}
      />
      {usage.isPending && <Skeleton className="h-40 w-full max-w-2xl rounded-xl" />}
      {usage.isError && (
        <Alert variant="destructive">
          <AlertTitle>{ui("Không tải được dung lượng đã dùng.")}</AlertTitle>
          <Button size="sm" prominence="internal" onClick={() => void usage.refetch()}>
            {ui("Thử lại")}
          </Button>
        </Alert>
      )}
      {usage.isSuccess && <StorageMeter usage={usage.data} />}
      {/* The meter already offers the library and says a deleted file still counts; this adds the number. */}
      {trashWindow.data !== undefined && trashWindow.data > 0 && (
        <p className="max-w-2xl font-secondary-body text-content-muted">
          {ui("Tệp đã xoá được giữ {{days}} ngày rồi xoá vĩnh viễn.", { days: trashWindow.data })}
        </p>
      )}
      {retention}
    </SettingsLayout>
  );
}
