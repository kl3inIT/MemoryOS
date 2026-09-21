import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Clock, Lock, Mic, MonitorSpeaker, Users } from "lucide-react";
import { AppShell } from "@/components/app-shell/app-shell";
import { Button } from "@/components/ui/button";
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { Skeleton } from "@/components/ui/skeleton";
import { StatusBadge } from "@/components/ui/status-badge";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { i18n } from "@/i18n";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import {
  formatClock,
  formatWhen,
  loadMeetings,
  meetingsKey,
  type MeetingSummary,
} from "./meetings-api";
import { useActiveMeeting } from "./meeting-session";
import { NewMeetingDialog } from "./new-meeting-dialog";

type Group = { label: "today" | "week" | "earlier"; items: MeetingSummary[] };

/** Groups newest-first meetings into today, the last seven days and earlier. */
function groupMeetings(items: readonly MeetingSummary[], now = new Date()): Group[] {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const week = today - 6 * 24 * 60 * 60 * 1000;
  const groups: Group[] = [];
  for (const item of items) {
    const time = Date.parse(item.createdAt);
    const label = time >= today ? "today" : time >= week ? "week" : "earlier";
    const last = groups.at(-1);
    if (last?.label === label) last.items.push(item);
    else groups.push({ label, items: [item] });
  }
  return groups;
}

export function MeetingsPage() {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [creating, setCreating] = useState(false);
  const live = useActiveMeeting();
  const meetings = useQuery({
    queryKey: [...meetingsKey, actorId, authorizationVersion],
    queryFn: ({ signal }) => loadMeetings(signal),
  });
  const groupTitle = { today: ui("Hôm nay"), week: ui("7 ngày qua"), earlier: ui("Trước đó") };

  return (
    <AppShell pageTitle={ui("Cuộc họp")}>
      <SettingsLayout wide>
        <PageHeader
          title={ui("Cuộc họp")}
          description={ui(
            "Ghi âm cuộc họp không cần bot, xem transcript và ghi chú. Chỉ bạn xem được.",
          )}
          actions={
            <Button onClick={() => setCreating(true)} disabled={!!live}>
              <Mic aria-hidden="true" />
              {ui("Ghi cuộc họp mới")}
            </Button>
          }
        />
        {meetings.isPending ? (
          <div className="grid gap-2" aria-busy="true">
            {[0, 1, 2].map((row) => (
              <Skeleton key={row} className="h-16 rounded-xl" />
            ))}
          </div>
        ) : meetings.isError ? (
          <p role="alert" className="text-sm text-status-danger-content">
            {problemMessage(presentProblem(meetings.error, "initialLoad").message)}
          </p>
        ) : meetings.data.length === 0 ? (
          <Empty>
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <Mic />
              </EmptyMedia>
              <EmptyTitle>{ui("Chưa có cuộc họp nào")}</EmptyTitle>
              <EmptyDescription>
                {ui(
                  "Bấm “Ghi cuộc họp mới” khi cuộc họp bắt đầu. Transcript hiện ngay trong lúc họp.",
                )}
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        ) : (
          <div className="grid gap-6">
            {groupMeetings(meetings.data).map((group) => (
              <section
                key={group.label}
                aria-label={groupTitle[group.label]}
                className="grid gap-2"
              >
                <h2 className="font-main-ui-action text-content-muted">
                  {groupTitle[group.label]}
                </h2>
                <ul className="divide-y divide-border-subtle overflow-hidden rounded-xl border border-border-subtle bg-surface-raised">
                  {group.items.map((meeting) => (
                    <li key={meeting.id}>
                      <Link
                        to="/meetings/$meetingId"
                        params={{ meetingId: meeting.id }}
                        className="flex flex-wrap items-center gap-x-4 gap-y-1 px-4 py-3 hover:bg-surface-base focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
                      >
                        {meeting.kind === "ONLINE" ? (
                          <MonitorSpeaker
                            className="size-4 shrink-0 text-content-muted"
                            aria-hidden="true"
                          />
                        ) : (
                          <Mic className="size-4 shrink-0 text-content-muted" aria-hidden="true" />
                        )}
                        <span className="min-w-0 flex-1">
                          <span className="block truncate font-main-ui-action text-content-primary">
                            {meeting.title}
                          </span>
                          <span className="flex flex-wrap gap-3 text-xs text-content-muted tabular-nums">
                            <span>{formatWhen(meeting.createdAt, i18n.language)}</span>
                            <span className="inline-flex items-center gap-1">
                              <Clock className="size-3" aria-hidden="true" />
                              {formatClock(meeting.durationMs)}
                            </span>
                            {meeting.participants > 0 && (
                              <span className="inline-flex items-center gap-1">
                                <Users className="size-3" aria-hidden="true" />
                                {ui("{{count}} người", { count: meeting.participants })}
                              </span>
                            )}
                          </span>
                        </span>
                        {meeting.status === "RECORDING" ? (
                          <StatusBadge tone="danger">
                            {live?.meetingId === meeting.id ? ui("Đang ghi") : ui("Chưa kết thúc")}
                          </StatusBadge>
                        ) : (
                          <StatusBadge tone="neutral">{ui("Đã kết thúc")}</StatusBadge>
                        )}
                      </Link>
                    </li>
                  ))}
                </ul>
              </section>
            ))}
          </div>
        )}
        <p className="flex items-center gap-2 text-xs text-content-muted">
          <Lock className="size-3.5" aria-hidden="true" />
          {ui("Chỉ bạn xem được các cuộc họp này. MemoryOS không lưu âm thanh, chỉ lưu văn bản.")}
        </p>
      </SettingsLayout>
      <NewMeetingDialog open={creating} onOpenChange={setCreating} />
    </AppShell>
  );
}
