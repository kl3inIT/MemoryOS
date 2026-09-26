import { Trash2 } from "lucide-react";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { deleteAllChatSessions } from "@/lib/hey-api/sdk.gen";
import { useRefreshChatSessions } from "@/features/chat/runtime/chat-threads-context";

/** Onyx Danger Zone on the General settings page: delete every conversation the member owns, after confirmation. */
export function DangerZoneSection() {
  const ui = useAppTranslation();
  const refreshSessions = useRefreshChatSessions();
  return (
    <section aria-labelledby="danger-zone-heading" className="flex max-w-2xl flex-col gap-3">
      <h2 id="danger-zone-heading" className="font-heading-h3 text-content-primary">
        {ui("Danger Zone")}
      </h2>
      <SettingRows className="border-status-danger-border">
        <SettingRow
          icon={<Trash2 />}
          title={ui("Delete All Chats")}
          description={ui("Permanently delete all your chat sessions.")}
          control={
            <ConfirmDialog
              trigger={
                <Button prominence="secondary" tone="danger">
                  {ui("Delete All Chats")}
                </Button>
              }
              title={ui("Delete all chats?")}
              description={ui(
                "All your chat sessions and history will be permanently deleted. Deletion cannot be undone.",
              )}
              confirmLabel={ui("Delete")}
              pendingLabel={ui("Deleting…")}
              confirmTone="danger"
              onConfirm={async () => {
                await deleteAllChatSessions();
                await refreshSessions();
              }}
            />
          }
        />
      </SettingRows>
    </section>
  );
}
