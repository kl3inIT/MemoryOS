import { useDeferredValue, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Clock, FileAudio, Mic, MonitorSpeaker, Search, Users, WifiOff } from "lucide-react";
import { AppShell } from "@/components/app-shell/app-shell";
import { Button } from "@/components/ui/button";
import { BrandLoader } from "@/components/brand-loader";
import { EmptyState } from "@/components/composites/empty-state";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
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
import { UploadRecordingDialog } from "./upload-recording-dialog";

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

/** The meetings a name search, a status and a period leave visible. */
function matchingMeetings(
  meetings: readonly MeetingSummary[],
  query: string,
  status: "all" | "RECORDING" | "ENDED",
  period: "30" | "90" | "all",
  now = Date.now(),
) {
  const since = period === "all" ? 0 : now - Number(period) * 86_400_000;
  return meetings.filter(
    (meeting) =>
      (status === "all" || meeting.status === status) &&
      Date.parse(meeting.createdAt) >= since &&
      (query === "" || meeting.title.toLocaleLowerCase("vi").includes(query)),
  );
}

export function MeetingsPage() {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [creating, setCreating] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState<"all" | "RECORDING" | "ENDED">("all");
  const [period, setPeriod] = useState<"30" | "90" | "all">("30");
  const query = useDeferredValue(search.trim().toLocaleLowerCase("vi"));
  const live = useActiveMeeting();
  const meetings = useQuery({
    queryKey: [...meetingsKey, actorId, authorizationVersion],
    queryFn: ({ signal }) => loadMeetings(signal),
  });
  const groupTitle = { today: ui("Hôm nay"), week: ui("7 ngày qua"), earlier: ui("Trước đó") };
  const all = meetings.data;
  /** The list is small and already owner-private, so it filters in the browser. */
  const shown = useMemo(
    () => (all ? matchingMeetings(all, query, status, period) : []),
    [all, query, status, period],
  );
  const filtered = !!all && all.length > 0 && shown.length === 0;

  return (
    <AppShell pageTitle={ui("Cuộc họp")}>
      <SettingsLayout wide className="gap-6 md:pt-8">
        <PageHeader
          title={ui("Cuộc họp")}
          icon={<Mic />}
          actions={
            <>
              <Button prominence="secondary" onClick={() => setUploading(true)}>
                <FileAudio aria-hidden="true" />
                {ui("Tải file ghi âm")}
              </Button>
              <Button onClick={() => setCreating(true)} disabled={!!live}>
                <Mic aria-hidden="true" />
                {ui("Ghi cuộc họp mới")}
              </Button>
            </>
          }
        />
        {meetings.isPending ? (
          <div
            role="status"
            className="flex justify-center rounded-xl border border-border-subtle px-6 py-20"
          >
            <BrandLoader label={ui("Đang tải cuộc họp")} />
          </div>
        ) : meetings.isError ? (
          <EmptyState
            role="alert"
            icon={<WifiOff />}
            title={ui("Chưa xem được danh sách cuộc họp")}
            detail={problemMessage(presentProblem(meetings.error, "initialLoad").message)}
            action={
              <Button size="sm" prominence="secondary" onClick={() => void meetings.refetch()}>
                {ui("Thử lại")}
              </Button>
            }
          />
        ) : (
          <>
            <div className="flex flex-wrap gap-2">
              <div className="relative min-w-0 flex-1 sm:max-w-80">
                <Search
                  className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-content-muted"
                  aria-hidden="true"
                />
                <Input
                  value={search}
                  className="pl-9"
                  placeholder={ui("Tìm theo tên cuộc họp")}
                  aria-label={ui("Tìm theo tên cuộc họp")}
                  onChange={(event) => setSearch(event.target.value)}
                />
              </div>
              <Select
                value={status}
                aria-label={ui("Trạng thái")}
                className="w-auto"
                onChange={(event) => setStatus(event.target.value as typeof status)}
              >
                <option value="all">{ui("Mọi trạng thái")}</option>
                <option value="RECORDING">{ui("Chưa kết thúc")}</option>
                <option value="ENDED">{ui("Đã kết thúc")}</option>
              </Select>
              <Select
                value={period}
                aria-label={ui("Thời gian")}
                className="w-auto"
                onChange={(event) => setPeriod(event.target.value as typeof period)}
              >
                <option value="30">{ui("30 ngày qua")}</option>
                <option value="90">{ui("90 ngày qua")}</option>
                <option value="all">{ui("Tất cả")}</option>
              </Select>
            </div>
          </>
        )}
        {meetings.isPending || meetings.isError ? null : !all || all.length === 0 ? (
          <EmptyState
            icon={<Mic />}
            title={ui("Chưa có cuộc họp nào")}
            action={
              <Button size="sm" disabled={!!live} onClick={() => setCreating(true)}>
                <Mic aria-hidden="true" />
                {ui("Ghi cuộc họp mới")}
              </Button>
            }
          />
        ) : filtered ? (
          <EmptyState
            icon={<Search />}
            title={ui("Không có cuộc họp nào khớp bộ lọc.")}
            action={
              <Button
                size="sm"
                prominence="secondary"
                onClick={() => {
                  setSearch("");
                  setStatus("all");
                  setPeriod("all");
                }}
              >
                {ui("Xoá bộ lọc")}
              </Button>
            }
          />
        ) : (
          <div className="grid gap-6">
            {groupMeetings(shown).map((group) => (
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
      </SettingsLayout>
      <NewMeetingDialog open={creating} onOpenChange={setCreating} />
      <UploadRecordingDialog open={uploading} onOpenChange={setUploading} />
    </AppShell>
  );
}
