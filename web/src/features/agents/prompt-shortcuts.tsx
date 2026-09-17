import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMemo, useState } from "react";
import { ComposerPrimitive, unstable_useSlashCommandAdapter, useAui } from "@assistant-ui/react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { EyeOff, Eye, Pencil, Plus, Trash2 } from "lucide-react";
import { z } from "zod";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  createChatPromptShortcut,
  createPublicChatPromptShortcut,
  deleteChatPromptShortcut,
  deletePublicChatPromptShortcut,
  getChatPromptShortcutPreferences,
  hideChatPromptShortcut,
  listChatPromptShortcuts,
  listPublicChatPromptShortcuts,
  setChatPromptShortcutPreferences,
  updateChatPromptShortcut,
  updatePublicChatPromptShortcut,
} from "@/lib/hey-api/sdk.gen";
import { ChatDialog } from "@/features/chat/chat-dialog";
import { chatActionError, chatField } from "@/features/chat/chat-action-utils";

const shortcutSchema = z.object({
  id: z.string().uuid(),
  name: z.string(),
  content: z.string(),
  active: z.boolean(),
  isPublic: z.boolean(),
  hidden: z.boolean(),
  revision: z.number().int(),
});
type Shortcut = z.infer<typeof shortcutSchema>;
const shortcutsKey = ["chat-prompt-shortcuts"] as const;

function useShortcutPreferences() {
  const { actorId, authorizationVersion } = useApplicationSession();
  return useQuery({
    queryKey: [...shortcutsKey, "preferences", actorId, authorizationVersion],
    queryFn: async ({ signal }) =>
      z
        .object({ enabled: z.boolean() })
        .parse((await getChatPromptShortcutPreferences({ signal, throwOnError: true })).data),
  });
}

function useShortcuts(includeHidden: boolean) {
  const { actorId, authorizationVersion } = useApplicationSession();
  return useQuery({
    queryKey: [...shortcutsKey, actorId, authorizationVersion, includeHidden],
    queryFn: async ({ signal }) =>
      shortcutSchema
        .array()
        .parse(
          (await listChatPromptShortcuts({ query: { includeHidden }, signal, throwOnError: true }))
            .data,
        ),
  });
}

/**
 * Typing "/" at the start of the composer lists active shortcuts; choosing one replaces the draft with its content
 * (Onyx {@code AppInputBar} prompt shortcuts). Renders nothing when the actor turned shortcuts off.
 */
export function ChatPromptShortcutPopover() {
  const ui = useAppTranslation();
  const aui = useAui();
  const preferences = useShortcutPreferences();
  const shortcuts = useShortcuts(false);
  const commands = useMemo(
    () =>
      (shortcuts.data ?? [])
        .filter((shortcut) => shortcut.active)
        .map((shortcut) => ({
          id: shortcut.name,
          label: `/${shortcut.name}`,
          description:
            shortcut.content.length > 80 ? `${shortcut.content.slice(0, 80)}…` : shortcut.content,
          // The trigger text is removed first; replace the draft afterwards.
          execute: () => setTimeout(() => aui.composer().setText(shortcut.content), 0),
        })),
    [shortcuts.data, aui],
  );
  const slash = unstable_useSlashCommandAdapter({ commands, removeOnExecute: true });
  if (preferences.data?.enabled === false || commands.length === 0) return null;
  return (
    <ComposerPrimitive.Unstable_TriggerPopover
      char="/"
      adapter={slash.adapter}
      className="z-50 max-h-72 w-[min(28rem,calc(100vw-2rem))] overflow-y-auto rounded-xl border border-border-subtle bg-surface-overlay p-1.5 shadow-md"
    >
      <ComposerPrimitive.Unstable_TriggerPopover.Action {...slash.action} />
      <ComposerPrimitive.Unstable_TriggerPopoverItems>
        {(items) =>
          items.map((item, index) => (
            <ComposerPrimitive.Unstable_TriggerPopoverItem
              key={item.id}
              item={item}
              index={index}
              className="flex w-full flex-col items-start rounded-lg px-3 py-2 text-left data-[highlighted]:bg-surface-sunken"
            >
              <span className="text-sm font-medium">{item.label}</span>
              {item.description && (
                <span className="line-clamp-1 text-xs text-content-muted">{item.description}</span>
              )}
            </ComposerPrimitive.Unstable_TriggerPopoverItem>
          ))
        }
      </ComposerPrimitive.Unstable_TriggerPopoverItems>
      <Link
        to="/settings/general"
        className="mt-1 block rounded-lg px-3 py-2 text-sm text-content-secondary hover:bg-surface-subtle"
      >
        {ui("Quản lý lệnh tắt")}
      </Link>
    </ComposerPrimitive.Unstable_TriggerPopover>
  );
}

