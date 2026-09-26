import { useAppTranslation } from "@/i18n/use-app-translation";
import { ChevronLeft, ChevronRight, LoaderCircle, User, Users } from "lucide-react";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import type { GroupMember } from "@/lib/hey-api/types.gen";

export function MemberIdentity({ member }: { member: GroupMember }) {
  const ui = useAppTranslation();
  const email = member.email?.trim() || ui("Email unavailable");
  return (
    <span
      className="block min-w-0 flex-1 truncate font-main-ui-body text-content-primary"
      title={email}
    >
      {email}
    </span>
  );
}
export function MemberAccount({ member }: { member: GroupMember }) {
  const ui = useAppTranslation();

  return (
    <span className="inline-flex shrink-0 items-center gap-1.5 font-main-ui-body text-content-secondary">
      <User className="size-4 text-content-muted" aria-hidden="true" />
      {member.accountType === "STANDARD" ? ui("Standard") : member.accountType}
    </span>
  );
}

export function LoadingRows({ label }: { label: string }) {
  return (
    <p
      role="status"
      className="mt-5 flex items-center gap-2 px-2 py-6 font-main-ui-body text-content-muted"
    >
      <LoaderCircle className="size-4 animate-spin motion-reduce:animate-none" aria-hidden="true" />
      {label}
    </p>
  );
}

export function InlineError({ label, onRetry }: { label: string; onRetry: () => void }) {
  const ui = useAppTranslation();

  return (
    <div className="mt-4 rounded-xl border border-border-subtle px-4 py-5">
      <p role="alert" className="font-main-ui-body text-content-secondary">
        {label}
      </p>
      <Button size="sm" prominence="secondary" className="mt-3" onClick={onRetry}>
        {ui("Try again")}
      </Button>
    </div>
  );
}

export function EmptyRows({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="mt-4 rounded-xl border border-dashed border-border-default px-4 py-8 text-center">
      <Users className="mx-auto size-5 text-content-muted" aria-hidden="true" />
      <p className="mt-2 font-main-ui-action text-content-primary">{title}</p>
      <p className="mt-1 font-secondary-body text-content-muted">{detail}</p>
    </div>
  );
}

export function Pagination({
  label,
  page,
  pageSize,
  itemCount,
  totalItems,
  totalPages,
  disabled,
  onPageChange,
}: {
  label: string;
  page: number;
  pageSize: number;
  itemCount: number;
  totalItems: number;
  totalPages: number;
  disabled: boolean;
  onPageChange: (page: number) => void;
}) {
  const ui = useAppTranslation();
  const firstItem = itemCount === 0 ? 0 : page * pageSize + 1;
  const lastItem = itemCount === 0 ? 0 : page * pageSize + itemCount;
  return (
    <nav aria-label={label} className="mt-3 flex flex-wrap items-center justify-between gap-2">
      <span className="font-secondary-body tabular-nums text-content-secondary" aria-live="polite">
        {ui("Showing {{first}}–{{last}} of {{total}}", {
          first: firstItem,
          last: lastItem,
          total: totalItems,
        })}
      </span>
      <div className="flex items-center gap-1">
        <IconButton
          size="sm"
          aria-label={ui("Previous page")}
          disabled={disabled || page === 0}
          onClick={() => onPageChange(page - 1)}
        >
          <ChevronLeft />
        </IconButton>
        <span
          className="min-w-8 rounded-lg bg-surface-subtle px-2 py-1.5 text-center font-secondary-body tabular-nums text-content-secondary"
          aria-label={ui("Page {{v1}} of {{v2}}", { v1: page + 1, v2: Math.max(totalPages, 1) })}
          aria-current="page"
        >
          {page + 1}
        </span>
        <IconButton
          size="sm"
          aria-label={ui("Next page")}
          disabled={disabled || page + 1 >= totalPages}
          onClick={() => onPageChange(page + 1)}
        >
          <ChevronRight />
        </IconButton>
      </div>
    </nav>
  );
}
