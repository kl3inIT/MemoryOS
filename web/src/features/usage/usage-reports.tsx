import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CircleAlert, Download, FileArchive, FileDown } from "lucide-react";
import { useEffect, useState } from "react";
import { EmptyState } from "@/components/composites/empty-state";
import { SectionHeader } from "@/components/composites/section-header";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { IconButton } from "@/components/ui/icon-button";
import { Spinner } from "@/components/ui/spinner";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { TablePagination } from "@/components/ui/table-pagination";
import { formatUiDate } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  listUsageReportsOptions,
  listUsageReportsQueryKey,
  requestUsageReportMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { UsageReport } from "@/lib/hey-api/types.gen";
import { period, periodLabels, type PeriodId } from "./ai-costs";

const periods: PeriodId[] = ["7d", "30d", "month", "lastMonth"];
// Onyx's cadence: poll while a report is being built, and say so when it takes longer than usual.
const PAGE_SIZE = 8;
const POLL_INTERVAL_MS = 3_000;
const SLOW_REPORT_AFTER_MS = 20_000;

const isOpen = (report: UsageReport) => report.status === "PENDING" || report.status === "RUNNING";

function rangeLabel(report: Pick<UsageReport, "from" | "to">) {
  const day = (value: string) =>
    formatUiDate(`${value}T00:00:00Z`, { dateStyle: "medium", timeZone: "UTC" });
  return `${day(report.from)} – ${day(report.to)}`;
}

/**
 * Admin › AI costs › Usage reports (Onyx Usage reports): a period's usage as a ZIP of CSV files and a PDF review pack,
 * built in the background and kept for every model manager to download.
 */
export function UsageReports() {
  const ui = useAppTranslation();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [announcement, setAnnouncement] = useState("");
  const reports = useQuery({
    ...listUsageReportsOptions(),
    refetchInterval: (query) => (query.state.data?.some(isOpen) ? POLL_INTERVAL_MS : false),
  });
  const request = useMutation({
    ...requestUsageReportMutation(),
    onSuccess: () => {
      setPage(1);
      setAnnouncement("");
      void queryClient.invalidateQueries({ queryKey: listUsageReportsQueryKey() });
    },
  });

  const list = reports.data ?? [];
  const pending = list.some(isOpen);
  // Announces a report that finished while this page was open, not the ones already there when it loaded.
  const [seen, setSeen] = useState<{ data?: UsageReport[]; open?: Set<string> }>({});
  if (reports.data && reports.data !== seen.data) {
    const previous = seen.open;
    if (
      previous &&
      reports.data.some((report) => report.status === "READY" && previous.has(report.id))
    )
      setAnnouncement(ui("Usage report ready."));
    setSeen({
      data: reports.data,
      open: new Set(reports.data.filter(isOpen).map((report) => report.id)),
    });
  }
  const totalPages = Math.max(1, Math.ceil(list.length / PAGE_SIZE));
  const shown = list.slice(PAGE_SIZE * (page - 1), PAGE_SIZE * page);

  return (
    <section aria-labelledby="usage-reports-heading" className="flex min-w-0 flex-col gap-4">
      <SectionHeader
        id="usage-reports-heading"
        title={ui("Usage reports")}
        description={ui(
          "Export per-user usage as a ZIP of CSV files and a PDF report. Reports build in the background and stay available here.",
        )}
        actions={
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button prominence="secondary" disabled={request.isPending || pending}>
                <FileDown data-icon="inline-start" aria-hidden="true" />
                {pending ? ui("Generating…") : ui("Generate report")}
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-auto min-w-48">
              <DropdownMenuGroup>
                {periods.map((id) => (
                  <DropdownMenuItem
                    key={id}
                    onSelect={() => {
                      const range = period(id);
                      request.mutate({ body: { from: range.from, to: range.to } });
                    }}
                  >
                    {ui(periodLabels[id])}
                  </DropdownMenuItem>
                ))}
              </DropdownMenuGroup>
            </DropdownMenuContent>
          </DropdownMenu>
        }
      />

      <p role="status" className="sr-only">
        {announcement}
      </p>
      {request.isError && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>
            {ui("Failed to start report generation. Try again in a moment.")}
          </AlertDescription>
        </Alert>
      )}

      {reports.isPending ? (
        <p role="status" className="font-secondary-body text-content-muted">
          {ui("Loading usage reports…")}
        </p>
      ) : reports.isError ? (
        <EmptyState
          role="alert"
          icon={<CircleAlert />}
          title={ui("Failed to load usage reports.")}
          action={
            <Button prominence="secondary" onClick={() => void reports.refetch()}>
              {ui("Try again")}
            </Button>
          }
        />
      ) : list.length === 0 ? (
        <EmptyState
          icon={<FileArchive />}
          title={ui("No reports yet. Pick a period and generate your first one.")}
        />
      ) : (
        <>
          <SettingRows>
            {shown.map((report) => (
              <ReportRow key={report.id} report={report} />
            ))}
          </SettingRows>
          {totalPages > 1 && (
            <TablePagination
              label={ui("Usage reports")}
              page={page}
              totalPages={totalPages}
              previousDisabled={page === 1}
              nextDisabled={page === totalPages}
              onPrevious={() => setPage((current) => current - 1)}
              onNext={() => setPage((current) => current + 1)}
            />
          )}
        </>
      )}
    </section>
  );
}

function ReportRow({ report }: { report: UsageReport }) {
  const ui = useAppTranslation();
  const label = rangeLabel(report);
  const slow = useSlow(report);
  if (isOpen(report))
    return (
      <SettingRow
        icon={<Spinner aria-hidden="true" />}
        title={ui("Preparing report…")}
        description={
          slow
            ? ui("Still working. You can leave this page; the report will appear here.")
            : ui("{{range}} · usually ready in under a minute", { range: label })
        }
        control={null}
      />
    );
  const generated = ui("Generated by {{requester}} · {{time}}", {
    requester: report.requester,
    time: formatUiDate(report.createdAt, { dateStyle: "medium", timeStyle: "short" }),
  });
  if (report.status === "FAILED")
    return (
      <SettingRow
        icon={<CircleAlert className="text-status-danger-content" />}
        title={label}
        description={
          <>
            <span className="text-status-danger-content">
              {ui("The report could not be generated.")}
            </span>
            <span className="block">{generated}</span>
          </>
        }
        control={null}
      />
    );
  return (
    <SettingRow
      icon={<FileArchive />}
      title={label}
      description={
        report.hasPdf ? (
          generated
        ) : (
          <>
            {generated}
            <span className="block">{ui("CSV files only")}</span>
          </>
        )
      }
      control={
        <IconButton
          asChild
          prominence="tertiary"
          title={ui("Download ZIP")}
          aria-label={ui("Download report for {{label}}", { label })}
        >
          <a href={`/api/ai-costs/reports/${report.id}/content`} download>
            <Download aria-hidden="true" />
          </a>
        </IconButton>
      }
    />
  );
}

/** Onyx's hint after 20 s: the report is still building, and leaving the page is safe. */
function useSlow(report: UsageReport) {
  const [slow, setSlow] = useState(
    () => Date.now() - Date.parse(report.createdAt) > SLOW_REPORT_AFTER_MS,
  );
  useEffect(() => {
    if (!isOpen(report) || slow) return;
    const wait = SLOW_REPORT_AFTER_MS - (Date.now() - Date.parse(report.createdAt));
    const timer = setTimeout(() => setSlow(true), Math.max(0, wait));
    return () => clearTimeout(timer);
  }, [report, slow]);
  return slow;
}