/** Personal settings: own shortcuts, public shortcuts to hide, and the on/off preference. */
export function PersonalPromptShortcuts() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const preferences = useShortcutPreferences();
  const shortcuts = useShortcuts(true);
  const [editing, setEditing] = useState<Shortcut | "new">();
  const [removing, setRemoving] = useState<Shortcut>();
  const [error, setError] = useState<string>();
  const refresh = () => cache.invalidateQueries({ queryKey: shortcutsKey });
  const own = shortcuts.data?.filter((shortcut) => !shortcut.isPublic) ?? [];
  const shared = shortcuts.data?.filter((shortcut) => shortcut.isPublic) ?? [];
  return (
    <section className="space-y-4" aria-labelledby="prompt-shortcuts">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 id="prompt-shortcuts" className="text-lg font-medium">
            {ui("Lệnh tắt câu hỏi")}
          </h2>
          <p className="text-sm text-content-secondary">
            {ui("Gõ / ở đầu ô chat để chèn nhanh một câu hỏi đã lưu.")}
          </p>
        </div>
        <label className="flex items-center gap-2 text-sm">
          {ui("Bật lệnh tắt")}
          <Switch
            checked={preferences.data?.enabled ?? true}
            disabled={!preferences.isSuccess}
            aria-label={ui("Bật lệnh tắt")}
            onCheckedChange={async (enabled) => {
              setError(undefined);
              try {
                await setChatPromptShortcutPreferences({
                  body: { enabled },
                  headers: sameOriginMutationHeaders,
                  signal: AbortSignal.timeout(30000),
                  throwOnError: true,
                });
                await refresh();
              } catch (cause) {
                setError(chatActionError(cause));
              }
            }}
          />
        </label>
      </div>
      {(error || shortcuts.isError) && (
        <p role="alert" className="text-sm text-status-danger-content">
          {error ?? ui("Không tải được lệnh tắt.")}
        </p>
      )}
      <ShortcutRows
        shortcuts={own}
        empty={ui("Bạn chưa có lệnh tắt nào.")}
        onEdit={setEditing}
        onRemove={setRemoving}
      />
      <Button prominence="secondary" onClick={() => setEditing("new")}>
        <Plus aria-hidden="true" className="size-4" />
        {ui("Thêm lệnh tắt")}
      </Button>
      {shared.length > 0 && (
        <div className="space-y-2">
          <h3 className="text-sm font-medium">{ui("Lệnh tắt dùng chung")}</h3>
          <ul className="divide-y divide-border-subtle rounded-xl border border-border-default">
            {shared.map((shortcut) => (
              <li key={shortcut.id} className="flex items-center gap-3 px-3 py-2">
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-medium">/{shortcut.name}</span>
                  <span className="block truncate text-xs text-content-muted">
                    {shortcut.content}
                  </span>
                </span>
                {shortcut.hidden && <Badge variant="outline">{ui("Đã ẩn")}</Badge>}
                <IconButton
                  size="sm"
                  prominence="internal"
                  aria-label={
                    shortcut.hidden
                      ? ui("Hiện /{{v1}}", { v1: shortcut.name })
                      : ui("Ẩn /{{v1}}", { v1: shortcut.name })
                  }
                  onClick={async () => {
                    setError(undefined);
                    try {
                      await hideChatPromptShortcut({
                        path: { shortcutId: shortcut.id },
                        body: { hidden: !shortcut.hidden },
                        headers: sameOriginMutationHeaders,
                        signal: AbortSignal.timeout(30000),
                        throwOnError: true,
                      });
                      await refresh();
                    } catch (cause) {
                      setError(chatActionError(cause));
                    }
                  }}
                >
                  {shortcut.hidden ? <Eye /> : <EyeOff />}
                </IconButton>
              </li>
            ))}
          </ul>
        </div>
      )}
      {editing && (
        <ShortcutEditor
          shortcut={editing === "new" ? undefined : editing}
          scope="own"
          onClose={() => setEditing(undefined)}
        />
      )}
      <RemoveShortcut shortcut={removing} scope="own" onClose={() => setRemoving(undefined)} />
    </section>
  );
}

