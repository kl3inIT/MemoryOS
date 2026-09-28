import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMemo, useState, type ReactNode } from "react";
import {
  ComposerPrimitive,
  useAui,
  type Unstable_TriggerItem,
  type Unstable_TriggerMatcher,
} from "@assistant-ui/react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Plus } from "lucide-react";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Alert, AlertAction, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { Switch } from "@/components/ui/switch";
import { actionErrorText } from "@/lib/action-errors";
import {
  listPublicChatPromptShortcutsOptions,
  setChatPromptShortcutPreferencesMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { SharedShortcut, ShortcutFields, type ShortcutScope } from "./prompt-shortcut-fields";
import {
  invalidateShortcuts,
  matchingShortcuts,
  shortcutOf,
  useComposerShortcuts,
  useShortcutPreferences,
  useShortcuts,
  type Shortcut,
} from "./prompt-shortcuts-api";

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
      to="/settings/chat"
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
        matchingShortcuts(items, query).map((shortcut) => ({
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
      <Separator className="my-1" />
      <CreateShortcutLink />
    </ComposerPrimitive.Unstable_TriggerPopover>
  );
}

/** Personal settings, laid out like Onyx: inline rows saved on blur, public rows read-only and hideable. */
export function PersonalPromptShortcuts() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const notify = useActionNotifications();
  const savePreferences = useMutation({
    ...setChatPromptShortcutPreferencesMutation(),
    onSuccess: () => invalidateShortcuts(cache),
    onError: (cause) => notify({ title: actionErrorText(cause), tone: "error" }),
  });
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
              onCheckedChange={(enabled) => savePreferences.mutate({ body: { enabled } })}
            />
          }
        />
      </SettingRows>
      {shortcuts.isError && (
        <Alert variant="destructive">
          <AlertDescription>{ui("Không tải được lệnh tắt.")}</AlertDescription>
          <AlertAction>
            <Button size="sm" prominence="tertiary" onClick={() => void shortcuts.refetch()}>
              {ui("Tải lại")}
            </Button>
          </AlertAction>
        </Alert>
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
  const shortcuts = useQuery({
    ...listPublicChatPromptShortcutsOptions(),
    select: (views) => views.map(shortcutOf),
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
        <Alert variant="destructive">
          <AlertDescription>{ui("Không tải được lệnh tắt.")}</AlertDescription>
        </Alert>
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

function EditableShortcuts({ shortcuts, scope }: { shortcuts: Shortcut[]; scope: ShortcutScope }) {
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
