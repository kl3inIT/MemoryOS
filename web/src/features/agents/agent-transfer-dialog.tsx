import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { User, Users } from "lucide-react";
import { transferChatPersonaMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import { Item } from "@/components/ui/item";
import { FormDialog } from "@/components/composites/form-dialog";
import { personLabel } from "@/features/identity/principals";
import { invalidateAgents, type Persona } from "@/features/chat/chat-personas-api";
import { PrincipalPicker, type Principal } from "@/features/identity/principal-picker";

export function AgentTransferDialog({ agent, onClose }: { agent: Persona; onClose: () => void }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const transfer = useMutation({
    ...transferChatPersonaMutation(),
    onSuccess: () => invalidateAgents(cache, agent.id),
  });
  const [target, setTarget] = useState<Principal>();
  const current = agent.owner.actor?.actorId ?? agent.owner.group?.id;
  return (
    <FormDialog
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
        await transfer.mutateAsync({
          path: { personaId: agent.id },
          query: { revision: agent.revision },
          body:
            target.kind === "person"
              ? { actorId: target.person.actorId }
              : { groupId: target.group.id },
        });
      }}
    >
      <div className="flex flex-col gap-3">
        <PrincipalPicker exclude={new Set(current ? [current] : [])} onPick={setTarget} />
        {target && (
          <Item role="status" variant="outline" size="sm">
            {target.kind === "person" ? (
              <User aria-hidden="true" className="size-4" />
            ) : (
              <Users aria-hidden="true" className="size-4" />
            )}
            {ui("Chủ sở hữu mới: {{v1}}", {
              v1: target.kind === "person" ? personLabel(target.person) : target.group.name,
            })}
          </Item>
        )}
      </div>
    </FormDialog>
  );
}