/** Administration of public shortcuts (AGENTS_MANAGE). */
export function PublicPromptShortcuts() {
  const ui = useAppTranslation();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [editing, setEditing] = useState<Shortcut | "new">();
  const [removing, setRemoving] = useState<Shortcut>();
  const shortcuts = useQuery({
    queryKey: [...shortcutsKey, "public", actorId, authorizationVersion],
    queryFn: async ({ signal }) =>
      shortcutSchema
        .array()
        .parse((await listPublicChatPromptShortcuts({ signal, throwOnError: true })).data),
  });
  return (
    <section className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-medium">{ui("Lệnh tắt dùng chung")}</h2>
          <p className="text-sm text-content-secondary">
            {ui("Mọi thành viên thấy các lệnh tắt này khi gõ / và có thể ẩn cho riêng mình.")}
          </p>
        </div>
        <Button prominence="secondary" onClick={() => setEditing("new")}>
          <Plus aria-hidden="true" className="size-4" />
          {ui("Thêm lệnh tắt")}
        </Button>
      </div>
      {shortcuts.isError && (
        <p role="alert" className="text-sm text-status-danger-content">
          {ui("Không tải được lệnh tắt.")}
        </p>
      )}
      <ShortcutRows
        shortcuts={shortcuts.data ?? []}
        empty={ui("Chưa có lệnh tắt dùng chung.")}
        onEdit={setEditing}
        onRemove={setRemoving}
      />
      {editing && (
        <ShortcutEditor
          shortcut={editing === "new" ? undefined : editing}
          scope="public"
          onClose={() => setEditing(undefined)}
        />
      )}
      <RemoveShortcut shortcut={removing} scope="public" onClose={() => setRemoving(undefined)} />
    </section>
  );
}

function ShortcutRows({
  shortcuts,
  empty,
  onEdit,
  onRemove,
}: {
  shortcuts: Shortcut[];
  empty: string;
  onEdit: (shortcut: Shortcut) => void;
  onRemove: (shortcut: Shortcut) => void;
}) {
  const ui = useAppTranslation();
  if (shortcuts.length === 0) return <p className="text-sm text-content-muted">{empty}</p>;
  return (
    <ul className="divide-y divide-border-subtle rounded-xl border border-border-default">
      {shortcuts.map((shortcut) => (
        <li key={shortcut.id} className="flex items-center gap-3 px-3 py-2">
          <span className="min-w-0 flex-1">
            <span className="block text-sm font-medium">/{shortcut.name}</span>
            <span className="block truncate text-xs text-content-muted">{shortcut.content}</span>
          </span>
          {!shortcut.active && <Badge variant="outline">{ui("Tắt")}</Badge>}
          <IconButton
            size="sm"
            prominence="internal"
            aria-label={ui("Sửa /{{v1}}", { v1: shortcut.name })}
            onClick={() => onEdit(shortcut)}
          >
            <Pencil />
          </IconButton>
          <IconButton
            size="sm"
            prominence="internal"
            aria-label={ui("Xóa /{{v1}}", { v1: shortcut.name })}
            onClick={() => onRemove(shortcut)}
          >
            <Trash2 />
          </IconButton>
        </li>
      ))}
    </ul>
  );
}

