import { useNavigate, useParams } from "@tanstack/react-router";
import { BotMessageSquare } from "lucide-react";
import { BrandLoader } from "@/components/brand-loader";
import { DetailHeader } from "@/components/composites/detail-header";
import { EmptyState } from "@/components/composites/empty-state";
import { SectionHeader } from "@/components/composites/section-header";
import { SettingsLayout } from "@/components/composites/settings-layout";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { formatUiMoment } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ChannelResponse } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { ChannelMenu, ChannelSwitch } from "./channel-actions";
import { type ChannelAdmin, useChannelAdmin, useChannels } from "./channel-admin";
import { channelLine, channelState } from "./channel-status";
import { ChannelMark } from "./channels-page";

/** One bot's page: its name, line and switch at the top, then its facts and the scopes Lark still refuses. */
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
  const facts: [string, string][] = [
    [ui("Trạng thái"), ui(channelState(channel.status))],
    [ui("Bot trên Lark"), channel.botName ?? ui("Chưa rõ")],
    [ui("Khu vực"), channel.region === "FEISHU" ? ui("Feishu") : ui("Lark")],
    [ui("App ID"), channel.appId],
    [ui("Ngôn ngữ thông báo"), channel.noticeLanguage === "en" ? ui("English") : ui("Tiếng Việt")],
    [ui("Kết nối từ"), when(channel.connectedAt)],
    [ui("Tin nhắn gần nhất"), when(channel.lastMessageAt)],
    ...(channel.failureCode
      ? [[ui("Lỗi gần nhất"), `${channel.failureCode} · ${when(channel.failedAt)}`] as [string, string]]
      : []),
  ];

  return (
    <>
      <DetailHeader
        parent={{ label: ui("Kênh chat"), to: "/admin/channels" }}
        icon={<ChannelMark />}
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

      {admin.toggleError ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{ui(admin.toggleError)}</AlertDescription>
        </Alert>
      ) : null}

      <section className="flex flex-col gap-3">
        <SectionHeader level="group" title={ui("Thông tin")} />
        <dl className="divide-y divide-border-subtle rounded-xl border border-border-subtle font-secondary-body">
          {facts.map(([label, value]) => (
            <div key={label} className="grid grid-cols-3 gap-4 px-4 py-3">
              <dt className="text-content-muted">{label}</dt>
              <dd className="col-span-2 break-all text-content-primary">{value}</dd>
            </div>
          ))}
        </dl>
      </section>

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
