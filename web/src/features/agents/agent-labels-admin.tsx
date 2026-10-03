import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Plus, Trash2 } from "lucide-react";
import { FormDialog } from "@/components/composites/form-dialog";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Field, FieldError, FieldLabel } from "@/components/ui/field";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { invalidateAgents, namedRefsOf } from "@/features/chat/chat-personas-api";
import type { NamedRef } from "@/features/identity/principals";
import { actionErrorText } from "@/lib/action-errors";
import {
  createChatPersonaLabelMutation,
  deleteChatPersonaLabelMutation,
  listChatPersonaLabelsOptions,
  renameChatPersonaLabelMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { withRequestTimeout } from "@/lib/api";

/**
 * The Tenant's agent labels, which only AGENTS_MANAGE creates, renames or removes; agent editors assign them to
 * keep the gallery filter curated.
 */
export function AgentLabelsAdministration() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const [renaming, setRenaming] = useState<NamedRef>();
  const [name, setName] = useState("");
  const [removing, setRemoving] = useState<NamedRef>();
  const [draft, setDraft] = useState("");
  const labels = useQuery({ ...listChatPersonaLabelsOptions(), select: namedRefsOf });
  const create = useMutation({
    ...createChatPersonaLabelMutation(),
    onSuccess: async () => {
      setDraft("");
      await invalidateAgents(cache);
    },
  });
  const trimmed = draft.trim();
  const taken = (labels.data ?? []).some(
    (label) => label.name.toLocaleLowerCase() === trimmed.toLocaleLowerCase(),
  );
  const rename = useMutation({
    ...withRequestTimeout(renameChatPersonaLabelMutation()),
    onSuccess: () => invalidateAgents(cache),
  });
  const remove = useMutation({
    ...withRequestTimeout(deleteChatPersonaLabelMutation()),
    onSuccess: () => invalidateAgents(cache),
  });
  return (
    <section className="flex flex-col gap-3">
      <div>
        <h2 className="text-lg font-medium">{ui("Nhãn trợ lý")}</h2>
        <p className="text-sm text-content-muted">
          {ui("Người tạo trợ lý chọn các nhãn này trong trình chỉnh sửa để phân loại thư viện.")}
        </p>
      </div>
      <form
        noValidate
        className="flex max-w-md items-start gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          if (trimmed && !taken && !create.isPending) create.mutate({ body: { name: trimmed } });
        }}
      >
        <Field className="min-w-0 flex-1" data-invalid={taken || create.isError || undefined}>
          <Input
            aria-label={ui("Tên nhãn mới")}
            aria-invalid={taken || create.isError || undefined}
            maxLength={100}
            value={draft}
            placeholder={ui("Tên nhãn mới")}
            onChange={(event) => {
              setDraft(event.target.value);
              create.reset();
            }}
          />
          {taken ? (
            <FieldError>{ui("Nhãn này đã có.")}</FieldError>
          ) : (
            create.isError && <FieldError>{actionErrorText(create.error)}</FieldError>
          )}
        </Field>
        <Button
          type="submit"
          prominence="secondary"
          pending={create.isPending}
          disabled={!trimmed || taken}
        >
          <Plus aria-hidden="true" />
          {ui("Thêm nhãn")}
        </Button>
      </form>
      {labels.data?.length === 0 && (
        <p className="text-sm text-content-muted">{ui("Chưa có nhãn nào.")}</p>
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
