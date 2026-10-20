import { useRef, useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { MoreHorizontal, Pencil, Trash2 } from "lucide-react";
import { FormDialog } from "@/components/composites/form-dialog";
import { SectionHeader } from "@/components/composites/section-header";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Field, FieldLabel } from "@/components/ui/field";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectSeparator,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { personasOptions, type Persona } from "@/features/chat/chat-personas-api";
import { formatUiDay, formatUiRelative, uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  allowChannelGroupMutation,
  bindChannelAssistantMutation,
  bindChannelGroupAssistantMutation,
  forgetChannelGroupMutation,
  labelChannelGroupMutation,
  listChannelGroupsOptions,
  listChannelGroupsQueryKey,
  listChannelsQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChannelGroupResponse, ChannelResponse } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { can } from "@/lib/resource-permissions";
import { useProblemMessage } from "@/lib/use-problem-message";
import { ChannelMark } from "./channel-actions";
import { channelError } from "./channel-errors";
import { providerWords } from "./channel-provider";

/** The picker's value for the built-in assistant as the bot's default, which the API writes as no assistant. */
const BUILT_IN = "built-in";
/** The picker's value for a group without an assistant of its own. */
const BY_DEFAULT = "by-default";

/**
 * Which assistant answers where (MEM-273, as Onyx's Slack bot sets one per channel): the bot's default, and the group
 * chats it has answered in, each of which may take an assistant of its own. A group of a platform that cannot tell who
 * is in it (Zalo) answers only once allowed here (MEM-285, as OpenClaw's group allowlist). Every choice saves on change.
 */
