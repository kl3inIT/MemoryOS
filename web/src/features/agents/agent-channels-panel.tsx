import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { BotMessageSquare } from "lucide-react";
import { AppGrid, AppRow, appRowLinkClass } from "@/components/composites/app-row";
import { EmptyState } from "@/components/composites/empty-state";
import { Alert, AlertDescription } from "@/components/ui/alert";
import type { Persona } from "@/features/chat/chat-personas-api";
import { ChannelMark } from "@/features/channels/channel-actions";
import { useGlobalCapability } from "@/features/identity/application-session-context";
import { actionErrorText } from "@/lib/action-errors";
import { listAssistantChannelsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { AssistantChannelResponse } from "@/lib/hey-api/types.gen";

type Place = {
  key: string;
  bot: AssistantChannelResponse;
  title: string;
  line: string;
};

/**
 * Kênh: where this assistant answers (MEM-273): the bots it is the default of and the group chats given it as their
 * own. Which assistant answers where is set on each bot's page, so whoever manages bots follows a row there.
 */
export function AgentChannelsPanel({ agent }: { agent: Persona }) {
  const ui = useAppTranslation();
  const manages = useGlobalCapability("CHANNELS_MANAGE");
  const bound = useQuery(listAssistantChannelsOptions({ path: { personaId: agent.id } }));
  const platform = (bot: AssistantChannelResponse) =>
    bot.provider === "ZALO_BOT" ? ui("Zalo") : ui("Lark");
  const groupKind = (bot: AssistantChannelResponse) =>
    bot.provider === "ZALO_BOT" ? ui("Nhóm Zalo") : ui("Nhóm Lark");
  const places: Place[] = (bound.data ?? []).flatMap((bot) => [
    ...(bot.byDefault
      ? [
          {
            key: bot.id,
            bot,
            title: bot.name,
            line: [
              platform(bot),
              ui("Trợ lý mặc định của bot"),
              bot.enabled ? null : ui("Đang tắt"),
            ]
              .filter(Boolean)
              .join(" · "),
          },
        ]
      : []),
    ...bot.groups.map((group) => ({
      key: `${bot.id}:${group.id}`,
      bot,
      title: group.label || group.name || `${groupKind(bot)} ${group.id.slice(-4)}`,
      line: [groupKind(bot), bot.name].join(" · "),
    })),
  ]);

  return (
    <div className="flex flex-col gap-4">
      {bound.isError && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{actionErrorText(bound.error)}</AlertDescription>
        </Alert>
      )}
      {bound.data && places.length === 0 && (
        <EmptyState
          icon={<BotMessageSquare />}
          title={ui("Chưa có bot nào trả lời bằng trợ lý này.")}
        />
      )}
      {places.length > 0 && (
        <AppGrid>
          {places.map((place) => (
            <AppRow
              key={place.key}
              mark={<ChannelMark provider={place.bot.provider} />}
              title={place.title}
              line={place.line}
              link={
                manages
                  ? (body) => (
                      <Link
                        to="/admin/channels/$channelId"
                        params={{ channelId: place.bot.id }}
                        className={appRowLinkClass}
                      >
                        {body}
                      </Link>
                    )
                  : undefined
              }
            />
          ))}
        </AppGrid>
      )}
    </div>
  );
}
