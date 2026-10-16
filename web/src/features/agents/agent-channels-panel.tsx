import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { BotMessageSquare, Plus } from "lucide-react";
import { AppGrid, AppRow } from "@/components/composites/app-row";
import { EmptyState } from "@/components/composites/empty-state";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from "@/components/ui/command";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import type { Persona } from "@/features/chat/chat-personas-api";
import { ChannelMark } from "@/features/channels/channel-actions";
import { useGlobalCapability } from "@/features/identity/application-session-context";
import { actionErrorText } from "@/lib/action-errors";
import {
  bindChannelAssistantMutation,
  listAssistantChannelsOptions,
  listAssistantChannelsQueryKey,
  listChannelsOptions,
  listChannelsQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { AssistantChannelResponse, ChannelResponse } from "@/lib/hey-api/types.gen";
import { can } from "@/lib/resource-permissions";

type Bot = Pick<AssistantChannelResponse, "id" | "provider" | "name" | "botName">;

/**
 * Kênh: the bots that answer with this assistant (MEM-273). Whoever manages bots and may edit the assistant adds a bot,
 * moving it here after a confirmation when it answers with another, or removes one; everyone else reads the list.
 * Bots themselves are created on the administration's Kênh chat page. Each change saves at once.
 */
export function AgentChannelsPanel({ agent }: { agent: Persona }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const manages = useGlobalCapability("CHANNELS_MANAGE");
  const binds = manages && can(agent, "edit");
  const bound = useQuery(listAssistantChannelsOptions({ path: { personaId: agent.id } }));
  const all = useQuery({ ...listChannelsOptions(), enabled: binds });
  const [moving, setMoving] = useState<ChannelResponse | null>(null);
  const bind = useMutation({
    ...bindChannelAssistantMutation(),
    onSettled: () =>
      Promise.all([
        cache.invalidateQueries({
          queryKey: listAssistantChannelsQueryKey({ path: { personaId: agent.id } }),
        }),
        cache.invalidateQueries({ queryKey: listChannelsQueryKey() }),
      ]),
  });
  const choose = (channelId: string, personaId: string | null) =>
    bind.mutateAsync({ path: { channelId }, body: { personaId } });
  const rows = bound.data ?? [];
  const others = (all.data ?? []).filter((channel) => !rows.some((row) => row.id === channel.id));

  const add = binds && (
    <AddBot
      bots={others}
      disabled={bind.isPending || all.isPending}
      onPick={(channel) => {
        bind.reset();
        if (channel.assistant) setMoving(channel);
        else void choose(channel.id, agent.id).catch(() => {});
      }}
    />
  );

  return (
    <div className="flex flex-col gap-4">
      {bind.isError && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{actionErrorText(bind.error)}</AlertDescription>
        </Alert>
      )}
      {bound.isError && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{actionErrorText(bound.error)}</AlertDescription>
        </Alert>
      )}
      {bound.data && rows.length === 0 && (
        <EmptyState
          icon={<BotMessageSquare />}
          title={ui("Chưa có bot nào trả lời bằng trợ lý này.")}
        />
      )}
      {rows.length > 0 && (
        <AppGrid>
          {rows.map((bot) => (
            <AppRow
              key={bot.id}
              mark={<ChannelMark provider={bot.provider} />}
              title={bot.name}
              line={line(ui, bot, bot.enabled ? null : ui("Đang tắt"))}
              action={
                binds && (
                  <Button
                    type="button"
                    size="sm"
                    prominence="tertiary"
                    disabled={bind.isPending}
                    aria-label={ui("Gỡ {{v1}}", { v1: bot.name })}
                    onClick={() => {
                      bind.reset();
                      void choose(bot.id, null).catch(() => {});
                    }}
                  >
                    {ui("Gỡ")}
                  </Button>
                )
              }
            />
          ))}
        </AppGrid>
      )}
      {add && <div>{add}</div>}

      <ConfirmDialog
        open={moving !== null}
        onOpenChange={(open) => {
          if (!open) setMoving(null);
        }}
        title={ui("Chuyển bot sang trợ lý này?")}
        description={
          moving?.assistant
            ? ui(
                "{{bot}} đang trả lời bằng {{from}}. Sau khi chuyển, bot sẽ trả lời bằng {{to}}.",
                {
                  bot: moving.name,
                  from: moving.assistant.name,
                  to: agent.name,
                },
              )
            : ""
        }
        confirmLabel={ui("Chuyển")}
        pendingLabel={ui("Đang chuyển…")}
        onConfirm={async () => {
          if (!moving) return;
          await choose(moving.id, agent.id);
          setMoving(null);
        }}
      />
    </div>
  );
}

function line(ui: ReturnType<typeof useAppTranslation>, bot: Bot, extra: string | null) {
  const platform = bot.provider === "ZALO_BOT" ? ui("Zalo") : ui("Lark");
  return [platform, bot.botName, extra].filter(Boolean).join(" · ");
}

function AddBot({
  bots,
  disabled,
  onPick,
}: {
  bots: ChannelResponse[];
  disabled: boolean;
  onPick: (bot: ChannelResponse) => void;
}) {
  const ui = useAppTranslation();
  const [open, setOpen] = useState(false);
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button type="button" size="sm" prominence="secondary" disabled={disabled}>
          <Plus data-icon="inline-start" aria-hidden="true" />
          {ui("Thêm kênh")}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-80 max-w-[calc(100vw-2rem)] p-0">
        <Command>
          <CommandInput placeholder={ui("Tìm bot…")} />
          <CommandList>
            <CommandEmpty>{ui("Không còn bot nào để thêm.")}</CommandEmpty>
            <CommandGroup>
              {bots.map((bot) => (
                <CommandItem
                  key={bot.id}
                  value={`${bot.name} ${bot.id}`}
                  onSelect={() => {
                    setOpen(false);
                    onPick(bot);
                  }}
                >
                  <span className="flex min-w-0 flex-1 flex-col">
                    <span className="truncate">{bot.name}</span>
                    <span className="truncate font-secondary-body text-content-muted">
                      {bot.assistant
                        ? ui("Đang trả lời bằng {{v1}}", { v1: bot.assistant.name })
                        : bot.provider === "ZALO_BOT"
                          ? ui("Zalo")
                          : ui("Lark")}
                    </span>
                  </span>
                </CommandItem>
              ))}
            </CommandGroup>
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  );
}
