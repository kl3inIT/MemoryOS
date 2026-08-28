import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { TextButton } from "@/components/ui/text-button";
import type {
  InvitationListSearch,
  InvitationStatusFilter,
} from "@/features/invitations/invitation-list-search";

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
        <Input
          type="search"
          value={email}
          maxLength={254}
          placeholder="Search invitation email"
          onChange={(event) => setEmail(event.target.value)}
        />
      </label>

      <label className="grid gap-1.5 font-secondary-action text-content-secondary">
        Status
        <Select
          value={status}
          onChange={(event) => setStatus(event.target.value as InvitationStatusFilter | "")}
        >
          <option value="">All statuses</option>
          <option value="PENDING">Pending</option>
          <option value="ACCEPTED">Accepted</option>
          <option value="EXPIRED">Expired</option>
          <option value="REVOKED">Revoked</option>
        </Select>
      </label>

      <div className="flex gap-2">
        <Button type="submit">Apply</Button>
        <TextButton
          onClick={() => {
            setEmail("");
            setStatus("");
            onClear();
          }}
        >
          Clear
        </TextButton>
      </div>
    </form>
  );
}
