import { useId } from "react";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { BotMessageSquare, ChevronRight } from "lucide-react";
import { EmptyState } from "@/components/composites/empty-state";
import { SectionHeader } from "@/components/composites/section-header";
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
 * Kênh: where this assistant answers (MEM-273), in two lists as the bot's page lays out its group chats: the bots it
 * is the default of, and the group chats given it as their own. Which assistant answers where is set on each bot's
 * page, so whoever manages bots follows a row there.
 */
export function AgentChannelsPanel({ agent }: { agent: Persona }) {
  const ui = useAppTranslation();
  const manages = useGlobalCapability("CHANNELS_MANAGE");
  const botsHeading = useId();
  const groupsHeading = useId();
  const bound = useQuery(listAssistantChannelsOptions({ path: { personaId: agent.id } }));
  const zalo = (bot: AssistantChannelResponse) => bot.provider === "ZALO_BOT";
  const bots: Place[] = (bound.data ?? [])
    .filter((bot) => bot.byDefault)
    .map((bot) => ({
      key: bot.id,
      bot,
      title: bot.name,
      line: [
        zalo(bot) ? ui("Bot Zalo") : ui("Bot Lark"),
        ui("chat riêng và nhóm chưa chọn trợ lý"),
        bot.enabled ? null : ui("Đang tắt"),
      ]
        .filter(Boolean)
        .join(" · "),
    }));
  const groups: Place[] = (bound.data ?? []).flatMap((bot) =>
    bot.groups.map((group) => {
      const kind = zalo(bot) ? ui("Nhóm Zalo") : ui("Nhóm Lark");
      return {
        key: `${bot.id}:${group.id}`,
        bot,
        title: group.label || group.name || `${kind} ${group.id.slice(-4)}`,
        line: [kind, ui("qua bot “{{v1}}”", { v1: bot.name })].join(" · "),
      };
    }),
  );

  const list = (places: Place[]) => (
    <ul className="divide-y divide-border-subtle overflow-hidden rounded-xl border border-border-subtle">
      {places.map((place) => {
        const body = (
          <>
            <ChannelMark provider={place.bot.provider} />
            <span className="flex min-w-0 flex-1 flex-col">
              <span className="truncate font-main-ui-action text-content-primary">
                {place.title}
              </span>
              <span className="line-clamp-2 font-secondary-body text-content-muted">
                {place.line}
              </span>
            </span>
          </>
        );
        return (
          <li key={place.key}>
            {manages ? (
              <Link
                to="/admin/channels/$channelId"
                params={{ channelId: place.bot.id }}
                className="flex min-w-0 items-center gap-3 px-4 py-3 outline-none hover:bg-surface-subtle focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:ring-inset"
              >
                {body}
                <ChevronRight aria-hidden="true" className="size-4 shrink-0 text-content-faint" />
              </Link>
            ) : (
              <span className="flex min-w-0 items-center gap-3 px-4 py-3">{body}</span>
            )}
          </li>
        );
      })}
    </ul>
  );

  return (
    <div className="flex flex-col gap-6">
      {bound.isError && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{actionErrorText(bound.error)}</AlertDescription>
        </Alert>
      )}
      {bound.data && bots.length === 0 && groups.length === 0 && (
        <EmptyState
          icon={<BotMessageSquare />}
          title={ui("Chưa có bot nào trả lời bằng trợ lý này.")}
        />
      )}
      {bots.length > 0 && (
        <section aria-labelledby={botsHeading} className="flex flex-col gap-3">
          <SectionHeader level="group" id={botsHeading} title={ui("Mặc định của bot")} />
          {list(bots)}
        </section>
      )}
      {groups.length > 0 && (
        <section aria-labelledby={groupsHeading} className="flex flex-col gap-3">
          <SectionHeader level="group" id={groupsHeading} title={ui("Nhóm chat")} />
          {list(groups)}
        </section>
      )}
    </div>
  );
}
