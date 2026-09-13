import { uiLocale } from "@/i18n/format";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Search, X } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useCapabilityAuthority } from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  listGroupSourcesOptions,
  listSourcesOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { listSourceGroups, updateSourceGroups } from "@/lib/hey-api/sdk.gen";
import type { GroupSummary, SourceSummary } from "@/lib/hey-api/types.gen";
import { groupMutationError } from "./group-errors";
import { findSourceProvider } from "@/features/sources/source-provider-catalog";

type GroupSourcesSectionProps = {
  group: GroupSummary;
  onAuthorityChanged: () => Promise<void>;
};

export function GroupSourcesSection({ group, onAuthorityChanged }: GroupSourcesSectionProps) {
  const ui = useAppTranslation();

  const canOpenSources = useCapabilityAuthority("SOURCES_READ") !== "none";
  const globalManage = useCapabilityAuthority("SOURCES_MANAGE") === "global";
  const ordinaryGroup = group.systemKey === null;
  const canManage = ordinaryGroup && group.actions.includes("manage_sources");
  const associated = useQuery({
    ...listGroupSourcesOptions({ path: { groupId: group.id } }),
    enabled: ordinaryGroup && (canOpenSources || canManage),
    retry: false,
  });
  const allSources = useQuery({
    ...listSourcesOptions(),
    enabled: canManage,
    retry: false,
  });
  const [baselineIds, setBaselineIds] = useState<Set<string>>(() => new Set());
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set());
  const [search, setSearch] = useState("");
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<AppCopy | null>(null);
  const baselineKey = [...baselineIds].sort().join("\u0000");
  const selectedKey = [...selectedIds].sort().join("\u0000");
  const dirty = baselineKey !== selectedKey;
  const incomingIds = useMemo(
    () => new Set((associated.data?.items ?? []).map((source) => source.id)),
    [associated.data?.items],
  );
  const seededIncomingKeyRef = useRef<string | null>(null);
  const incomingKey = [...incomingIds].sort().join("\u0000");
  useEffect(() => {
    if (!associated.data || dirty || seededIncomingKeyRef.current === incomingKey) return;
    seededIncomingKeyRef.current = incomingKey;
    setBaselineIds(new Set(incomingIds));
    setSelectedIds(new Set(incomingIds));
  }, [associated.data, dirty, incomingIds, incomingKey]);

  const [previousAssociationState, setPreviousAssociationState] = useState(() => ({
    canManage,
    incomingKey,
  }));
  if (
    previousAssociationState.canManage !== canManage ||
    previousAssociationState.incomingKey !== incomingKey
  ) {
    setPreviousAssociationState({ canManage, incomingKey });
    if (!canManage) {
      setSelectedIds(new Set(incomingIds));
      setBaselineIds(new Set(incomingIds));
      setSearch("");
      setError(null);
    }
  }

  const saveAssociations = useMutation({
    mutationFn: async () => {
      if (!canManage) throw new Error("Source association access has changed");
      const additions = [...selectedIds].filter((sourceId) => !baselineIds.has(sourceId));
      const removals = [...baselineIds].filter((sourceId) => !selectedIds.has(sourceId));
      const changes = [
        ...additions.map((sourceId) => ({ sourceId, add: true })),
        ...removals.map((sourceId) => ({ sourceId, add: false })),
      ];
      if (
        changes.some(
          (change) =>
            !allSources.data?.some(
              (source) => source.id === change.sourceId && source.actions.includes("manage_groups"),
            ),
        )
      )
        throw new Error("Source association access has changed");
      const currentGroups = await Promise.all(
        changes.map(async (change) => {
          const { data } = await listSourceGroups({
            path: { sourceId: change.sourceId },
            throwOnError: true,
          });
          return {
            ...change,
            groupIds: data.items.filter((item) => item.systemKey === null).map((item) => item.id),
          };
        }),
      );
      const replacements = currentGroups.map((change) => {
        const ids = new Set(change.groupIds);
        if (change.add) ids.add(group.id);
        else ids.delete(group.id);
        return { sourceId: change.sourceId, groupIds: [...ids] };
      });
      if (!globalManage && replacements.some((replacement) => replacement.groupIds.length === 0)) {
        throw new Error("SOURCE_REQUIRES_GROUP");
      }
      await Promise.all(
        replacements.map((replacement) =>
          updateSourceGroups({
            path: { sourceId: replacement.sourceId },
            headers: sameOriginMutationHeaders,
            body: { groupIds: replacement.groupIds },
            throwOnError: true,
          }),
        ),
      );
    },
  });

  async function save() {
    if (!canManage || !dirty || saveAssociations.isPending) return;
    setError(null);
    try {
      await saveAssociations.mutateAsync();
      setBaselineIds(new Set(selectedIds));
      await onAuthorityChanged();
    } catch (cause) {
      setError(
        cause instanceof Error && cause.message === "SOURCE_REQUIRES_GROUP"
          ? "Scoped managers must retain at least one managed group. Associate the Source with another group you manage from its detail page first."
          : groupMutationError(cause, "sources"),
      );
    }
  }

  const sources = associated.data?.items ?? [];
  const normalizedSearch = search.trim().toLocaleLowerCase();
  const candidates = (allSources.data ?? []).filter(
    (source) =>
      source.actions.includes("manage_groups") &&
      (!normalizedSearch || source.name.toLocaleLowerCase().includes(normalizedSearch)),
  );
  const unselectedCandidates = candidates.filter((source) => !selectedIds.has(source.id));
  const selectedSources = (allSources.data ?? []).filter((source) => selectedIds.has(source.id));

  useEffect(() => {
    if (!dropdownOpen) return;
    function onPointerDown(event: PointerEvent) {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setDropdownOpen(false);
      }
    }
    document.addEventListener("pointerdown", onPointerDown);
    return () => document.removeEventListener("pointerdown", onPointerDown);
  }, [dropdownOpen]);
  if (!ordinaryGroup || (!canOpenSources && !canManage)) return null;

  return (
    <section aria-labelledby="group-sources-heading" className="border-t border-border-subtle pt-7">
      <h2 id="group-sources-heading" className="font-heading-h3 text-content-primary">
        {ui("Sources")}
      </h2>

      {error ? (
        <p
          role="alert"
          className="mt-4 rounded-lg bg-status-danger-surface px-4 py-3 font-secondary-body text-status-danger-content"
        >
          {ui(error)}
        </p>
      ) : null}

      {associated.isPending ? (
        <p
          role="status"
          className="mt-4 rounded-xl border border-border-subtle px-4 py-7 font-main-ui-body text-content-muted"
        >
          {ui("Loading associated Sources")}
        </p>
      ) : associated.isError ? (
        <div className="mt-4 rounded-xl border border-border-subtle p-4">
          <p role="alert" className="font-main-ui-body text-content-secondary">
            {ui("Source associations could not be loaded.")}
          </p>
          <Button
            size="sm"
            prominence="secondary"
            className="mt-3"
            onClick={() => void associated.refetch()}
          >
            {ui("Try again")}
          </Button>
        </div>
      ) : canManage ? (
        <div className="mt-4">
          <div className="relative">
            <label className="relative block">
              <span className="sr-only">{ui("Search Sources")}</span>
              <Search
                className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-content-muted"
                aria-hidden="true"
              />
              <Input
                type="search"
                value={search}
                placeholder={ui("Search Sources…")}
                className="bg-surface-sunken pl-9"
                onChange={(event) => {
                  setSearch(event.target.value);
                  setDropdownOpen(true);
                }}
                onFocus={() => setDropdownOpen(true)}
              />
            </label>

            {dropdownOpen ? (
              <div
                ref={dropdownRef}
                className="absolute z-50 mt-1 max-h-72 w-full overflow-y-auto rounded-xl border border-border-subtle bg-surface-sunken shadow-md"
              >
                {allSources.isPending ? (
                  <p role="status" className="px-4 py-6 text-center font-main-ui-body text-content-muted">
                    {ui("Loading Sources")}
                  </p>
                ) : allSources.isError ? (
                  <div className="p-4">
                    <p role="alert" className="font-main-ui-body text-content-secondary">
                      {ui("Source choices could not be loaded.")}
                    </p>
                    <Button
                      size="sm"
                      prominence="secondary"
                      className="mt-3"
                      onClick={() => void allSources.refetch()}
                    >
                      {ui("Try again")}
                    </Button>
                  </div>
                ) : unselectedCandidates.length === 0 ? (
                  <p className="px-4 py-6 text-center font-secondary-body text-content-muted">
                    {search
                      ? ui("No Sources match your search.")
                      : ui("All available Sources are selected.")}
                  </p>
                ) : (
                  unselectedCandidates.map((source) => (
                    <button
                      type="button"
                      key={source.id}
                      className="flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-surface-subtle"
                      onClick={() => {
                        setSelectedIds((current) => new Set(current).add(source.id));
                        setSearch("");
                      }}
                    >
                      <SourceIdentity source={source} />
                    </button>
                  ))
                )}
              </div>
            ) : null}
          </div>

          {selectedSources.length > 0 ? (
            <div className="mt-3 flex flex-wrap gap-1.5">
              {selectedSources.map((source) => {
                const ChipIcon = findSourceProvider(source.type)?.icon;
                return (
                <span
                  key={source.id}
                  className="flex items-center gap-1.5 rounded-lg border border-border-subtle bg-surface-sunken px-2 py-1 text-xs"
                >
                  {ChipIcon ? (
                    <ChipIcon className="size-3.5 shrink-0 text-content-secondary" aria-hidden="true" />
                  ) : null}
                  <span className="truncate font-main-ui-action text-content-primary">
                    {source.name}
                  </span>
                  <button
                    type="button"
                    aria-label={ui("Remove {{v1}}", { v1: source.name })}
                    className="ml-0.5 rounded p-0.5 text-content-muted transition-colors hover:text-content-primary"
                    disabled={saveAssociations.isPending}
                    onClick={() =>
                      setSelectedIds((current) => {
                        const next = new Set(current);
                        next.delete(source.id);
                        return next;
                      })
                    }
                  >
                    <X className="size-3.5" aria-hidden="true" />
                  </button>
                </span>
                );
              })}
            </div>
          ) : (
            <div className="mt-3 rounded-xl border border-dashed border-border-default px-4 py-6 text-center font-secondary-body text-content-muted">
              {ui("No Sources are associated with this group.")}
            </div>
          )}

          {dirty ? (
            <div className="mt-4 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
              <Button
                prominence="secondary"
                disabled={saveAssociations.isPending}
                onClick={() => {
                  setSelectedIds(new Set(baselineIds));
                  setError(null);
                }}
              >
                {ui("Cancel")}
              </Button>
              <Button
                pending={saveAssociations.isPending}
                disabled={allSources.isError || associated.isError}
                onClick={() => void save()}
              >
                {saveAssociations.isPending ? ui("Saving associations…") : ui("Save associations")}
              </Button>
            </div>
          ) : null}
        </div>
      ) : sources.length === 0 ? (
        <div className="mt-4 rounded-xl border border-dashed border-border-default px-4 py-8 text-center font-main-ui-body text-content-muted">
          {ui("No Sources are associated with this group.")}
        </div>
      ) : (
        <div className="mt-4 grid gap-3 sm:grid-cols-2">
          {sources.map((source) =>
            canOpenSources ? (
              <Link
                key={source.id}
                to="/admin/sources/$sourceId"
                params={{ sourceId: source.id }}
                className="rounded-xl border border-border-subtle bg-surface-raised px-4 py-3 outline-none transition-colors hover:bg-surface-subtle focus-visible:ring-3 focus-visible:ring-focus-ring/40"
              >
                <SourceIdentity source={source} />
              </Link>
            ) : (
              <div
                key={source.id}
                className="rounded-xl border border-border-subtle bg-surface-raised px-4 py-3"
              >
                <SourceIdentity source={source} />
              </div>
            ),
          )}
        </div>
      )}
    </section>
  );
}

function SourceIdentity({ source }: { source: SourceSummary }) {
  const ui = useAppTranslation();
  const ProviderIcon = findSourceProvider(source.type)?.icon;

  return (
    <span className="flex min-w-0 flex-1 items-center gap-3">
      {ProviderIcon ? (
        <ProviderIcon className="size-4 shrink-0 text-content-secondary" aria-hidden="true" />
      ) : null}
      <span className="min-w-0">
        <span className="block truncate font-main-ui-action text-content-primary">{source.name}</span>
        <span className="mt-0.5 block font-secondary-body text-content-muted">
          {source.type} · {source.documentCount.toLocaleString(uiLocale())} {ui("documents")}
        </span>
      </span>
    </span>
  );
}
