import type { ReactNode } from "react";
import { useNavigate, useParams } from "@tanstack/react-router";
import { BotMessageSquare } from "lucide-react";
import { BrandLoader } from "@/components/brand-loader";
import { DetailHeader } from "@/components/composites/detail-header";
import { EmptyState } from "@/components/composites/empty-state";
import { SectionHeader } from "@/components/composites/section-header";
import { SettingsLayout } from "@/components/composites/settings-layout";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { formatUiMoment } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ChannelResponse } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { ChannelMark, ChannelMenu, ChannelSwitch } from "./channel-actions";
import { type ChannelAdmin, useChannelAdmin, useChannels } from "./channel-admin";
import { ChannelAssistantsSection } from "./channel-assistants-section";
import { channelFailure, channelLine, channelState } from "./channel-status";

/**
 * One bot's page: its name, line and switch at the top, then its facts, which assistant answers where and the scopes
 * Lark still refuses.
 */
export function ChannelDetailPage() {
  const ui = useAppTranslation();
  const navigate = useNavigate();
  const { channelId } = useParams({ from: "/_authenticated/admin/channels/$channelId" });
  const admin = useChannelAdmin({ onRemoved: () => void navigate({ to: "/admin/channels" }) });
  const list = useChannels();
  const channel = list.data?.find((candidate) => candidate.id === channelId);

  return (
    <SettingsLayout wide>
      {list.isPending ? (
        <div role="status" className="flex justify-center px-6 py-20">
          <BrandLoader label={ui("Đang tải bot")} />
        </div>
      ) : !channel ? (
        <EmptyState
          role="alert"
          icon={<BotMessageSquare />}
          title={ui("Không tìm thấy bot này.")}
          action={
            <Button size="sm" prominence="secondary" onClick={() => void list.refetch()}>
              {ui("Thử lại")}
            </Button>
          }
        />
      ) : (
        <ChannelDetail key={channel.id} channel={channel} admin={admin} />
      )}
      {admin.dialogs}
    </SettingsLayout>
  );
}

function ChannelDetail({ channel, admin }: { channel: ChannelResponse; admin: ChannelAdmin }) {
  const ui = useAppTranslation();
  const { line, tone } = channelLine(channel);
  const when = (value?: string) => (value ? formatUiMoment(value) : ui("Chưa có"));
  const zalo = channel.provider === "ZALO_BOT";
  const facts: [string, ReactNode][] = [
    [ui("Trạng thái"), ui(channelState(channel.status))],
    [zalo ? ui("Bot trên Zalo") : ui("Bot trên Lark"), channel.botName ?? ui("Chưa rõ")],
    ...(zalo
      ? ([
          [ui("Bot ID"), channel.appId],
          [
            ui("Cách nhận tin"),
            <TransportSelect key="transport" channel={channel} admin={admin} />,
          ],
        ] as [string, ReactNode][])
      : ([
          [ui("Khu vực"), channel.region === "FEISHU" ? ui("Feishu") : ui("Lark")],
          [ui("App ID"), channel.appId],
        ] as [string, ReactNode][])),
    [ui("Ngôn ngữ thông báo"), channel.noticeLanguage === "en" ? ui("English") : ui("Tiếng Việt")],
    [ui("Kết nối từ"), when(channel.connectedAt)],
    [ui("Tin nhắn gần nhất"), when(channel.lastMessageAt)],
    ...(channel.failureCode
      ? ([
          [ui("Lỗi gần nhất"), `${ui(channelFailure(channel) ?? "")} · ${when(channel.failedAt)}`],
        ] as [string, ReactNode][])
      : []),
  ];

  return (
    <>
      <DetailHeader
        parent={{ label: ui("Kênh chat"), to: "/admin/channels" }}
        icon={<ChannelMark provider={channel.provider} />}
        iconSize="lg"
        title={channel.name}
        description={
          <span
            className={cn(
              tone === "warning" && "text-status-warning-content",
              tone === "danger" && "text-status-danger-content",
            )}
          >
            {ui(line, { count: channel.missingScopes.length })}
          </span>
        }
        actions={
          <div className="flex items-center gap-3">
            <ChannelSwitch channel={channel} admin={admin} />
            <ChannelMenu channel={channel} admin={admin} />
          </div>
        }
      />

      {admin.changeError ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{ui(admin.changeError)}</AlertDescription>
        </Alert>
      ) : null}

      <section className="flex flex-col gap-3">
        <SectionHeader level="group" title={ui("Thông tin")} />
        <dl className="divide-y divide-border-subtle rounded-xl border border-border-subtle font-secondary-body">
          {facts.map(([label, value]) => (
            <div key={label} className="grid grid-cols-3 items-center gap-4 px-4 py-3">
              <dt className="text-content-muted">{label}</dt>
              <dd className="col-span-2 wrap-anywhere text-content-primary">{value}</dd>
            </div>
          ))}
        </dl>
      </section>

      <ChannelAssistantsSection channel={channel} />

      {channel.missingScopes.length > 0 ? (
        <section className="flex flex-col gap-3">
          <SectionHeader level="group" title={ui("Quyền Lark còn thiếu")} />
          <ul className="flex flex-col gap-1 rounded-xl border border-border-subtle px-4 py-3">
            {channel.missingScopes.map((scope) => (
              <li key={scope}>
                <code className="font-mono text-xs break-all text-content-primary">{scope}</code>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </>
  );
}

/** How a Zalo bot receives; saved on change, as the switch is. */
function TransportSelect({ channel, admin }: { channel: ChannelResponse; admin: ChannelAdmin }) {
  const ui = useAppTranslation();
  return (
    <Select
      value={channel.transport ?? "WEBHOOK"}
      disabled={admin.choosingTransport(channel)}
      onValueChange={(next) =>
        admin.setTransport(channel, next === "POLLING" ? "POLLING" : "WEBHOOK")
      }
    >
      <SelectTrigger size="sm" className="w-40" aria-label={ui("Cách nhận tin")}>
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value="WEBHOOK">{ui("Webhook")}</SelectItem>
        <SelectItem value="POLLING">{ui("Polling")}</SelectItem>
      </SelectContent>
    </Select>
  );
}
