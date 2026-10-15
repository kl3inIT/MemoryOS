import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  deleteChannelMutation,
  listChannelsOptions,
  listChannelsQueryKey,
  updateChannelEnabledMutation,
  updateChannelTransportMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChannelResponse } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { ChannelDialog } from "./channel-dialog";
import { channelError } from "./channel-errors";
import { settling } from "./channel-status";

/** How often a bot switched on but not connected yet is read again. */
const SETTLING_POLL_MS = 5_000;

type Transport = NonNullable<ChannelResponse["transport"]>;

/** The Tenant's bots, read again while one is settling. */
export function useChannels() {
  return useQuery({
    ...listChannelsOptions(),
    refetchInterval: (query) => (settling(query.state.data) ? SETTLING_POLL_MS : false),
  });
}

/**
 * The administrator's actions on bots, shared by the list and a bot's page: add, switch on or off, choose how a Zalo
 * bot receives, replace the secret, delete. `dialogs` renders whichever of them is open.
 */
export function useChannelAdmin({ onRemoved }: { onRemoved?: () => void } = {}) {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const cache = useQueryClient();
  const trigger = useRef<HTMLElement | null>(null);
  const [dialog, setDialog] = useState<{
    open: boolean;
    session: number;
    replacing: ChannelResponse | null;
  }>({
    open: false,
    session: 0,
    replacing: null,
  });
  const [removing, setRemoving] = useState<ChannelResponse | null>(null);
  const [changed, setChanged] = useState<ChannelResponse | null>(null);
  const reread = () => cache.invalidateQueries({ queryKey: listChannelsQueryKey() });
  const toggle = useMutation({ ...updateChannelEnabledMutation(), onSettled: reread });
  const transport = useMutation({ ...updateChannelTransportMutation(), onSettled: reread });
  const remove = useMutation({
    ...deleteChannelMutation(),
    onSuccess: async () => {
      setRemoving(null);
      onRemoved?.();
      await reread();
    },
  });
  const message = (failure: unknown) =>
    channelError(failure, changed?.provider) ?? problem(presentProblem(failure, "mutation").message);
  const failure = toggle.error ?? transport.error;
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
          description={
            removing.provider === "ZALO_BOT"
              ? ui(
                  "Bot ngừng trả lời trên Zalo, các liên kết tài khoản bị xóa và tài khoản của bot bị vô hiệu hóa; các hội thoại cũ vẫn giữ tên bot. Không hoàn tác được.",
                )
              : ui(
                  "Bot ngừng trả lời trên Lark và tài khoản của bot bị vô hiệu hóa; các hội thoại cũ vẫn giữ tên bot. Không hoàn tác được.",
                )
          }
          confirmLabel={ui("Xóa")}
          pendingLabel={ui("Đang xóa")}
          confirmTone="danger"
          onConfirm={async () => {
            await remove.mutateAsync({
              path: { channelId: removing.id },
              headers: { "If-Match": `"${removing.revision}"` },
            });
          }}
          errorMessage={(failure) =>
            channelError(failure, removing.provider) ?? presentProblem(failure, "mutation").message
          }
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
    setEnabled: (channel: ChannelResponse, enabled: boolean) => {
      setChanged(channel);
      transport.reset();
      toggle.mutate({
        path: { channelId: channel.id },
        headers: { "If-Match": `"${channel.revision}"` },
        body: { enabled },
      });
    },
    switching: (channel: ChannelResponse) =>
      toggle.isPending && toggle.variables?.path.channelId === channel.id,
    /** How a Zalo bot receives; a choice saves on change, as the switch does. */
    setTransport: (channel: ChannelResponse, next: Transport) => {
      setChanged(channel);
      toggle.reset();
      transport.mutate({
        path: { channelId: channel.id },
        headers: { "If-Match": `"${channel.revision}"` },
        body: { transport: next },
      });
    },
    choosingTransport: (channel: ChannelResponse) =>
      transport.isPending && transport.variables?.path.channelId === channel.id,
    changeError: failure ? message(failure) : undefined,
    dialogs,
  };
}

export type ChannelAdmin = ReturnType<typeof useChannelAdmin>;
