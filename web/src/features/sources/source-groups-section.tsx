import { TriangleAlert, Users } from "lucide-react";
import { appText, type AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { EmptyState } from "@/components/composites/empty-state";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import {
  listSourceGroupsOptions,
  listSourceGroupOptionsOptions,
  updateSourceGroupsMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { GroupAccessPicker } from "@/features/groups/group-access-picker";
import { sourceMutationError } from "@/features/sources/shared/source-errors";
import { SourceSectionIcon } from "@/features/sources/shared/source-section-icon";

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
  // The draft holds only the person's unsaved selection; without one, the saved groups show.
  const [draft, setDraft] = useState<ReadonlySet<string> | null>(null);
  const [error, setError] = useState<AppCopy | null>(null);
  const currentGroups = useMemo(
    () => (groups.data?.items ?? []).filter((group) => group.systemKey === null),
    [groups.data?.items],
  );
  const savedIds = useMemo(() => new Set(currentGroups.map((group) => group.id)), [currentGroups]);
  const selectedIds = (editable && draft) || savedIds;
  const dirty = groupKey(selectedIds) !== groupKey(savedIds);

  const [previousEditable, setPreviousEditable] = useState(editable);
  if (previousEditable !== editable) {
    setPreviousEditable(editable);
    if (!editable) {
      setDraft(null);
      setError(null);
    }
  }

  async function save() {
    if (!editable || !dirty || updateGroups.isPending) return;
    setError(null);
    try {
      await updateGroups.mutateAsync({
        path: { sourceId },
        body: { groupIds: [...selectedIds] },
      });
      // Every view reads again, the saved groups among them, before the draft goes.
      await onAuthorityChanged();
      setDraft(null);
    } catch (cause) {
      setError(sourceMutationError(cause, "associations"));
    }
  }

  return (
    <section aria-labelledby="source-groups-heading">
      <Card size="sm">
        <CardHeader>
          <div className="flex items-center gap-3">
            <SourceSectionIcon icon={Users} />
            <h2 id="source-groups-heading" className="font-heading-h3 text-content-primary">
              {ui("Group associations")}
            </h2>
          </div>
          <CardDescription>
            {ui("Only members of these groups can read this Source.")}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="flex flex-col gap-4">
            {error ? (
              <Alert variant="destructive">
                <AlertDescription>{ui(error)}</AlertDescription>
              </Alert>
            ) : null}

            {/* Reads come from group membership alone, so an unassociated Source reaches nobody. */}
            {groups.data && currentGroups.length === 0 ? (
              <Alert variant="warning" role="note">
                <TriangleAlert aria-hidden="true" />
                <AlertDescription>
                  {ui(
                    "This Source belongs to no group yet, so nobody can search or read its documents. Associate it with a group to make it usable.",
                  )}
                </AlertDescription>
              </Alert>
            ) : null}

            {groups.isPending ? (
              <p role="status" className="py-3 font-main-ui-body text-content-muted">
                {ui("Loading group associations")}
              </p>
            ) : groups.isError ? (
              <EmptyState
                role="alert"
                title={ui("Group associations could not be loaded.")}
                action={
                  <Button size="sm" prominence="secondary" onClick={() => void groups.refetch()}>
                    {ui("Try again")}
                  </Button>
                }
              />
            ) : editable ? (
              <div>
                <GroupAccessPicker
                  load={(query) => listSourceGroupOptionsOptions({ query })}
                  description={appText("Only members of these groups can read this Source.")}
                  selected={selectedIds}
                  knownGroups={groups.data?.items}
                  disabled={updateGroups.isPending}
                  onChange={setDraft}
                />
                <div className="mt-4 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
                  <Button
                    prominence="secondary"
                    disabled={!dirty || updateGroups.isPending}
                    onClick={() => {
                      setDraft(null);
                      setError(null);
                    }}
                  >
                    {ui("Cancel")}
                  </Button>
                  <Button
                    pending={updateGroups.isPending}
                    disabled={!dirty}
                    onClick={() => void save()}
                  >
                    {updateGroups.isPending ? ui("Saving associations…") : ui("Save associations")}
                  </Button>
                </div>
              </div>
            ) : currentGroups.length === 0 ? (
              <EmptyState title={ui("No group associations are visible.")} />
            ) : (
              <div className="flex flex-wrap gap-2" aria-label={ui("Source groups")}>
                {currentGroups.map((group) => (
                  <Badge key={group.id} variant="secondary">
                    <Users data-icon="inline-start" aria-hidden="true" />
                    {group.name}
                  </Badge>
                ))}
              </div>
            )}
          </div>
        </CardContent>
      </Card>
    </section>
  );
}

function groupKey(ids: ReadonlySet<string>) {
  return [...ids].sort().join("\u0000");
}
