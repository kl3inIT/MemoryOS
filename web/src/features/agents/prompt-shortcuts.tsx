import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMemo, useRef, useState, type FocusEvent, type ReactNode } from "react";
import {
  ComposerPrimitive,
  useAui,
  type Unstable_TriggerItem,
  type Unstable_TriggerMatcher,
} from "@assistant-ui/react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Eye, EyeOff, MinusCircle, Plus } from "lucide-react";
import { z } from "zod";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Button } from "@/components/ui/button";
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
import { cn } from "@/lib/utils";
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
type Scope = "own" | "public";
const shortcutsKey = ["chat-prompt-shortcuts"] as const;

/** Vietnamese users often type without diacritics: "/tom tat" finds "Tóm tắt hợp đồng". */
function fold(value: string) {
  return value
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .replace(/[đĐ]/g, "d")
    .toLocaleLowerCase("vi");
}

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

/** Shortcuts the composer offers: enabled by the actor, active and not hidden. */
function useComposerShortcuts() {
  const preferences = useShortcutPreferences();
  const shortcuts = useShortcuts(false);
  const enabled = preferences.data?.enabled === true;
  const items = useMemo(
    () => (shortcuts.data ?? []).filter((shortcut) => shortcut.active && !shortcut.hidden),
    [shortcuts.data],
  );
  return { enabled, items };
}

function matching(items: Shortcut[], query: string) {
  const folded = fold(query.trim());
  return folded ? items.filter((shortcut) => fold(shortcut.name).includes(folded)) : items;
}

/** The menu row shared by "/" and the `+` menu (Onyx `LineItemButton`: name, then content). */
function ShortcutMenuRow({ name, content }: { name: string; content: string }) {
  return (
    <span className="flex min-w-0 flex-col">
      <span className="truncate font-main-ui-action text-content-primary">{name}</span>
      <span className="truncate font-secondary-body text-content-muted">{content.trim()}</span>
    </span>
  );
}

function CreateShortcutLink() {
  const ui = useAppTranslation();
  return (
    <Link
      to="/settings/general"
      hash="prompt-shortcuts"
      className="flex items-center gap-2 rounded-lg px-3 py-2 font-main-ui-body text-content-secondary outline-none hover:bg-surface-sunken focus-visible:ring-2 focus-visible:ring-focus-ring/40"
    >
      <Plus aria-hidden="true" className="size-4" />
      {ui("Tạo lệnh tắt")}
    </Link>
  );
}

// As in Onyx, the whole draft is the query once it starts with "/", so names may contain spaces.
const draftStartsWithSlash: Unstable_TriggerMatcher = (text, _char, cursor) => {
  if (!text.startsWith("/") || cursor < 1) return null;
  const query = text.slice(1, cursor);
  return query.includes("\n") ? null : { query, offset: 0, endOffset: cursor };
};

/**
 * Typing "/" at the start of the composer lists shortcuts; choosing one replaces the draft with its content
 * (Onyx {@code AppInputBar}). Renders nothing when the actor turned shortcuts off.
 */
export function ChatPromptShortcutPopover() {
  const aui = useAui();
  const { enabled, items } = useComposerShortcuts();
  const byId = useMemo(() => new Map(items.map((shortcut) => [shortcut.id, shortcut])), [items]);
  const adapter = useMemo(
    () => ({
      categories: () => [],
      categoryItems: () => [],
      search: (query: string): Unstable_TriggerItem[] =>
        matching(items, query).map((shortcut) => ({
          id: shortcut.id,
          type: "command",
          label: shortcut.name,
          description: shortcut.content,
        })),
    }),
    [items],
  );
  if (!enabled || items.length === 0) return null;
  return (
    <ComposerPrimitive.Unstable_TriggerPopover
      char="/"
      matcher={draftStartsWithSlash}
      adapter={adapter}
      className="z-50 max-h-80 w-[min(32rem,calc(100vw-2rem))] overflow-y-auto rounded-xl border border-border-subtle bg-surface-overlay p-1.5 shadow-md"
    >
      <ComposerPrimitive.Unstable_TriggerPopover.Action
        removeOnExecute
        onExecute={(item) => {
          const shortcut = byId.get(item.id);
          // The trigger text is removed first; replace the draft afterwards.
          if (shortcut) setTimeout(() => aui.composer().setText(shortcut.content), 0);
        }}
      />
      <ComposerPrimitive.Unstable_TriggerPopoverItems>
        {(found) =>
          found.map((item, index) => (
            <ComposerPrimitive.Unstable_TriggerPopoverItem
              key={item.id}
              item={item}
              index={index}
              className="flex w-full rounded-lg px-3 py-2 text-left data-[highlighted]:bg-surface-sunken"
            >
              <ShortcutMenuRow name={item.label} content={item.description ?? ""} />
            </ComposerPrimitive.Unstable_TriggerPopoverItem>
          ))
        }
      </ComposerPrimitive.Unstable_TriggerPopoverItems>
      <div role="separator" className="my-1 border-t border-border-subtle" />
      <CreateShortcutLink />
    </ComposerPrimitive.Unstable_TriggerPopover>
  );
}

