import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Archive, ArchiveRestore } from "lucide-react";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { sameOriginMutationHeaders } from "@/lib/api";
import { archiveAllChatSessions } from "@/lib/hey-api/sdk.gen";
import { chatSessionsKey } from "./chat-api";

/**
 * Archiving from Settings, beside Delete All Chats (ChatGPT's Data controls): clearing the sidebar in one
 * command keeps every conversation, so it reports how many it moved instead of warning about loss.
 */
export function ChatArchiveSection() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const [archived, setArchived] = useState<number>();
  return (
    <section aria-labelledby="chat-archive-heading" className="flex max-w-2xl flex-col gap-3">
      <h2 id="chat-archive-heading" className="font-heading-h3 text-content-primary">
        {ui("Lưu trữ hội thoại")}
      </h2>
      <SettingRows>
        <SettingRow
          icon={<Archive />}
          title={ui("Hội thoại đã lưu trữ")}
          description={ui("Xem, bỏ lưu trữ hoặc xoá những hội thoại bạn đã cất đi.")}
          control={
            <Button prominence="secondary" asChild>
              <Link to="/settings/archived-chats">{ui("Mở")}</Link>
            </Button>
          }
        />
        <SettingRow
          icon={<ArchiveRestore />}
          title={ui("Lưu trữ tất cả hội thoại")}
          description={
            archived === undefined
              ? ui("Dọn thanh bên mà vẫn giữ lại mọi hội thoại.")
              : ui("Đã lưu trữ {{count}} hội thoại.", { count: archived })
          }
          control={
            <ConfirmDialog
              trigger={<Button prominence="secondary">{ui("Lưu trữ tất cả")}</Button>}
              title={ui("Lưu trữ tất cả hội thoại?")}
              description={ui(
                "Thanh bên sẽ trống, nhưng mọi hội thoại vẫn còn trong trang Hội thoại đã lưu trữ.",
              )}
              confirmLabel={ui("Lưu trữ tất cả")}
              pendingLabel={ui("Đang lưu trữ…")}
              onConfirm={async () => {
                const { data } = await archiveAllChatSessions({
                  headers: sameOriginMutationHeaders,
                  signal: AbortSignal.timeout(60000),
                  throwOnError: true,
                });
                setArchived(data.archived);
                await cache.invalidateQueries({ queryKey: chatSessionsKey });
              }}
            />
          }
        />
      </SettingRows>
    </section>
  );
}
