import { useDeferredValue, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { Clock, FileAudio, Mic, MonitorSpeaker, Search, Users, WifiOff } from "lucide-react";
import { AppShellHeader } from "@/components/app-shell/app-shell-header";
import { Button } from "@/components/ui/button";
import { BrandLoader } from "@/components/brand-loader";
import { EmptyState } from "@/components/composites/empty-state";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { NativeSelect } from "@/components/ui/native-select";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { StatusBadge } from "@/components/ui/status-badge";
import { i18n } from "@/i18n";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { listMeetingsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { formatClock, formatWhen, type MeetingSummary } from "./meetings-api";
import { useActiveMeeting } from "./meeting-session";
import { MEETING_PERIOD_DAYS, type MeetingsSearch } from "./meetings-search";
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

/**
 * Starts a recording, or, while this browser is recording one, leads back to it: one browser records one meeting
 * at a time, and a button that only refused would not say so.
 */
function RecordAction({
  live,
  size,
  onRecord,
}: {
  live: string | undefined;
  size?: "sm";
  onRecord: () => void;
}) {
  const ui = useAppTranslation();
  if (live)
    return (
      <Button asChild size={size}>
        <Link to="/meetings/$meetingId" params={{ meetingId: live }}>
          <Mic aria-hidden="true" />
          {ui("Về cuộc họp đang ghi")}
        </Link>
      </Button>
    );
  return (
    <Button size={size} onClick={onRecord}>
      <Mic aria-hidden="true" />
      {ui("Ghi cuộc họp mới")}
    </Button>
  );
}

/** The meetings a name search, a status and a period leave visible. */
function matchingMeetings(
  meetings: readonly MeetingSummary[],
  query: string,
  status: MeetingsSearch["status"],
  period: keyof typeof MEETING_PERIOD_DAYS,
  now = Date.now(),
) {
  const days = MEETING_PERIOD_DAYS[period];
  const since = days === undefined ? 0 : now - days * 86_400_000;
  return meetings.filter(
    (meeting) =>
      (!status || meeting.status === status) &&
      Date.parse(meeting.createdAt) >= since &&
      (query === "" || meeting.title.toLocaleLowerCase("vi").includes(query)),
  );
}

export function MeetingsPage() {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const [creating, setCreating] = useState(false);
  const [uploading, setUploading] = useState(false);
  // The filters are part of the address, so coming back from a meeting shows the list as it was left.
  const {
    q: search = "",
    status,
    period = "month",
  } = useSearch({ from: "/_authenticated/meetings" });
  const navigate = useNavigate({ from: "/meetings" });
  const filter = (next: MeetingsSearch) =>
    void navigate({ replace: true, search: (current) => ({ ...current, ...next }) });
  const query = useDeferredValue(search.trim().toLocaleLowerCase("vi"));
  const live = useActiveMeeting();
  const meetings = useQuery(listMeetingsOptions());
  const groupTitle = { today: ui("Hôm nay"), week: ui("7 ngày qua"), earlier: ui("Trước đó") };
  const all = meetings.data;
  /** The list is small and already owner-private, so it filters in the browser. */
  const shown = useMemo(
    () => (all ? matchingMeetings(all, query, status, period) : []),
    [all, query, status, period],
  );
  const filtered = !!all && all.length > 0 && shown.length === 0;

  return (
    <>
      <AppShellHeader title={ui("Cuộc họp")} pageHeader />
      <SettingsLayout wide className="gap-6">
        <PageHeader
          title={ui("Cuộc họp")}
          icon={<Mic />}
          actions={
            <>
              <Button prominence="secondary" onClick={() => setUploading(true)}>
                <FileAudio aria-hidden="true" />
                {ui("Tải file ghi âm")}
              </Button>
              <RecordAction live={live?.meetingId} onRecord={() => setCreating(true)} />
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
          <div className="flex flex-wrap gap-2">
            <InputGroup className="w-full sm:w-auto sm:max-w-80 sm:min-w-56 sm:flex-1">
              <InputGroupAddon>
                <Search aria-hidden="true" />
              </InputGroupAddon>
              <InputGroupInput
                value={search}
                placeholder={ui("Tìm theo tên cuộc họp")}
                aria-label={ui("Tìm theo tên cuộc họp")}
                onChange={(event) => filter({ q: event.target.value || undefined })}
              />
            </InputGroup>
            <NativeSelect
              value={status ?? ""}
              aria-label={ui("Trạng thái")}
              className="w-auto flex-1 sm:flex-none"
              onChange={(event) =>
                filter({ status: (event.target.value || undefined) as MeetingsSearch["status"] })
              }
            >
              <option value="">{ui("Mọi trạng thái")}</option>
              <option value="RECORDING">{ui("Chưa kết thúc")}</option>
              <option value="TRANSCRIBING">{ui("Đang nhận dạng")}</option>
              <option value="ENDED">{ui("Đã kết thúc")}</option>
            </NativeSelect>
            <NativeSelect
              value={period}
              aria-label={ui("Thời gian")}
              className="w-auto flex-1 sm:flex-none"
              onChange={(event) => {
                const next = event.target.value as typeof period;
                filter({ period: next === "month" ? undefined : next });
              }}
            >
              <option value="month">{ui("30 ngày qua")}</option>
              <option value="quarter">{ui("90 ngày qua")}</option>
              <option value="all">{ui("Tất cả")}</option>
            </NativeSelect>
          </div>
        )}
        {meetings.isPending || meetings.isError ? null : !all || all.length === 0 ? (
          <EmptyState
            icon={<Mic />}
            title={ui("Chưa có cuộc họp nào")}
            action={
              <RecordAction size="sm" live={live?.meetingId} onRecord={() => setCreating(true)} />
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
                onClick={() => filter({ q: undefined, status: undefined, period: "all" })}
              >
                {ui("Xóa bộ lọc")}
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
                        className="flex flex-wrap items-center gap-x-4 gap-y-1 px-4 py-3 hover:bg-surface-base focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:outline-none"
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
                        ) : meeting.status === "TRANSCRIBING" ? (
                          <StatusBadge tone="info">{ui("Đang nhận dạng")}</StatusBadge>
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
    </>
  );
}