export function ChannelAssistantsSection({ channel }: { channel: ChannelResponse }) {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const cache = useQueryClient();
  const channelId = channel.id;
  const personas = useQuery(personasOptions());
  const groups = useQuery(listChannelGroupsOptions({ path: { channelId } }));
  const reread = () =>
    Promise.all([
      cache.invalidateQueries({ queryKey: listChannelGroupsQueryKey({ path: { channelId } }) }),
      cache.invalidateQueries({ queryKey: listChannelsQueryKey() }),
      // Every assistant's Kênh tab: the key's prefix matches each assistant's read of it.
      cache.invalidateQueries({ queryKey: [{ _id: "listAssistantChannels" }] }),
    ]);
  const bindDefault = useMutation({ ...bindChannelAssistantMutation(), onSettled: reread });
  const bindGroup = useMutation({ ...bindChannelGroupAssistantMutation(), onSettled: reread });
  const label = useMutation({ ...labelChannelGroupMutation(), onSettled: reread });
  const forget = useMutation({ ...forgetChannelGroupMutation(), onSettled: reread });
  const allow = useMutation({ ...allowChannelGroupMutation(), onSettled: reread });
  const [renaming, setRenaming] = useState<ChannelGroupResponse | null>(null);
  const [name, setName] = useState("");
  const [forgetting, setForgetting] = useState<ChannelGroupResponse | null>(null);
  const menuTrigger = useRef<HTMLElement | null>(null);

  const message = (failure: unknown) =>
    channelError(failure, channel.provider) ?? problem(presentProblem(failure, "mutation").message);
  const failure = bindDefault.error ?? bindGroup.error ?? allow.error;
  const resetChoices = () => {
    bindDefault.reset();
    bindGroup.reset();
    allow.reset();
  };

  const all = personas.data ?? [];
  const builtIn = all.find((persona) => persona.builtin);
  // The server checks edit rights on every change; the picker offers what it would accept, plus what is chosen now.
  const choices = (current?: string) =>
    all.filter((persona) => !persona.builtin && (can(persona, "edit") || persona.id === current));
  // An assistant the administrator cannot see still names the current choice, so the picker never shows blank.
  const unseen = (chosen?: { id: string; name: string }) =>
    chosen && !all.some((persona) => persona.id === chosen.id) ? (
      <SelectItem value={chosen.id}>{chosen.name}</SelectItem>
    ) : null;
  const groupTitle = (group: ChannelGroupResponse) =>
    group.label ||
    group.name ||
    `${ui(providerWords(channel.provider).group)} ${group.id.slice(-4)}`;
  const busy = bindDefault.isPending || bindGroup.isPending || allow.isPending;

  return (
    <section className="flex flex-col gap-3">
      <SectionHeader level="group" title={ui("Trợ lý")} />
      {failure ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{message(failure)}</AlertDescription>
        </Alert>
      ) : null}
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border-subtle px-4 py-3 font-secondary-body">
        <span className="text-content-muted">{ui("Trợ lý mặc định")}</span>
        <AssistantSelect
          label={ui("Trợ lý mặc định")}
          value={channel.assistant?.id ?? BUILT_IN}
          disabled={busy || personas.isPending}
          onChange={(value) => {
            resetChoices();
            bindDefault.mutate({
              path: { channelId },
              body: { personaId: value === BUILT_IN ? null : value },
            });
          }}
        >
          <SelectItem value={BUILT_IN}>{builtIn?.name ?? ui("Trợ lý mặc định")}</SelectItem>
          <PersonaItems personas={choices(channel.assistant?.id)} />
          {unseen(channel.assistant)}
        </AssistantSelect>
      </div>

      <SectionHeader level="group" title={ui("Theo nhóm chat")} />
      {groups.isError ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{message(groups.error)}</AlertDescription>
        </Alert>
      ) : null}
      {groups.data && groups.data.length > 0 ? (
        <ul className="divide-y divide-border-subtle rounded-xl border border-border-subtle">
          {groups.data.map((group) => {
            const title = groupTitle(group);
            const current = group.assistant?.id;
            return (
              <li key={group.id} className="flex flex-wrap items-center gap-x-3 gap-y-2 px-4 py-3">
                <span className="flex min-w-0 flex-1 basis-56 items-center gap-3">
                  <ChannelMark provider={channel.provider} />
                  <span className="flex min-w-0 flex-col">
                    <span className="truncate font-main-ui-action text-content-primary">
                      {title}
                    </span>
                    <span className="truncate font-secondary-body text-content-muted">
                      {[
                        ui(providerWords(channel.provider).group),
                        group.permission === "WAITING"
                          ? ui("chờ cho phép")
                          : group.lastMessageAt
                            ? ui("hỏi {{v1}}", {
                                // Mid-sentence, so "Hôm qua" reads "hôm qua".
                                v1: (
                                  formatUiRelative(group.lastMessageAt) ??
                                  formatUiDay(group.lastMessageAt)
                                ).toLocaleLowerCase(uiLocale()),
                              })
                            : ui("chưa có ai hỏi"),
                      ].join(" · ")}
                    </span>
                  </span>
                </span>
                <span className="ml-auto flex items-center gap-2">
                  {group.permission !== "NOT_NEEDED" ? (
                    <label className="flex items-center gap-2 font-secondary-body text-content-muted">
                      <Switch
                        checked={group.permission === "ALLOWED"}
                        disabled={busy}
                        aria-label={ui("Cho phép trả lời trong {{v1}}", { v1: title })}
                        onCheckedChange={(allowed) => {
                          resetChoices();
                          allow.mutate({
                            path: { channelId, groupId: group.id },
                            body: { allowed },
                          });
                        }}
                      />
                      {ui("Cho phép trả lời")}
                    </label>
                  ) : null}
                  <AssistantSelect
                    label={ui("Trợ lý của {{v1}}", { v1: title })}
                    value={current ?? BY_DEFAULT}
                    disabled={busy || personas.isPending}
                    onChange={(value) => {
                      resetChoices();
                      bindGroup.mutate({
                        path: { channelId, groupId: group.id },
                        body: { personaId: value === BY_DEFAULT ? null : value },
                      });
                    }}
                  >
                    <SelectItem value={BY_DEFAULT}>{ui("Theo mặc định")}</SelectItem>
                    <SelectSeparator />
                    {builtIn ? <SelectItem value={builtIn.id}>{builtIn.name}</SelectItem> : null}
                    <PersonaItems personas={choices(current)} />
                    {unseen(group.assistant)}
                  </AssistantSelect>
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <IconButton
                        size="sm"
                        prominence="internal"
                        aria-label={ui("Thao tác với {{name}}", { name: title })}
                        onPointerDown={(event) => {
                          menuTrigger.current = event.currentTarget;
                        }}
                      >
                        <MoreHorizontal />
                      </IconButton>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end" className="min-w-40">
                      <DropdownMenuItem
                        onSelect={() => {
                          label.reset();
                          setName(group.label ?? "");
                          setRenaming(group);
                        }}
                      >
                        <Pencil /> {ui("Đổi tên")}
                      </DropdownMenuItem>
                      {/* A Lark bot's groups are Lark's own list: a group leaves it when the bot leaves the group. */}
                      {channel.provider !== "LARK" && (
                        <>
                          <DropdownMenuSeparator />
                          <DropdownMenuItem
                            variant="destructive"
                            onSelect={() => setForgetting(group)}
                          >
                            <Trash2 /> {ui("Quên nhóm")}
                          </DropdownMenuItem>
                        </>
                      )}
                    </DropdownMenuContent>
                  </DropdownMenu>
                </span>
              </li>
            );
          })}
        </ul>
      ) : groups.data ? (
        <p role="status" className="font-secondary-body text-content-muted">
          {ui("Chưa có nhóm chat nào")}
        </p>
      ) : null}

      {renaming ? (
        <FormDialog
          open
          onOpenChange={(open) => !open && setRenaming(null)}
          title={ui("Đổi tên nhóm chat")}
          restoreFocusRef={menuTrigger}
          onSubmit={async () => {
            await label.mutateAsync({
              path: { channelId, groupId: renaming.id },
              body: { label: name.trim() || null },
            });
          }}
        >
          <Field>
            <FieldLabel htmlFor="channel-group-label">{ui("Tên nhóm")}</FieldLabel>
            <Input
              id="channel-group-label"
              maxLength={100}
              placeholder={renaming.name ?? undefined}
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
          </Field>
        </FormDialog>
      ) : null}
      <ConfirmDialog
        open={forgetting !== null}
        onOpenChange={(open) => !open && setForgetting(null)}
        restoreFocusRef={menuTrigger}
        title={ui("Quên nhóm {{name}}?", { name: forgetting ? groupTitle(forgetting) : "" })}
        description={ui(
          "Nhóm rời khỏi danh sách và trả lời bằng trợ lý mặc định. Nhóm hiện lại khi bot trả lời ở đó lần sau.",
        )}
        confirmLabel={ui("Quên nhóm")}
        pendingLabel={ui("Đang lưu…")}
        confirmTone="danger"
        errorMessage={(error) => message(error)}
        onConfirm={async () => {
          if (!forgetting) return;
          await forget.mutateAsync({ path: { channelId, groupId: forgetting.id } });
          setForgetting(null);
        }}
      />
    </section>
  );
}

function AssistantSelect({
  label,
  value,
  disabled,
  onChange,
  children,
}: {
  label: string;
  value: string;
  disabled: boolean;
  onChange: (value: string) => void;
  children: ReactNode;
}) {
  return (
    <Select value={value} disabled={disabled} onValueChange={onChange}>
      <SelectTrigger size="sm" className="w-52" aria-label={label}>
        <SelectValue />
      </SelectTrigger>
      <SelectContent>{children}</SelectContent>
    </Select>
  );
}

function PersonaItems({ personas }: { personas: Persona[] }) {
  return personas.map((persona) => (
    <SelectItem key={persona.id} value={persona.id}>
      {persona.name}
    </SelectItem>
  ));
}