/** Personal settings, laid out like Onyx: inline rows saved on blur, public rows read-only and hideable. */
export function PersonalPromptShortcuts() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const notify = useActionNotifications();
  const preferences = useShortcutPreferences();
  const shortcuts = useShortcuts(true);
  const own = shortcuts.data?.filter((shortcut) => !shortcut.isPublic) ?? [];
  const shared = shortcuts.data?.filter((shortcut) => shortcut.isPublic) ?? [];
  return (
    <section
      id="prompt-shortcuts"
      aria-labelledby="prompt-shortcuts-title"
      className="flex scroll-mt-6 flex-col gap-5"
    >
      <header>
        <h2 id="prompt-shortcuts-title" className="font-heading-h3 text-content-primary">
          {ui("Lệnh tắt")}
        </h2>
        <p className="mt-1 font-secondary-body text-content-muted">
          {ui("Câu hỏi hay dùng được lưu sẵn. Gõ / ở đầu ô chat để chèn vào.")}
        </p>
      </header>
      <SettingRows>
        <SettingRow
          htmlFor="prompt-shortcuts-enabled"
          title={ui("Dùng lệnh tắt")}
          description={ui("Tắt nếu bạn muốn gõ / như ký tự bình thường.")}
          control={
            <Switch
              id="prompt-shortcuts-enabled"
              checked={preferences.data?.enabled ?? true}
              disabled={!preferences.isSuccess}
              onCheckedChange={async (enabled) => {
                try {
                  await setChatPromptShortcutPreferences({
                    body: { enabled },
                    headers: sameOriginMutationHeaders,
                    signal: AbortSignal.timeout(30000),
                    throwOnError: true,
                  });
                  await cache.invalidateQueries({ queryKey: shortcutsKey });
                } catch (cause) {
                  notify({ title: chatActionError(cause), tone: "error" });
                }
              }}
            />
          }
        />
      </SettingRows>
      {shortcuts.isError && (
        <p role="alert" className="font-secondary-body text-status-danger-content">
          {ui("Không tải được lệnh tắt.")}{" "}
          <Button size="sm" prominence="tertiary" onClick={() => void shortcuts.refetch()}>
            {ui("Tải lại")}
          </Button>
        </p>
      )}
      {shortcuts.isSuccess && (
        <>
          <ShortcutGroup title={ui("Của tôi")}>
            <EditableShortcuts shortcuts={own} scope="own" />
          </ShortcutGroup>
          {shared.length > 0 && (
            <ShortcutGroup
              title={ui("Dùng chung")}
              description={ui("Do quản trị viên tạo cho cả công ty. Ẩn những lệnh bạn không dùng.")}
            >
              {shared.map((shortcut) => (
                <SharedShortcut key={`${shortcut.id}-${shortcut.revision}`} shortcut={shortcut} />
              ))}
            </ShortcutGroup>
          )}
        </>
      )}
    </section>
  );
}

