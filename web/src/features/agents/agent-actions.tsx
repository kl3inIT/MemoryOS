import { useAppTranslation } from "@/i18n/use-app-translation";
import { useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { DropdownMenu } from "radix-ui";
import { LogOut, MoreHorizontal, Trash2, UserRoundCog } from "lucide-react";
import { IconButton } from "@/components/ui/icon-button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { sameOriginMutationHeaders } from "@/lib/api";
import { deleteChatPersona, leaveChatPersona } from "@/lib/hey-api/sdk.gen";
import { can } from "@/lib/resource-permissions";
import { chatActionError } from "@/features/chat/chat-action-utils";
import type { Persona } from "@/features/chat/chat-workspace-api";
import { AgentTransferDialog } from "./agent-transfer-dialog";

const itemClass =
  "flex cursor-default items-center gap-2 rounded-lg px-3 py-2 text-sm outline-none data-[highlighted]:bg-surface-sunken data-[disabled]:opacity-40";

/** Secondary agent actions; each renders only when the server's permission hint allows it. */
export function AgentActions({ agent }: { agent: Persona }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const trigger = useRef<HTMLButtonElement>(null);
  const [dialog, setDialog] = useState<"delete" | "leave" | "transfer">();
  const actions = [can(agent, "transfer"), can(agent, "leave"), can(agent, "delete")];
  if (!actions.some(Boolean)) return null;
  const refresh = () =>
    Promise.all([
      cache.invalidateQueries({ queryKey: ["chat-personas"] }),
      cache.invalidateQueries({ queryKey: ["chat-persona-pins"] }),
    ]);
  return (
    <>
      <DropdownMenu.Root>
        <DropdownMenu.Trigger asChild>
          <IconButton
            ref={trigger}
            size="sm"
            prominence="internal"
            aria-label={ui("Thao tác khác cho {{v1}}", { v1: agent.name })}
            className="ml-auto"
          >
            <MoreHorizontal />
          </IconButton>
        </DropdownMenu.Trigger>
        <DropdownMenu.Portal>
          <DropdownMenu.Content
            align="end"
            sideOffset={5}
            className="z-50 min-w-52 rounded-xl border border-border-subtle bg-surface-overlay p-1.5 shadow-md"
            onCloseAutoFocus={(event) => {
              if (dialog) event.preventDefault();
            }}
          >
            {can(agent, "transfer") && (
              <DropdownMenu.Item className={itemClass} onSelect={() => setDialog("transfer")}>
                <UserRoundCog aria-hidden="true" className="size-4" />
                {ui("Chuyển quyền sở hữu")}
              </DropdownMenu.Item>
            )}
            {can(agent, "leave") && (
              <DropdownMenu.Item className={itemClass} onSelect={() => setDialog("leave")}>
                <LogOut aria-hidden="true" className="size-4" />
                {ui("Rời khỏi trợ lý")}
              </DropdownMenu.Item>
            )}
            {can(agent, "delete") && (
              <DropdownMenu.Item
                className={`${itemClass} text-status-danger-content`}
                onSelect={() => setDialog("delete")}
              >
                <Trash2 aria-hidden="true" className="size-4" />
                {ui("Xóa trợ lý")}
              </DropdownMenu.Item>
            )}
          </DropdownMenu.Content>
        </DropdownMenu.Portal>
      </DropdownMenu.Root>
      <ConfirmDialog
        open={dialog === "delete"}
        onOpenChange={(open) => setDialog(open ? "delete" : undefined)}
        restoreFocusRef={trigger}
        title={ui("Xóa trợ lý?")}
        description={ui(
          "Các hội thoại cũ vẫn được giữ. Người đang dùng trợ lý này cần chọn trợ lý khác để tiếp tục.",
        )}
        confirmLabel={ui("Xóa trợ lý")}
        pendingLabel={ui("Đang lưu…")}
        confirmTone="danger"
        errorMessage={chatActionError}
        onConfirm={async () => {
          await deleteChatPersona({
            path: { personaId: agent.id },
            query: { revision: agent.revision },
            headers: sameOriginMutationHeaders,
            signal: AbortSignal.timeout(30000),
            throwOnError: true,
          });
          await refresh();
        }}
      />
      <ConfirmDialog
        open={dialog === "leave"}
        onOpenChange={(open) => setDialog(open ? "leave" : undefined)}
        restoreFocusRef={trigger}
        title={ui("Rời khỏi trợ lý?")}
        description={ui(
          "Bạn sẽ không thấy trợ lý này nữa, trừ khi được chia sẻ lại qua Group hoặc trợ lý được công khai.",
        )}
        confirmLabel={ui("Rời khỏi")}
        pendingLabel={ui("Đang lưu…")}
        errorMessage={chatActionError}
        onConfirm={async () => {
          await leaveChatPersona({
            path: { personaId: agent.id },
            headers: sameOriginMutationHeaders,
            signal: AbortSignal.timeout(30000),
            throwOnError: true,
          });
          await refresh();
        }}
      />
      {dialog === "transfer" && (
        <AgentTransferDialog agent={agent} onClose={() => setDialog(undefined)} />
      )}
    </>
  );
}
