import { useState } from "react";
import { Button } from "@/components/ui/button";
import type {
  InvitationListSearch,
  InvitationStatusFilter,
} from "@/features/invitations/invitation-list-search";

const controlClassName =
  "h-10 rounded-lg border border-border-default bg-surface-base px-3 font-main-ui-body text-content-primary outline-none focus:border-focus-ring focus:ring-3 focus:ring-ring/30";

type InvitationFiltersProps = {
  search: InvitationListSearch;
  onApply: (filters: Pick<InvitationListSearch, "status" | "email">) => void;
  onClear: () => void;
};

export function InvitationFilters({ search, onApply, onClear }: InvitationFiltersProps) {
  const [email, setEmail] = useState(search.email ?? "");
  const [status, setStatus] = useState<InvitationStatusFilter | "">(search.status ?? "");

  function submit() {
    const normalizedEmail = email.trim().toLowerCase();
    onApply({
      status: status || undefined,
      email: normalizedEmail || undefined,
    });
  }

  return (
    <form
      aria-label="Filter invitations"
      className="grid gap-3 border-b border-border-subtle bg-surface-subtle/40 p-4 lg:grid-cols-[minmax(14rem,1fr)_11rem_auto] lg:items-end"
      onSubmit={(event) => {
        event.preventDefault();
        submit();
      }}
    >
      <label className="grid gap-1.5 font-secondary-action text-content-secondary">
        Email
        <input
          type="search"
          value={email}
          maxLength={254}
          placeholder="Search invitation email"
          className={controlClassName}
          onChange={(event) => setEmail(event.target.value)}
        />
      </label>

      <label className="grid gap-1.5 font-secondary-action text-content-secondary">
        Status
        <select
          value={status}
          className={controlClassName}
          onChange={(event) => setStatus(event.target.value as InvitationStatusFilter | "")}
        >
          <option value="">All statuses</option>
          <option value="PENDING">Pending</option>
          <option value="ACCEPTED">Accepted</option>
          <option value="EXPIRED">Expired</option>
          <option value="REVOKED">Revoked</option>
        </select>
      </label>

      <div className="flex gap-2 lg:pb-px">
        <Button type="submit">Apply</Button>
        <Button
          type="button"
          variant="ghost"
          onClick={() => {
            setEmail("");
            setStatus("");
            onClear();
          }}
        >
          Clear
        </Button>
      </div>
    </form>
  );
}