/** Administration of public shortcuts (AGENTS_MANAGE), edited inline like personal ones. */
export function PublicPromptShortcuts() {
  const ui = useAppTranslation();
  const { actorId, authorizationVersion } = useApplicationSession();
  const shortcuts = useQuery({
    queryKey: [...shortcutsKey, "public", actorId, authorizationVersion],
    queryFn: async ({ signal }) =>
      shortcutSchema
        .array()
        .parse((await listPublicChatPromptShortcuts({ signal, throwOnError: true })).data),
  });
  return (
    <section aria-labelledby="public-prompt-shortcuts" className="flex flex-col gap-4">
      <header>
        <h2 id="public-prompt-shortcuts" className="font-heading-h3 text-content-primary">
          {ui("Lệnh tắt dùng chung")}
        </h2>
        <p className="mt-1 font-secondary-body text-content-muted">
          {ui("Mọi thành viên thấy các lệnh tắt này khi gõ / và có thể ẩn cho riêng mình.")}
        </p>
      </header>
      {shortcuts.isError && (
        <p role="alert" className="font-secondary-body text-status-danger-content">
          {ui("Không tải được lệnh tắt.")}
        </p>
      )}
      {shortcuts.isSuccess && <EditableShortcuts shortcuts={shortcuts.data} scope="public" />}
    </section>
  );
}

function ShortcutGroup({
  title,
  description,
  children,
}: {
  title: string;
  description?: string;
  children: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-3">
      <div>
        <h3 className="font-main-ui-action text-content-primary">{title}</h3>
        {description && <p className="font-secondary-body text-content-muted">{description}</p>}
      </div>
      {children}
    </div>
  );
}

function EditableShortcuts({ shortcuts, scope }: { shortcuts: Shortcut[]; scope: Scope }) {
  // The trailing empty pair creates a shortcut; a new key clears it once that succeeds.
  const [draftKey, setDraftKey] = useState(0);
  return (
    <div className="flex flex-col gap-4">
      {shortcuts.map((shortcut) => (
        <ShortcutFields key={shortcut.id} shortcut={shortcut} scope={scope} />
      ))}
      <ShortcutFields
        key={`new-${draftKey}`}
        scope={scope}
        onCreated={() => setDraftKey((key) => key + 1)}
      />
    </div>
  );
}

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
    <div className="relative min-w-0">
      <span
        aria-hidden="true"
        className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 font-main-ui-body text-content-muted"
      >
        /
      </span>
      <Input
        aria-label={ui("Tên lệnh tắt")}
        aria-invalid={invalid || undefined}
        maxLength={100}
        readOnly={readOnly}
        value={value}
        placeholder={readOnly ? undefined : ui("Tên lệnh tắt mới")}
        className={cn("pl-6", readOnly && "bg-surface-sunken text-content-secondary")}
        onChange={(event) => onChange?.(event.target.value)}
      />
    </div>
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
      className={cn(chatField, "resize-y", readOnly && "bg-surface-sunken text-content-secondary")}
      rows={3}
      maxLength={8000}
      readOnly={readOnly}
      value={value}
      placeholder={readOnly ? undefined : ui("Nội dung sẽ được chèn vào ô chat khi chọn lệnh này")}
      onChange={(event) => onChange?.(event.target.value)}
    />
  );
}

