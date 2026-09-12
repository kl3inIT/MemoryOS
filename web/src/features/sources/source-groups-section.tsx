import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQuery } from "@tanstack/react-query";
import { ShieldCheck, UsersRound } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  listSourceGroupsOptions,
  updateSourceGroupsMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { SourceGroupPicker } from "./source-group-picker";
import { sourceMutationError } from "./source-errors";
import { SourceSectionIcon } from "./source-section-icon";

type SourceGroupsSectionProps = {
  sourceId: string;
  editable: boolean;
  onAuthorityChanged: () => Promise<void>;
};

export function SourceGroupsSection({
  sourceId,
  editable,
  onAuthorityChanged,
}: SourceGroupsSectionProps) {
  const ui = useAppTranslation();

  const groups = useQuery({
    ...listSourceGroupsOptions({ path: { sourceId } }),
    retry: false,
  });
  const updateGroups = useMutation(updateSourceGroupsMutation());
  const [baselineIds, setBaselineIds] = useState<Set<string>>(() => new Set());
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set());
  const [error, setError] = useState<AppCopy | null>(null);
  const currentGroups = useMemo(() => groups.data?.items ?? [], [groups.data?.items]);
  const incomingIds = useMemo(
    () => new Set(currentGroups.map((group) => group.id)),
    [currentGroups],
  );
  const baselineKey = [...baselineIds].sort().join("\u0000");
  const selectedKey = [...selectedIds].sort().join("\u0000");
  const incomingKey = [...incomingIds].sort().join("\u0000");
  const seededIncomingKeyRef = useRef<string | null>(null);
  const dirty = baselineKey !== selectedKey;

  useEffect(() => {
    if (!groups.data || dirty || seededIncomingKeyRef.current === incomingKey) return;
    seededIncomingKeyRef.current = incomingKey;
    setBaselineIds(new Set(incomingIds));
    setSelectedIds(new Set(incomingIds));
  }, [dirty, groups.data, incomingIds, incomingKey]);

  async function save() {
    if (!dirty || selectedIds.size === 0 || updateGroups.isPending) return;
    setError(null);
    try {
      await updateGroups.mutateAsync({
        path: { sourceId },
        headers: sameOriginMutationHeaders,
        body: { groupIds: [...selectedIds] },
      });
      setBaselineIds(new Set(selectedIds));
      await onAuthorityChanged();
    } catch (cause) {
      setError(sourceMutationError(cause, "associations"));
    }
  }

  return (
    <section
      aria-labelledby="source-groups-heading"
      className="mt-8 border-t border-border-subtle pt-6"
    >
      <div className="flex items-center gap-3">
        <SourceSectionIcon icon={UsersRound} />
        <h2 id="source-groups-heading" className="font-heading-h3 text-content-primary">
          {ui("Group associations")}
        </h2>
      </div>
      <p className="mt-3 font-main-ui-body text-content-muted">
        {ui("These groups define who can manage this Source within their authorized surface.")}
      </p>

      {error ? (
        <p
          role="alert"
          className="mt-4 rounded-lg bg-status-danger-surface px-4 py-3 font-secondary-body text-status-danger-content"
        >
          {ui(error)}
        </p>
      ) : null}

      {groups.isPending ? (
        <p
          role="status"
          className="mt-4 rounded-xl border border-border-subtle px-4 py-7 font-main-ui-body text-content-muted"
        >
          {ui("Loading group associations")}
        </p>
      ) : groups.isError ? (
        <div className="mt-4 rounded-xl border border-border-subtle p-4">
          <p role="alert" className="font-main-ui-body text-content-secondary">
            {ui("Group associations could not be loaded.")}
          </p>
          <Button
            size="sm"
            prominence="secondary"
            className="mt-3"
            onClick={() => void groups.refetch()}
          >
            {ui("Try again")}
          </Button>
        </div>
      ) : editable ? (
        <div className="mt-4">
          <SourceGroupPicker
            className="[--control-height-sm:var(--control-height-md)] [&_[data-slot=input]:enabled]:bg-surface-raised [&_[data-slot=source-group-options]]:rounded-none [&_[data-slot=source-group-options]]:border-x-0 [&_[data-slot=source-group-options]]:bg-transparent"
            selected={selectedIds}
            knownGroups={currentGroups}
            required
            onChange={setSelectedIds}
          />
          <div className="mt-4 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <Button
              prominence="secondary"
              disabled={!dirty || updateGroups.isPending}
              onClick={() => {
                setSelectedIds(new Set(baselineIds));
                setError(null);
              }}
            >
              {ui("Cancel")}
            </Button>
            <Button
              pending={updateGroups.isPending}
              disabled={!dirty || selectedIds.size === 0}
              onClick={() => void save()}
            >
              {updateGroups.isPending ? ui("Saving associations…") : ui("Save associations")}
            </Button>
          </div>
        </div>
      ) : currentGroups.length === 0 ? (
        <div className="mt-4 rounded-xl border border-dashed border-border-default px-4 py-8 text-center font-main-ui-body text-content-muted">
          {ui("No group associations are visible.")}
        </div>
      ) : (
        <div className="mt-4 flex flex-wrap gap-2" aria-label={ui("Source groups")}>
          {currentGroups.map((group) => (
            <Badge
              key={group.id}
              variant="secondary"
              className="gap-1.5 border border-border-subtle bg-surface-subtle text-content-secondary"
            >
              {group.systemKey ? (
                <ShieldCheck className="size-3" aria-hidden="true" />
              ) : (
                <UsersRound className="size-3" aria-hidden="true" />
              )}
              {group.name}
            </Badge>
          ))}
        </div>
      )}
    </section>
  );
}
