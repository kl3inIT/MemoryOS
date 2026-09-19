import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

/** A labelled setting with its control on the right; stacks rows inside `SettingRows`. */
export function SettingRow({
  title,
  description,
  control,
  htmlFor,
  descriptionId,
  icon,
  className,
}: {
  icon?: ReactNode;
  title: ReactNode;
  description?: ReactNode;
  control: ReactNode;
  htmlFor?: string;
  descriptionId?: string;
  className?: string;
}) {
  const Title = htmlFor ? "label" : "span";
  return (
    <div className={cn("flex min-w-0 items-center gap-3 px-4 py-3", className)}>
      {icon && (
        <span
          aria-hidden="true"
          className="grid size-9 shrink-0 place-items-center rounded-lg bg-surface-sunken text-content-secondary [&_svg]:size-4.5"
        >
          {icon}
        </span>
      )}
      <div className="min-w-0 flex-1">
        <Title htmlFor={htmlFor} className="block font-main-ui-action text-content-primary">
          {title}
        </Title>
        {description && (
          <p id={descriptionId} className="mt-0.5 font-secondary-body text-content-muted">
            {description}
          </p>
        )}
      </div>
      <div className="shrink-0">{control}</div>
    </div>
  );
}

export function SettingRows({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div
      className={cn(
        "divide-y divide-border-subtle overflow-hidden rounded-xl border border-border-subtle bg-surface-raised",
        className,
      )}
    >
      {children}
    </div>
  );
}
