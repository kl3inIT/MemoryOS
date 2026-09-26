import { useAppTranslation } from "@/i18n/use-app-translation";
import { useRef, type FocusEvent } from "react";
import { revalidateLogic, useStore } from "@tanstack/react-form";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Eye, EyeOff, MinusCircle } from "lucide-react";
import { z } from "zod";
import { useAppForm, useProblemErrors } from "@/components/form/app-form";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { FieldError } from "@/components/ui/field";
import { IconButton } from "@/components/ui/icon-button";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { actionErrorText, formField } from "@/lib/action-errors";
import {
  createChatPromptShortcutMutation,
  createPublicChatPromptShortcutMutation,
  deleteChatPromptShortcutMutation,
  deletePublicChatPromptShortcutMutation,
  hideChatPromptShortcutMutation,
  updateChatPromptShortcutMutation,
  updatePublicChatPromptShortcutMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { cn } from "@/lib/utils";
import { invalidateShortcuts, type Shortcut } from "./prompt-shortcuts-api";

export type ShortcutScope = "own" | "public";

function NameInput({
  value,
  readOnly,
  invalid,
  onChange,
}: {
  value: string;
  readOnly?: boolean;
  invalid?: boolean;
  onChange?: (value: string) => void;
}) {
  const ui = useAppTranslation();
  return (
    <InputGroup className="min-w-0 flex-1">
      <InputGroupAddon aria-hidden="true">/</InputGroupAddon>
      <InputGroupInput
        aria-label={ui("Tên lệnh tắt")}
        aria-invalid={invalid || undefined}
        maxLength={100}
        readOnly={readOnly}
        value={value}
        placeholder={readOnly ? undefined : ui("Tên lệnh tắt mới")}
        onChange={(event) => onChange?.(event.target.value)}
      />
    </InputGroup>
  );
}

function ContentInput({
  value,
  readOnly,
  invalid,
  onChange,
}: {
  value: string;
  readOnly?: boolean;
  invalid?: boolean;
  onChange?: (value: string) => void;
}) {
  const ui = useAppTranslation();
  return (
    <textarea
      aria-label={ui("Nội dung lệnh tắt")}
      aria-invalid={invalid || undefined}
      className={cn(
        formField,
        "min-w-0 flex-1 resize-y",
        readOnly && "bg-surface-sunken text-content-secondary",
      )}
      rows={3}
      maxLength={8000}
      readOnly={readOnly}
      value={value}
      placeholder={readOnly ? undefined : ui("Nội dung sẽ được chèn vào ô chat khi chọn lệnh này")}
      onChange={(event) => onChange?.(event.target.value)}
    />
  );
}

/** The create, update and delete requests of one scope, each refreshing every shortcut read. */
function useShortcutMutations(scope: ShortcutScope) {
  const cache = useQueryClient();
  const refresh = { onSuccess: () => invalidateShortcuts(cache) };
  const own = {
    create: useMutation({ ...createChatPromptShortcutMutation(), ...refresh }),
    update: useMutation({ ...updateChatPromptShortcutMutation(), ...refresh }),
    remove: useMutation({ ...deleteChatPromptShortcutMutation(), ...refresh }),
  };
  const shared = {
    create: useMutation({ ...createPublicChatPromptShortcutMutation(), ...refresh }),
    update: useMutation({ ...updatePublicChatPromptShortcutMutation(), ...refresh }),
    remove: useMutation({ ...deletePublicChatPromptShortcutMutation(), ...refresh }),
  };
  return scope === "public" ? shared : own;
}

/** One shortcut as a name and content pair, saved when focus leaves the pair (Onyx settings). */
export function ShortcutFields({
  shortcut,
  scope,
  onCreated,
}: {
  shortcut?: Shortcut;
  scope: ShortcutScope;
  onCreated?: () => void;
}) {
  const ui = useAppTranslation();
  const notify = useActionNotifications();
  const problemErrors = useProblemErrors();
  const { create, update, remove } = useShortcutMutations(scope);
  // A blur during a save is replayed after it finishes, with the server's newer revision.
  const again = useRef(false);
  const form = useAppForm({
    defaultValues: { name: shortcut?.name ?? "", content: shortcut?.content ?? "" },
    validationLogic: revalidateLogic(),
    validators: {
      onDynamic: z
        .object({ name: z.string(), content: z.string() })
        .refine(
          (value) => value.name.trim() !== "" && value.content.trim() !== "",
          ui("Cần cả tên và nội dung."),
        ),
    },
    onSubmit: async ({ value, formApi }) => {
      formApi.setErrorMap({ onSubmit: { form: undefined, fields: {} } });
      const body = { name: value.name.trim(), content: value.content };
      try {
        if (shortcut) {
          await update.mutateAsync({
            path: { shortcutId: shortcut.id },
            query: { revision: shortcut.revision },
            body,
          });
          notify({ title: ui("Đã lưu lệnh tắt"), tone: "success" });
        } else {
          await create.mutateAsync({ body });
          notify({ title: ui("Đã tạo lệnh tắt"), tone: "success" });
          onCreated?.();
        }
      } catch (cause) {
        formApi.setErrorMap({ onSubmit: problemErrors(cause) });
      }
    },
  });
  const name = useStore(form.store, (state) => state.values.name);
  const content = useStore(form.store, (state) => state.values.content);
  const error = useStore(form.store, (state) => state.errors.find(Boolean));
  const empty = !name.trim() && !content.trim();

  async function commit() {
    if (form.state.isSubmitting) {
      again.current = true;
      return;
    }
    const unchanged = shortcut && name.trim() === shortcut.name && content === shortcut.content;
    if (unchanged || (!shortcut && empty)) return;
    await form.handleSubmit();
    if (again.current) {
      again.current = false;
      if (shortcut) void commit();
    }
  }

  function clear() {
    if (shortcut) {
      remove.mutate(
        { path: { shortcutId: shortcut.id } },
        { onSuccess: () => notify({ title: ui("Đã xóa lệnh tắt"), tone: "success" }) },
      );
      return;
    }
    form.reset({ name: "", content: "" });
  }

  const message =
    typeof error === "string"
      ? error
      : remove.error
        ? actionErrorText(remove.error)
        : error && typeof error === "object" && "message" in error
          ? String(error.message)
          : undefined;
  return (
    <div
      className="flex flex-col gap-1.5"
      onBlur={(event: FocusEvent<HTMLDivElement>) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) void commit();
      }}
    >
      <div className="flex items-center gap-1">
        <NameInput
          value={name}
          invalid={!!message && !name.trim()}
          onChange={(value) => form.setFieldValue("name", value)}
        />
        {shortcut || !empty ? (
          <IconButton
            type="button"
            prominence="tertiary"
            aria-label={
              shortcut
                ? ui("Xóa lệnh tắt {{v1}}", { v1: shortcut.name })
                : ui("Xóa nội dung đang nhập")
            }
            disabled={remove.isPending}
            onMouseDown={(event) => event.preventDefault()}
            onClick={clear}
          >
            <MinusCircle />
          </IconButton>
        ) : (
          <span className="size-9 shrink-0" />
        )}
      </div>
      <div className="flex gap-1">
        <ContentInput
          value={content}
          invalid={!!message && !content.trim()}
          onChange={(value) => form.setFieldValue("content", value)}
        />
        <span className="w-9 shrink-0" />
      </div>
      {message && <FieldError>{message}</FieldError>}
    </div>
  );
}

