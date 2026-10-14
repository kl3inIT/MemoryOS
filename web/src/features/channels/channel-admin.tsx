import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { MoreHorizontal, KeyRound, Trash2 } from "lucide-react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { IconButton } from "@/components/ui/icon-button";
import { Switch } from "@/components/ui/switch";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  deleteChannelMutation,
  listChannelsOptions,
  listChannelsQueryKey,
  updateChannelEnabledMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChannelResponse } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { ChannelDialog } from "./channel-dialog";
import { channelError } from "./channel-errors";
import { settling } from "./channel-status";

/** How often a bot switched on but not connected yet is read again. */
const SETTLING_POLL_MS = 5_000;

/** The Tenant's bots, read again while one is settling. */
export function useChannels() {
  return useQuery({
    ...listChannelsOptions(),
    refetchInterval: (query) => (settling(query.state.data) ? SETTLING_POLL_MS : false),
  });
}

/**
 * The administrator's actions on bots, shared by the list and a bot's page: add, switch on or off, replace the
 * App Secret, delete. `dialogs` renders whichever of them is open.
 */
export function useChannelAdmin({ onRemoved }: { onRemoved?: () => void } = {}) {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const cache = useQueryClient();
  const trigger = useRef<HTMLElement | null>(null);
  const [dialog, setDialog] = useState<{ open: boolean; session: number; replacing: ChannelResponse | null }>({
    open: false,
    session: 0,
    replacing: null,
  });
  const [removing, setRemoving] = useState<ChannelResponse | null>(null);
  const reread = () => cache.invalidateQueries({ queryKey: listChannelsQueryKey() });
  const toggle = useMutation({ ...updateChannelEnabledMutation(), onSettled: reread });
  const remove = useMutation({
    ...deleteChannelMutation(),
    onSuccess: async () => {
      setRemoving(null);
      onRemoved?.();
      await reread();
    },
  });
  const message = (failure: unknown) =>
    channelError(failure) ?? problem(presentProblem(failure, "mutation").message);
  const open = (replacing: ChannelResponse | null) => {
    trigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setDialog((current) => ({ open: true, session: current.session + 1, replacing }));
  };

  const dialogs = (
    <>
      <ChannelDialog
        open={dialog.open}
        session={dialog.session}
        replacing={dialog.replacing}
        triggerRef={trigger}
        onOpenChange={(next) => setDialog((current) => ({ ...current, open: next }))}
        onSaved={async () => {
          setDialog((current) => ({ ...current, open: false }));
          await reread();
        }}
      />
      {removing ? (
        <ConfirmDialog
          open
          title={ui("Xóa bot {{name}}?", { name: removing.name })}
          description={ui(
            "Bot ngừng trả lời trên Lark và tài khoản của bot bị vô hiệu hóa; các cuộc trò chuyện cũ vẫn giữ tên bot. Không hoàn tác được.",
          )}
          confirmLabel={ui("Xóa")}
          pendingLabel={ui("Đang xóa")}
          confirmTone="danger"
          onConfirm={async () => {
            await remove.mutateAsync({
              path: { channelId: removing.id },
              headers: { "If-Match": `"${removing.revision}"` },
            });
          }}
          errorMessage={(failure) => channelError(failure) ?? presentProblem(failure, "mutation").message}
          onOpenChange={(next) => {
            if (!next) setRemoving(null);
          }}
        />
      ) : null}
    </>
  );

  return {
    add: () => open(null),
    replaceSecret: (channel: ChannelResponse) => open(channel),
    remove: setRemoving,
    /** A switch saves on change; the list is read again whatever the outcome. */
    setEnabled: (channel: ChannelResponse, enabled: boolean) =>
      toggle.mutate({
        path: { channelId: channel.id },
        headers: { "If-Match": `"${channel.revision}"` },
        body: { enabled },
      }),
    switching: (channel: ChannelResponse) => toggle.isPending && toggle.variables?.path.channelId === channel.id,
    toggleError: toggle.error ? message(toggle.error) : undefined,
    dialogs,
  };
}

export type ChannelAdmin = ReturnType<typeof useChannelAdmin>;

/** Switches a bot on or off; saved on change. */
export function ChannelSwitch({ channel, admin }: { channel: ChannelResponse; admin: ChannelAdmin }) {
  const ui = useAppTranslation();
  return (
    <Switch
      checked={channel.enabled}
      disabled={admin.switching(channel)}
      aria-label={ui(channel.enabled ? "Tắt {{name}}" : "Bật {{name}}", { name: channel.name })}
      onCheckedChange={(checked) => admin.setEnabled(channel, checked)}
    />
  );
}

/** Rare actions share one menu. */
export function ChannelMenu({ channel, admin }: { channel: ChannelResponse; admin: ChannelAdmin }) {
  const ui = useAppTranslation();
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <IconButton size="sm" prominence="internal" aria-label={ui("Thao tác với {{name}}", { name: channel.name })}>
          <MoreHorizontal />
        </IconButton>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-44">
        <DropdownMenuGroup>
          <DropdownMenuItem onSelect={() => admin.replaceSecret(channel)}>
            <KeyRound /> {ui("Thay App Secret")}
          </DropdownMenuItem>
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <DropdownMenuGroup>
          <DropdownMenuItem variant="destructive" onSelect={() => admin.remove(channel)}>
            <Trash2 /> {ui("Xóa bot")}
          </DropdownMenuItem>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
