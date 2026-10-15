import { Link } from "@tanstack/react-router";
import { BotMessageSquare, Plus } from "lucide-react";
import { AppGrid, AppRow, appRowLinkClass } from "@/components/composites/app-row";
import { EmptyState } from "@/components/composites/empty-state";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ChannelResponse } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { ChannelMark, ChannelSwitch } from "./channel-actions";
import { type ChannelAdmin, useChannelAdmin, useChannels } from "./channel-admin";
import { channelLine } from "./channel-status";

/**
 * The Tenant's chat bots, listed as the MCP servers are: a mark, a name, one line and one action, the switch that
 * saves on change. Each bot's facts and rare actions are on its own page.
 */
export function ChannelsPage() {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const admin = useChannelAdmin();
  const list = useChannels();

  return (
    <SettingsLayout wide>
      <PageHeader
        title={ui("Kênh chat")}
        icon={<BotMessageSquare />}
        actions={
          <Button onClick={admin.add}>
            <Plus data-icon="inline-start" />
            {ui("Thêm bot")}
          </Button>
        }
      />

      {admin.changeError ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{ui(admin.changeError)}</AlertDescription>
        </Alert>
      ) : null}
      {list.isError ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>
            {problem(presentProblem(list.error, "initialLoad").message)}
          </AlertDescription>
        </Alert>
      ) : null}

      {list.data && list.data.length === 0 ? (
        <EmptyState icon={<BotMessageSquare />} title={ui("Chưa có bot nào.")} />
      ) : null}
      {list.data && list.data.length > 0 ? (
        <AppGrid>
          {list.data.map((channel) => (
            <ChannelRow key={channel.id} channel={channel} admin={admin} />
          ))}
        </AppGrid>
      ) : null}

      {admin.dialogs}
    </SettingsLayout>
  );
}

function ChannelRow({ channel, admin }: { channel: ChannelResponse; admin: ChannelAdmin }) {
  const ui = useAppTranslation();
  const { line, tone } = channelLine(channel);
  return (
    <AppRow
      mark={<ChannelMark provider={channel.provider} />}
      title={channel.name}
      line={ui(line, { count: channel.missingScopes.length })}
      tone={tone}
      link={(body) => (
        <Link
          to="/admin/channels/$channelId"
          params={{ channelId: channel.id }}
          className={appRowLinkClass}
        >
          {body}
        </Link>
      )}
      action={<ChannelSwitch channel={channel} admin={admin} />}
    />
  );
}
