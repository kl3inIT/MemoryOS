import { useAppTranslation } from "@/i18n/use-app-translation";
import { useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { LogOut, MoreHorizontal, Trash2, UserRoundCog } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { IconButton } from "@/components/ui/icon-button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  deleteChatPersonaMutation,
  leaveChatPersonaMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { can } from "@/lib/resource-permissions";
import { actionErrorText } from "@/lib/action-errors";
import { invalidateAgents, type Persona } from "@/features/chat/chat-personas-api";
import { AgentTransferDialog } from "./agent-transfer-dialog";

/** Secondary agent actions; each renders only when the server's permission hint allows it. */
export function AgentActions({ agent }: { agent: Persona }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const trigger = useRef<HTMLButtonElement>(null);
  const [dialog, setDialog] = useState<"delete" | "leave" | "transfer">();
  const remove = useMutation({
    ...deleteChatPersonaMutation(),
    onSuccess: () => invalidateAgents(cache, agent.id),
  });
  const leave = useMutation({
    ...leaveChatPersonaMutation(),
    onSuccess: () => invalidateAgents(cache, agent.id),
  });
  const actions = [can(agent, "transfer"), can(agent, "leave"), can(agent, "delete")];
  if (!actions.some(Boolean)) return null;
  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <IconButton
            ref={trigger}
            size="sm"
            prominence="internal"
            aria-label={ui("Thao tác khác cho {{v1}}", { v1: agent.name })}
            className="ml-auto"
          >
            <MoreHorizontal />
          </IconButton>
        </DropdownMenuTrigger>
        <DropdownMenuContent
          align="end"
          sideOffset={5}
          className="w-auto min-w-52"
          onCloseAutoFocus={(event) => {
            if (dialog) event.preventDefault();
          }}
        >
          <DropdownMenuGroup>
            {can(agent, "transfer") && (
              <DropdownMenuItem onSelect={() => setDialog("transfer")}>
                <UserRoundCog aria-hidden="true" />
                {ui("Chuyển quyền sở hữu")}
              </DropdownMenuItem>
            )}
            {can(agent, "leave") && (
              <DropdownMenuItem onSelect={() => setDialog("leave")}>
                <LogOut aria-hidden="true" />
                {ui("Rời khỏi trợ lý")}
              </DropdownMenuItem>
            )}
            {can(agent, "delete") && (
              <DropdownMenuItem variant="destructive" onSelect={() => setDialog("delete")}>
                <Trash2 aria-hidden="true" />
                {ui("Xóa trợ lý")}
              </DropdownMenuItem>
            )}
          </DropdownMenuGroup>
        </DropdownMenuContent>
      </DropdownMenu>
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
        errorMessage={actionErrorText}
        onConfirm={async () => {
          await remove.mutateAsync({
            path: { personaId: agent.id },
            query: { revision: agent.revision },
          });
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
        errorMessage={actionErrorText}
        onConfirm={async () => {
          await leave.mutateAsync({ path: { personaId: agent.id } });
        }}
      />
      {dialog === "transfer" && (
        <AgentTransferDialog agent={agent} onClose={() => setDialog(undefined)} />
      )}
    </>
  );
}
