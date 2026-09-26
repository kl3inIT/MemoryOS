import { useAppTranslation } from "@/i18n/use-app-translation";
import { User, Users } from "lucide-react";
import { Alert, AlertAction, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty";
import { Spinner } from "@/components/ui/spinner";
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
      className="flex items-center gap-2 px-2 py-6 font-main-ui-body text-content-muted"
    >
      <Spinner aria-hidden="true" />
      {label}
    </p>
  );
}

export function InlineError({ label, onRetry }: { label: string; onRetry: () => void }) {
  const ui = useAppTranslation();

  return (
    <Alert variant="destructive">
      <AlertDescription>{label}</AlertDescription>
      <AlertAction>
        <Button size="sm" prominence="secondary" onClick={onRetry}>
          {ui("Try again")}
        </Button>
      </AlertAction>
    </Alert>
  );
}

export function EmptyRows({ title, detail }: { title: string; detail: string }) {
  return (
    <Empty>
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <Users />
        </EmptyMedia>
        <EmptyTitle>{title}</EmptyTitle>
        <EmptyDescription>{detail}</EmptyDescription>
      </EmptyHeader>
    </Empty>
  );
}

/** "Showing a–b of n" for a page of people, counting the rows the page actually shows. */
export function PageSummary({
  page,
  shown,
}: {
  page: { page: number; size: number; totalItems: number };
  shown: number;
}) {
  const ui = useAppTranslation();
  const first = shown === 0 ? 0 : page.page * page.size + 1;
  const last = shown === 0 ? 0 : page.page * page.size + shown;
  return ui("Showing {{first}}–{{last}} of {{total}}", {
    first,
    last,
    total: page.totalItems,
  });
}
