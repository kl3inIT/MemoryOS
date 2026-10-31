import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { FormDialog } from "@/components/composites/form-dialog";
import { Field, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { invalidateAgents } from "@/features/chat/chat-personas-api";
import { createChatPersonaMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import { withRequestTimeout } from "@/lib/api";
import { handBrief } from "./agent-builder-handoff";
import { createRequest, maxTitle } from "./agent-form";

/**
 * A new assistant from its name, title and description (Rakazo `CreateBotForm`); it is saved with the defaults of a
 * new one and its editor opens.
 */
export function AgentCreateDialog({ onClose }: { onClose: () => void }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const navigate = useNavigate();
  const create = useMutation(withRequestTimeout(createChatPersonaMutation()));
  const [name, setName] = useState("");
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  return (
    <FormDialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={ui("Tạo trợ lý")}
      submitLabel={ui("Tạo trợ lý")}
      submitDisabled={!name.trim()}
      closeOnSuccess={false}
      onSubmit={async () => {
        const created = await create.mutateAsync({
          body: createRequest({ name: name.trim(), title: title.trim(), description }),
        });
        await invalidateAgents(cache);
        if (!created.id) return;
        // A described assistant opens on its Agent Builder, which builds the rest from the description.
        if (description.trim()) handBrief(created.id, description.trim());
        await navigate({ to: "/agents/$agentId/edit", params: { agentId: created.id } });
      }}
    >
      <div className="flex flex-col gap-4">
        <Field>
          <FieldLabel htmlFor="agent-create-name">{ui("Tên trợ lý")}</FieldLabel>
          <Input
            id="agent-create-name"
            maxLength={200}
            value={name}
            placeholder={ui("Ví dụ: OKR/KPI hằng tháng")}
            onChange={(event) => setName(event.target.value)}
          />
        </Field>
        <Field>
          <FieldLabel htmlFor="agent-create-title">{ui("Chức danh")}</FieldLabel>
          <Input
            id="agent-create-title"
            maxLength={maxTitle}
            value={title}
            placeholder={ui("Ví dụ: Chuyên viên phân tích tài chính")}
            onChange={(event) => setTitle(event.target.value)}
          />
        </Field>
        <Field>
          <FieldLabel htmlFor="agent-create-description">{ui("Mô tả")}</FieldLabel>
          <Textarea
            id="agent-create-description"
            maxLength={2000}
            rows={3}
            value={description}
            placeholder={ui("Trợ lý này giúp gì, dùng cho phòng ban nào")}
            onChange={(event) => setDescription(event.target.value)}
          />
        </Field>
      </div>
    </FormDialog>
  );
}
