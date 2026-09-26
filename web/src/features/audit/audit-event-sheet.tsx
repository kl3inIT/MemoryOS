import type { ReactNode } from "react";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { StatusBadge } from "@/components/ui/status-badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { formatUiDate } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { AuditEvent } from "@/lib/hey-api/types.gen";
import {
  actionLabel,
  changeRows,
  classLabels,
  detailText,
  fieldLabel,
  outcomeLabels,
  outcomeTones,
} from "./audit-actions";

/** One event as When / Who / What, with a before-and-after table for a changed setting (Railway, Employment Hero). */
export function AuditEventSheet({
  event,
  onClose,
}: {
  event: AuditEvent | null;
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const details: Record<string, unknown> = event?.details ?? {};
  const changes =
    "before" in details || "after" in details ? changeRows(details.before, details.after, ui) : [];
  const others = Object.entries(details).filter(([key]) => key !== "before" && key !== "after");
  return (
    <Sheet open={event !== null} onOpenChange={(open) => (open ? null : onClose())}>
      <SheetContent className="w-full overflow-y-auto sm:max-w-lg">
        {event ? (
          <>
            <SheetHeader>
              <SheetTitle>{actionLabel(event.action, ui)}</SheetTitle>
              <SheetDescription asChild>
                <div className="flex flex-wrap items-center gap-2">
                  <code className="font-mono text-xs">{event.action}</code>
                  <StatusBadge tone={outcomeTones[event.outcome]} size="sm">
                    {ui(outcomeLabels[event.outcome])}
                  </StatusBadge>
                  <span>{ui(classLabels[event.eventClass])}</span>
                </div>
              </SheetDescription>
            </SheetHeader>
            <div className="flex flex-col gap-6 px-4 pb-6">
              <Block title={ui("When")}>
                <Detail label={ui("Local time")}>
                  {formatUiDate(event.occurredAt, { dateStyle: "full", timeStyle: "medium" })}
                </Detail>
                <Detail label={ui("UTC")}>
                  <span className="font-mono text-xs">{event.occurredAt}</span>
                </Detail>
              </Block>
              <Block title={ui("Who")}>
                <Detail label={ui("Person")}>{event.actorLabel ?? ui("System")}</Detail>
                {event.actorEmail ? <Detail label={ui("Email")}>{event.actorEmail}</Detail> : null}
                {event.sourceIp ? <Detail label={ui("IP address")}>{event.sourceIp}</Detail> : null}
                {event.endpoint ? (
                  <Detail label={ui("Request")}>
                    <span className="font-mono text-xs break-all">{event.endpoint}</span>
                  </Detail>
                ) : null}
                {event.traceId ? (
                  <Detail label={ui("Trace")}>
                    <span className="font-mono text-xs break-all">{event.traceId}</span>
                  </Detail>
                ) : null}
              </Block>
              <Block title={ui("What")}>
                <Detail label={ui("Item")}>{event.resourceLabel ?? "—"}</Detail>
                {event.resourceType ? (
                  <Detail label={ui("Type")}>
                    <span className="font-mono text-xs">{event.resourceType}</span>
                  </Detail>
                ) : null}
                {event.resourceId ? (
                  <Detail label={ui("ID")}>
                    <span className="font-mono text-xs break-all">{event.resourceId}</span>
                  </Detail>
                ) : null}
                {others.map(([key, value]) => (
                  <Detail key={key} label={fieldLabel(key, ui)}>
                    {detailText(value, ui)}
                  </Detail>
                ))}
              </Block>
              {changes.length > 0 ? (
                <Block title={ui("Changes")}>
                  <Table className="table-fixed">
                    <TableHeader>
                      <TableRow>
                        <TableHead className="w-1/3">{ui("Field")}</TableHead>
                        <TableHead>{ui("Before")}</TableHead>
                        <TableHead>{ui("After")}</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {changes.map((row) => (
                        <TableRow key={row.field}>
                          <TableCell className="break-words align-top">
                            <span className="text-content-muted">
                              {row.field ? fieldLabel(row.field, ui) : "—"}
                            </span>
                          </TableCell>
                          <TableCell className="break-words align-top">
                            <span className="text-content-secondary">{row.before}</span>
                          </TableCell>
                          <TableCell className="break-words align-top">{row.after}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </Block>
              ) : null}
            </div>
          </>
        ) : null}
      </SheetContent>
    </Sheet>
  );
}

function Block({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-2">
      <h3 className="font-heading-h3 text-content-primary">{title}</h3>
      <dl className="flex flex-col gap-1.5">{children}</dl>
    </section>
  );
}

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex gap-3">
      <dt className="w-28 shrink-0 font-secondary-body text-content-muted">{label}</dt>
      <dd className="min-w-0 flex-1 break-words text-content-primary">{children}</dd>
    </div>
  );
}
