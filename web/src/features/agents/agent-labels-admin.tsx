import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Trash2 } from "lucide-react";
import { FormDialog } from "@/components/composites/form-dialog";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Field, FieldLabel } from "@/components/ui/field";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { invalidateAgents, namedRefsOf } from "@/features/chat/chat-personas-api";
import type { NamedRef } from "@/features/identity/principals";
import { actionErrorText } from "@/lib/action-errors";
import {
  deleteChatPersonaLabelMutation,
  listChatPersonaLabelsOptions,
  renameChatPersonaLabelMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";

/** The Tenant's agent labels: renamed or removed for every agent that carries them. */
export function AgentLabelsAdministration() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const [renaming, setRenaming] = useState<NamedRef>();
  const [name, setName] = useState("");
  const [removing, setRemoving] = useState<NamedRef>();
  const labels = useQuery({ ...listChatPersonaLabelsOptions(), select: namedRefsOf });
  const rename = useMutation({
    ...renameChatPersonaLabelMutation(),
    onSuccess: () => invalidateAgents(cache),
  });
  const remove = useMutation({
    ...deleteChatPersonaLabelMutation(),
    onSuccess: () => invalidateAgents(cache),
  });
  return (
    <section className="flex flex-col gap-3">
      <h2 className="text-lg font-medium">{ui("Nhãn trợ lý")}</h2>
      {labels.data?.length === 0 && (
        <p className="text-sm text-content-muted">
          {ui("Chưa có nhãn. Người tạo trợ lý thêm nhãn trong trình chỉnh sửa.")}
        </p>
      )}
      <ul className="flex flex-wrap gap-2">
        {labels.data?.map((label) => (
          <li
            key={label.id}
            className="flex items-center gap-1 rounded-lg border border-border-default py-0.5 pr-0.5 pl-3 text-sm"
          >
            {label.name}
            <IconButton
              size="sm"
              prominence="internal"
              aria-label={ui("Đổi tên nhãn {{v1}}", { v1: label.name })}
              onClick={() => {
                setRenaming(label);
                setName(label.name);
              }}
            >
              <Pencil />
            </IconButton>
            <IconButton
              size="sm"
              prominence="internal"
              aria-label={ui("Xóa nhãn {{v1}}", { v1: label.name })}
              onClick={() => setRemoving(label)}
            >
              <Trash2 />
            </IconButton>
          </li>
        ))}
      </ul>
      {renaming && (
        <FormDialog
          open
          onOpenChange={(open) => !open && setRenaming(undefined)}
          title={ui("Đổi tên nhãn")}
          description={ui("Tên mới áp dụng cho mọi trợ lý đang gắn nhãn này.")}
          submitDisabled={!name.trim()}
          onSubmit={async () => {
            await rename.mutateAsync({ path: { labelId: renaming.id }, body: { name } });
          }}
        >
          <Field>
            <FieldLabel htmlFor="agent-label-name">{ui("Tên nhãn")}</FieldLabel>
            <Input
              id="agent-label-name"
              maxLength={100}
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
          </Field>
        </FormDialog>
      )}
      <ConfirmDialog
        open={removing !== undefined}
        onOpenChange={(open) => !open && setRemoving(undefined)}
        title={ui("Xóa nhãn?")}
        description={ui("Nhãn sẽ được gỡ khỏi mọi trợ lý. Trợ lý không bị ảnh hưởng.")}
        confirmLabel={ui("Xóa nhãn")}
        pendingLabel={ui("Đang lưu…")}
        confirmTone="danger"
        errorMessage={actionErrorText}
        onConfirm={async () => {
          if (removing) await remove.mutateAsync({ path: { labelId: removing.id } });
        }}
      />
    </section>
  );
}