function ShortcutEditor({
  shortcut,
  scope,
  onClose,
}: {
  shortcut?: Shortcut;
  scope: "own" | "public";
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const [name, setName] = useState(shortcut?.name ?? "");
  const [content, setContent] = useState(shortcut?.content ?? "");
  const [active, setActive] = useState(shortcut?.active ?? true);
  const nameInvalid = /\s/.test(name.trim());
  return (
    <ChatDialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={shortcut ? ui("Sửa lệnh tắt") : ui("Thêm lệnh tắt")}
      description={ui(
        "Tên dùng sau dấu /, không có khoảng trắng. Nội dung thay cho câu hỏi đang soạn.",
      )}
      submitDisabled={!name.trim() || !content.trim() || nameInvalid}
      onSubmit={async () => {
        const body = { name: name.trim(), content, active };
        const options = {
          headers: sameOriginMutationHeaders,
          signal: AbortSignal.timeout(30000),
          throwOnError: true as const,
        };
        if (shortcut) {
          const request = {
            path: { shortcutId: shortcut.id },
            query: { revision: shortcut.revision },
            body,
            ...options,
          };
          if (scope === "public") await updatePublicChatPromptShortcut(request);
          else await updateChatPromptShortcut(request);
        } else if (scope === "public") await createPublicChatPromptShortcut({ body, ...options });
        else await createChatPromptShortcut({ body, ...options });
        await cache.invalidateQueries({ queryKey: shortcutsKey });
      }}
    >
      <div className="space-y-4">
        <label className="block space-y-1">
          <span>{ui("Tên lệnh tắt")}</span>
          <Input
            maxLength={100}
            value={name}
            placeholder={ui("kpi-thang")}
            onChange={(e) => setName(e.target.value)}
          />
          {nameInvalid && (
            <span role="alert" className="block text-xs text-status-danger-content">
              {ui("Tên không được chứa khoảng trắng.")}
            </span>
          )}
        </label>
        <label className="block space-y-1">
          <span>{ui("Nội dung")}</span>
          <textarea
            className={chatField}
            rows={5}
            maxLength={8000}
            value={content}
            onChange={(e) => setContent(e.target.value)}
          />
        </label>
        <label className="flex items-center justify-between gap-4">
          {ui("Đang dùng")}
          <Switch checked={active} onCheckedChange={setActive} aria-label={ui("Đang dùng")} />
        </label>
      </div>
    </ChatDialog>
  );
}

function RemoveShortcut({
  shortcut,
  scope,
  onClose,
}: {
  shortcut?: Shortcut;
  scope: "own" | "public";
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  return (
    <ConfirmDialog
      open={shortcut !== undefined}
      onOpenChange={(open) => !open && onClose()}
      title={ui("Xóa lệnh tắt?")}
      description={
        scope === "public"
          ? ui("Lệnh tắt sẽ biến mất với mọi thành viên.")
          : ui("Bạn có thể tạo lại sau.")
      }
      confirmLabel={ui("Xóa lệnh tắt")}
      pendingLabel={ui("Đang lưu…")}
      confirmTone="danger"
      errorMessage={chatActionError}
      onConfirm={async () => {
        if (!shortcut) return;
        const request = {
          path: { shortcutId: shortcut.id },
          headers: sameOriginMutationHeaders,
          signal: AbortSignal.timeout(30000),
          throwOnError: true as const,
        };
        if (scope === "public") await deletePublicChatPromptShortcut(request);
        else await deleteChatPromptShortcut(request);
        await cache.invalidateQueries({ queryKey: shortcutsKey });
      }}
    />
  );
}
