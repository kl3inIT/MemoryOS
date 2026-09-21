import { UserCog } from "lucide-react";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { sameOriginMutationHeaders } from "@/lib/api";
import { listUsersOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { assignSourceManager } from "@/lib/hey-api/sdk.gen";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { sourceMutationError } from "./source-errors";
import { SourceSectionIcon } from "./source-section-icon";

/**
 * Administrator-only. The appointed member attaches this Source to the groups they manage; the server rejects a
 * member who manages none, so the directory is listed as it is rather than guessing eligibility here.
 */
export function SourceManagerSection({
  source,
  onAssigned,
}: {
  source: SourceSummary;
  onAssigned: () => Promise<void>;
}) {
  const ui = useAppTranslation();
  const [search, setSearch] = useState("");
  const [error, setError] = useState<AppCopy | null>(null);
  const [pendingActorId, setPendingActorId] = useState<string | null>(null);
  // The directory is only read once an administrator sets out to change the manager; naming the current one
  // needs no read, because the Source summary carries the profile name.
  const [picking, setPicking] = useState(false);
  const members = useQuery({
    ...listUsersOptions({
      query: { search: search.trim() || undefined, status: "ACTIVE", size: 10 },
    }),
    enabled: picking,
    retry: false,
  });
  const assign = useMutation({
    mutationFn: (actorId: string | null) =>
      assignSourceManager({
        path: { sourceId: source.id },
        headers: sameOriginMutationHeaders,
        body: { actorId },
        throwOnError: true,
      }),
  });

  async function save(actorId: string | null) {
    if (assign.isPending) return;
    setError(null);
    setPendingActorId(actorId);
    try {
      await assign.mutateAsync(actorId);
      await onAssigned();
    } catch (cause) {
      setError(sourceMutationError(cause, "metadata"));
    } finally {
      setPendingActorId(null);
    }
  }

  const rows = (members.data?.items ?? []).filter(
    (member) => member.actorId !== null && member.accountType === "STANDARD",
  );

  return (
    <section
      aria-labelledby="source-manager-heading"
      className="mt-6 rounded-xl border border-border-subtle bg-surface-raised p-4 sm:p-5"
    >
      <div className="flex items-center gap-3">
        <SourceSectionIcon icon={UserCog} />
        <h2 id="source-manager-heading" className="font-heading-h3 text-content-primary">
          {ui("Responsible group manager")}
        </h2>
      </div>
      <p className="mt-3 font-main-ui-body text-content-muted">
        {ui(
          "Add a manager for this Source. A manager must already manage a group, and attaches the Source to the groups they manage.",
        )}
      </p>

      {source.managerActorId === null ? null : (
        <p className="mt-4 font-main-ui-body text-content-primary">
          {ui("Responsible manager: {{v1}}", {
            v1: source.managerName ?? source.managerActorId,
          })}
        </p>
      )}

      {error ? (
        <p
          role="alert"
          className="mt-3 rounded-lg bg-status-danger-surface px-4 py-3 font-secondary-body text-status-danger-content"
        >
          {ui(error)}
        </p>
      ) : null}

      {picking ? null : (
        <Button prominence="secondary" className="mt-4" onClick={() => setPicking(true)}>
          {source.managerActorId === null
            ? ui("Appoint a responsible manager")
            : ui("Change responsible manager")}
        </Button>
      )}

      {picking ? (
        <>
          <label className="mt-4 block space-y-2">
            <span className="sr-only">{ui("Search members")}</span>
            <Input
              type="search"
              value={search}
              placeholder={ui("Search members…")}
              disabled={assign.isPending}
              onChange={(event) => setSearch(event.target.value)}
            />
          </label>

          {members.isPending ? (
            <p role="status" className="mt-3 font-main-ui-body text-content-muted">
              {ui("Loading members")}
            </p>
          ) : members.isError ? (
            <div className="mt-3">
              <p role="alert" className="font-main-ui-body text-content-secondary">
                {ui("Members could not be loaded.")}
              </p>
              <Button
                size="sm"
                prominence="secondary"
                className="mt-3"
                onClick={() => void members.refetch()}
              >
                {ui("Try again")}
              </Button>
            </div>
          ) : rows.length === 0 ? (
            <p className="mt-3 font-secondary-body text-content-muted">
              {ui("No members match your search.")}
            </p>
          ) : (
            <ul className="mt-3 divide-y divide-border-subtle rounded-xl border border-border-subtle">
              {rows.map((member) => (
                <li
                  key={member.actorId}
                  className="flex items-center justify-between gap-3 px-4 py-3"
                >
                  <span className="min-w-0">
                    <span className="block truncate font-main-ui-action text-content-primary">
                      {member.displayName ?? member.email ?? member.actorId}
                    </span>
                    {member.email ? (
                      <span className="mt-0.5 block truncate font-secondary-body text-content-muted">
                        {member.email}
                      </span>
                    ) : null}
                  </span>
                  <Button
                    size="sm"
                    prominence="secondary"
                    pending={assign.isPending && pendingActorId === member.actorId}
                    disabled={assign.isPending || member.actorId === source.managerActorId}
                    onClick={() => void save(member.actorId)}
                  >
                    {member.actorId === source.managerActorId
                      ? ui("Responsible")
                      : ui("Make responsible")}
                  </Button>
                </li>
              ))}
            </ul>
          )}

          {source.managerActorId === null ? null : (
            <Button
              prominence="secondary"
              className="mt-3"
              pending={assign.isPending && pendingActorId === null}
              disabled={assign.isPending}
              onClick={() => void save(null)}
            >
              {ui("Remove responsible manager")}
            </Button>
          )}
        </>
      ) : null}
    </section>
  );
}
