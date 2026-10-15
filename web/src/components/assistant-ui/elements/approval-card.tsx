// Adapted from assistant-ui (MIT), elements-approval-card registry, 2026-10-14: colours, radius and type sizes
// in the MemoryOS tokens, as the other adapted elements.
import { useId, type ComponentProps, type ReactNode } from "react";
import { CheckIcon, Loader2Icon, TerminalIcon, XIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { field, ghostButton, inkButton, mono, paper } from "./surfaces";
import { useReceiptFocus } from "./receipt-focus";

export type ApprovalState = "request" | "running" | "done" | "denied";

const receiptText: Record<Exclude<ApprovalState, "request">, string> = {
  running: "Approved, running",
  done: "Finished",
  denied: "Denied",
};

export function ApprovalCard({
  state,
  command,
  title,
  subtitle,
  description,
  details,
  variant = "default",
  icon,
  mark,
  onAllowOnce,
  onAlwaysAllow,
  onDeny,
  allowOnceLabel = "Allow once",
  alwaysAllowLabel = "Always allow",
  denyLabel = "Deny",
  statusLabel,
  className,
  ...props
}: Omit<
  ComponentProps<"div">,
  | "children"
  | "state"
  | "command"
  | "title"
  | "subtitle"
  | "description"
  | "details"
  | "variant"
  | "icon"
  | "mark"
  | "onAllowOnce"
  | "onAlwaysAllow"
  | "onDeny"
  | "allowOnceLabel"
  | "alwaysAllowLabel"
  | "denyLabel"
  | "statusLabel"
> & {
  state: ApprovalState;
  command?: string | undefined;
  title: string;
  subtitle: string;
  description?: string | undefined;
  details?: readonly { label: string; value: string }[] | undefined;
  variant?: "default" | "destructive" | undefined;
  icon?: ReactNode | undefined;
  /** A tile of its own, such as the app's logo, shown in place of the icon and its tile. */
  mark?: ReactNode | undefined;
  onAllowOnce?: (() => void) | undefined;
  onAlwaysAllow?: (() => void) | undefined;
  onDeny?: (() => void) | undefined;
  allowOnceLabel?: string | undefined;
  alwaysAllowLabel?: string | undefined;
  denyLabel?: string | undefined;
  statusLabel?: string | undefined;
}) {
  const titleId = useId();
  const descriptionId = useId();
  const { receiptRef, focusHandlers } = useReceiptFocus<HTMLDivElement>(props);

  return (
    <div
      {...props}
      {...focusHandlers}
      role="group"
      data-variant={variant}
      data-slot="approval-card"
      aria-labelledby={titleId}
      aria-describedby={description ? descriptionId : undefined}
      className={cn(paper, "flex w-full max-w-sm flex-col gap-3.5 rounded-2xl p-4", className)}
    >
      <div className="flex items-center gap-3">
        {mark ?? (
          <span
            aria-hidden
            className={cn(
              "flex size-9 shrink-0 items-center justify-center rounded-xl",
              variant === "destructive"
                ? "bg-status-danger-surface text-status-danger-content"
                : "bg-foreground/[0.05] text-muted-foreground",
            )}
          >
            {icon ?? <TerminalIcon className="size-4" />}
          </span>
        )}
        <div className="flex flex-col">
          <p id={titleId} className="text-sm font-medium">
            {title}
          </p>
          <p className="text-xs text-muted-foreground">{subtitle}</p>
        </div>
      </div>

      {description ? (
        <p id={descriptionId} className="text-code text-foreground/60">
          {description}
        </p>
      ) : null}

      {command ? (
        <div className={cn(field, "rounded-xl px-3.5 py-2.5 font-mono text-xs text-foreground/70")}>
          {command}
        </div>
      ) : null}

      {details?.length ? (
        <dl className={cn(field, "flex flex-col gap-2 rounded-xl px-3.5 py-2.5")}>
          {details.map((detail, index) => (
            <div key={`${detail.label}-${index}`} className="grid grid-cols-3 gap-4 text-xs">
              <dt className={cn(mono, "text-muted-foreground")}>{detail.label}</dt>
              <dd className="col-span-2 break-words whitespace-pre-wrap text-foreground/80">
                {detail.value}
              </dd>
            </div>
          ))}
        </dl>
      ) : null}

      <div className="flex min-h-8 flex-wrap items-center justify-end gap-2">
        {state === "request" ? (
          <>
            {onDeny && (
              <button
                type="button"
                data-slot="approval-card-deny"
                onClick={onDeny}
                className={cn(ghostButton, "h-8 px-3.5 text-xs font-medium whitespace-nowrap")}
              >
                {denyLabel}
              </button>
            )}
            {onAlwaysAllow && (
              <button
                type="button"
                data-slot="approval-card-always-allow"
                onClick={onAlwaysAllow}
                className={cn(ghostButton, "h-8 px-3.5 text-xs font-medium whitespace-nowrap")}
              >
                {alwaysAllowLabel}
              </button>
            )}
            {onAllowOnce && (
              <button
                type="button"
                data-slot="approval-card-allow-once"
                onClick={onAllowOnce}
                className={cn(
                  variant === "destructive"
                    ? "bg-destructive text-white transition-colors duration-150 hover:bg-destructive/90 motion-reduce:transition-none"
                    : inkButton,
                  "flex h-8 items-center rounded-full px-3.5 text-xs font-medium whitespace-nowrap",
                )}
              >
                {allowOnceLabel}
              </button>
            )}
          </>
        ) : (
          <div
            key={state}
            ref={receiptRef}
            tabIndex={-1}
            role="status"
            className="flex animate-in items-center gap-2 text-xs text-muted-foreground duration-300 fade-in motion-reduce:animate-none"
          >
            {state === "running" ? (
              <>
                <Loader2Icon className="size-3.5 animate-spin text-muted-foreground motion-reduce:animate-none" />
                {statusLabel ?? receiptText.running}
              </>
            ) : state === "denied" ? (
              <>
                <XIcon className="size-3.5 text-muted-foreground" />
                {statusLabel ?? receiptText.denied}
              </>
            ) : (
              <>
                <CheckIcon className="size-3.5 text-status-success-strong" />
                {statusLabel ?? receiptText.done}
              </>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