/** A public shortcut as members see it: read-only, hideable for themselves. */
export function SharedShortcut({ shortcut }: { shortcut: Shortcut }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const notify = useActionNotifications();
  const hide = useMutation({
    ...hideChatPromptShortcutMutation(),
    onSuccess: () => invalidateShortcuts(cache),
    onError: (cause) => notify({ title: actionErrorText(cause), tone: "error" }),
  });
  return (
    <div className={cn("flex flex-col gap-1.5", shortcut.hidden && "opacity-60")}>
      <div className="flex items-center gap-1">
        <NameInput value={shortcut.name} readOnly />
        <IconButton
          type="button"
          prominence="tertiary"
          disabled={hide.isPending}
          aria-label={
            shortcut.hidden
              ? ui("Hiện lại {{v1}}", { v1: shortcut.name })
              : ui("Ẩn {{v1}} cho riêng tôi", { v1: shortcut.name })
          }
          title={shortcut.hidden ? ui("Hiện lại") : ui("Ẩn cho riêng tôi")}
          onClick={() =>
            hide.mutate({ path: { shortcutId: shortcut.id }, body: { hidden: !shortcut.hidden } })
          }
        >
          {shortcut.hidden ? <Eye /> : <EyeOff />}
        </IconButton>
      </div>
      <div className="flex gap-1">
        <ContentInput value={shortcut.content} readOnly />
        <span className="w-9 shrink-0" />
      </div>
      {shortcut.hidden && (
        <span className="font-secondary-body text-content-muted">{ui("Đã ẩn")}</span>
      )}
    </div>
  );
}
