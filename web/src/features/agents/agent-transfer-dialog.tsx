import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { User, Users } from "lucide-react";
import { sameOriginMutationHeaders } from "@/lib/api";
import { transferChatPersona } from "@/lib/hey-api/sdk.gen";
import { ChatDialog } from "@/features/chat/chat-dialog";
import { personLabel, type Persona } from "@/features/chat/chat-workspace-api";
import { AgentPrincipalPicker, type Principal } from "./agent-principal-picker";

export function AgentTransferDialog({ agent, onClose }: { agent: Persona; onClose: () => void }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const [target, setTarget] = useState<Principal>();
  const current = agent.owner.actor?.actorId ?? agent.owner.group?.id;
  return (
    <ChatDialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={ui("Chuyển quyền sở hữu {{v1}}", { v1: agent.name })}
      description={ui(
        "Chủ sở hữu mới có toàn quyền với trợ lý. Nếu bạn là chủ sở hữu hiện tại, bạn vẫn giữ quyền sửa.",
      )}
      submitLabel={ui("Chuyển quyền sở hữu")}
      submitDisabled={!target}
      onSubmit={async () => {
        if (!target) return;
        await transferChatPersona({
          path: { personaId: agent.id },
          query: { revision: agent.revision },
          body:
            target.kind === "person"
              ? { actorId: target.person.actorId }
              : { groupId: target.group.id },
          headers: sameOriginMutationHeaders,
          signal: AbortSignal.timeout(30000),
          throwOnError: true,
        });
        await cache.invalidateQueries({ queryKey: ["chat-personas"] });
      }}
    >
      <div className="space-y-3">
        <AgentPrincipalPicker exclude={new Set(current ? [current] : [])} onPick={setTarget} />
        {target && (
          <p
            role="status"
            className="flex items-center gap-2 rounded-xl border border-border-default px-3 py-2 text-sm"
          >
            {target.kind === "person" ? (
              <User aria-hidden="true" className="size-4" />
            ) : (
              <Users aria-hidden="true" className="size-4" />
            )}
            {ui("Chủ sở hữu mới: {{v1}}", {
              v1: target.kind === "person" ? personLabel(target.person) : target.group.name,
            })}
          </p>
        )}
      </div>
    </ChatDialog>
  );
}