/** One shortcut as a name and content pair, saved when focus leaves the pair (Onyx settings). */
function ShortcutFields({
  shortcut,
  scope,
  onCreated,
}: {
  shortcut?: Shortcut;
  scope: Scope;
  onCreated?: () => void;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const notify = useActionNotifications();
  const [name, setName] = useState(shortcut?.name ?? "");
  const [content, setContent] = useState(shortcut?.content ?? "");
  const [error, setError] = useState<string>();
  const [removing, setRemoving] = useState(false);
  const saving = useRef(false);
  // A blur during a save is replayed after it finishes, with the server's newer revision.
  const again = useRef(false);
  const empty = !name.trim() && !content.trim();

  async function commit() {
    const trimmed = name.trim();
    const unchanged = shortcut && trimmed === shortcut.name && content === shortcut.content;
    if (saving.current) {
      again.current = true;
      return;
    }
    if (unchanged || (!shortcut && empty)) return;
    if (!trimmed || !content.trim()) {
      setError(ui("Cần cả tên và nội dung."));
      return;
    }
    saving.current = true;
    setError(undefined);
    const body = { name: trimmed, content };
    const options = {
      headers: sameOriginMutationHeaders,
      signal: AbortSignal.timeout(30000),
      throwOnError: true as const,
    };
    try {
      if (shortcut) {
        const request = {
          path: { shortcutId: shortcut.id },
          query: { revision: shortcut.revision },
          body,
          ...options,
        };
        if (scope === "public") await updatePublicChatPromptShortcut(request);
        else await updateChatPromptShortcut(request);
        notify({ title: ui("Đã lưu lệnh tắt"), tone: "success" });
      } else {
        if (scope === "public") await createPublicChatPromptShortcut({ body, ...options });
        else await createChatPromptShortcut({ body, ...options });
        notify({ title: ui("Đã tạo lệnh tắt"), tone: "success" });
        onCreated?.();
      }
      await cache.invalidateQueries({ queryKey: shortcutsKey });
    } catch (cause) {
      setError(chatActionError(cause));
    } finally {
      saving.current = false;
    }
    if (again.current) {
      again.current = false;
      if (shortcut) void commit();
    }
  }

  async function remove() {
    if (!shortcut) {
      setName("");
      setContent("");
      setError(undefined);
      return;
    }
    setRemoving(true);
    try {
      const request = {
        path: { shortcutId: shortcut.id },
        headers: sameOriginMutationHeaders,
        signal: AbortSignal.timeout(30000),
        throwOnError: true as const,
      };
      if (scope === "public") await deletePublicChatPromptShortcut(request);
      else await deleteChatPromptShortcut(request);
      notify({ title: ui("Đã xóa lệnh tắt"), tone: "success" });
      await cache.invalidateQueries({ queryKey: shortcutsKey });
    } catch (cause) {
      setError(chatActionError(cause));
      setRemoving(false);
    }
  }

  return (
    <div
      className="grid grid-cols-[minmax(0,1fr)_auto] gap-x-1 gap-y-1.5"
      onBlur={(event: FocusEvent<HTMLDivElement>) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) void commit();
      }}
    >
      <NameInput value={name} invalid={!!error && !name.trim()} onChange={setName} />
      {shortcut || !empty ? (
        <IconButton
          type="button"
          prominence="tertiary"
          aria-label={
            shortcut
              ? ui("Xóa lệnh tắt {{v1}}", { v1: shortcut.name })
              : ui("Xóa nội dung đang nhập")
          }
          disabled={removing}
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => void remove()}
        >
          <MinusCircle />
        </IconButton>
      ) : (
        <span className="size-9" />
      )}
      <ContentInput value={content} invalid={!!error && !content.trim()} onChange={setContent} />
      <span />
      {error && (
        <p role="alert" className="col-span-2 font-secondary-body text-status-danger-content">
          {error}
        </p>
      )}
    </div>
  );
}

/** A public shortcut as members see it: read-only, hideable for themselves. */
function SharedShortcut({ shortcut }: { shortcut: Shortcut }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const notify = useActionNotifications();
  const [pending, setPending] = useState(false);
  return (
    <div
      className={cn(
        "grid grid-cols-[minmax(0,1fr)_auto] gap-x-1 gap-y-1.5",
        shortcut.hidden && "opacity-60",
      )}
    >
      <NameInput value={shortcut.name} readOnly />
      <IconButton
        type="button"
        prominence="tertiary"
        disabled={pending}
        aria-label={
          shortcut.hidden
            ? ui("Hiện lại {{v1}}", { v1: shortcut.name })
            : ui("Ẩn {{v1}} cho riêng tôi", { v1: shortcut.name })
        }
        title={shortcut.hidden ? ui("Hiện lại") : ui("Ẩn cho riêng tôi")}
        onClick={async () => {
          setPending(true);
          try {
            await hideChatPromptShortcut({
              path: { shortcutId: shortcut.id },
              body: { hidden: !shortcut.hidden },
              headers: sameOriginMutationHeaders,
              signal: AbortSignal.timeout(30000),
              throwOnError: true,
            });
            await cache.invalidateQueries({ queryKey: shortcutsKey });
          } catch (cause) {
            notify({ title: chatActionError(cause), tone: "error" });
            setPending(false);
          }
        }}
      >
        {shortcut.hidden ? <Eye /> : <EyeOff />}
      </IconButton>
      <ContentInput value={shortcut.content} readOnly />
      <span className="font-secondary-body text-content-muted">
        {shortcut.hidden ? ui("Đã ẩn") : null}
      </span>
    </div>
  );
}
