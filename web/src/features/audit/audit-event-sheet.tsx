import type { ReactNode } from "react";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { StatusBadge } from "@/components/ui/status-badge";
import { formatUiDate } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { AuditEvent } from "@/lib/hey-api/types.gen";
import {
  actionLabels,
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
              <SheetTitle>
                {actionLabels[event.action] ? ui(actionLabels[event.action]!) : event.action}
              </SheetTitle>
              <SheetDescription className="flex flex-wrap items-center gap-2">
                <code className="font-mono text-xs">{event.action}</code>
                <StatusBadge tone={outcomeTones[event.outcome]} size="sm">
                  {ui(outcomeLabels[event.outcome])}
                </StatusBadge>
                <span>{ui(classLabels[event.eventClass])}</span>
              </SheetDescription>
            </SheetHeader>
            <div className="flex flex-col gap-6 px-4 pb-6">
              <Block title={ui("When")}>
                <Field label={ui("Local time")}>
                  {formatUiDate(event.occurredAt, { dateStyle: "full", timeStyle: "medium" })}
                </Field>
                <Field label={ui("UTC")}>
                  <span className="font-mono text-xs">{event.occurredAt}</span>
                </Field>
              </Block>
              <Block title={ui("Who")}>
                <Field label={ui("Person")}>{event.actorLabel ?? ui("System")}</Field>
                {event.actorEmail ? <Field label={ui("Email")}>{event.actorEmail}</Field> : null}
                {event.sourceIp ? <Field label={ui("IP address")}>{event.sourceIp}</Field> : null}
                {event.endpoint ? (
                  <Field label={ui("Request")}>
                    <span className="font-mono text-xs break-all">{event.endpoint}</span>
                  </Field>
                ) : null}
                {event.traceId ? (
                  <Field label={ui("Trace")}>
                    <span className="font-mono text-xs break-all">{event.traceId}</span>
                  </Field>
                ) : null}
              </Block>
              <Block title={ui("What")}>
                <Field label={ui("Item")}>{event.resourceLabel ?? "—"}</Field>
                {event.resourceType ? (
                  <Field label={ui("Type")}>
                    <span className="font-mono text-xs">{event.resourceType}</span>
                  </Field>
                ) : null}
                {event.resourceId ? (
                  <Field label={ui("ID")}>
                    <span className="font-mono text-xs break-all">{event.resourceId}</span>
                  </Field>
                ) : null}
                {others.map(([key, value]) => (
                  <Field key={key} label={fieldLabel(key, ui)}>
                    {detailText(value, ui)}
                  </Field>
                ))}
              </Block>
              {changes.length > 0 ? (
                <Block title={ui("Changes")}>
                  <table className="w-full table-fixed text-left">
                    <thead className="font-secondary-body text-content-muted">
                      <tr>
                        <th className="w-1/3 pb-1 font-normal">{ui("Field")}</th>
                        <th className="pb-1 font-normal">{ui("Before")}</th>
                        <th className="pb-1 font-normal">{ui("After")}</th>
                      </tr>
                    </thead>
                    <tbody className="align-top">
                      {changes.map((row) => (
                        <tr key={row.field} className="border-t border-border-subtle">
                          <td className="py-1.5 pr-2 break-words text-content-muted">
                            {row.field ? fieldLabel(row.field, ui) : "—"}
                          </td>
                          <td className="py-1.5 pr-2 break-words text-content-secondary">
                            {row.before}
                          </td>
                          <td className="py-1.5 break-words text-content-primary">{row.after}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
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

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[7rem_1fr] gap-3">
      <dt className="font-secondary-body text-content-muted">{label}</dt>
      <dd className="min-w-0 break-words text-content-primary">{children}</dd>
    </div>
  );
}
