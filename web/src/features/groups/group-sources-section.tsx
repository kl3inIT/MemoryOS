import { uiLocale } from "@/i18n/format";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Lock, Search, X } from "lucide-react";
import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useCapabilityAuthority } from "@/features/identity/application-session-context";
import {
  listGroupSourcesOptions,
  listSourcesOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { listSourceGroups, removeGroupSource, updateSourceGroups } from "@/lib/hey-api/sdk.gen";
import type { GroupSummary, SourceSummary } from "@/lib/hey-api/types.gen";
import { groupMutationError } from "./group-errors";
import { findSourceProvider } from "@/features/sources/source-provider-catalog";
import { can } from "@/lib/resource-permissions";
import { type GroupDraftSectionHandle, type GroupDraftStateChange } from "./group-draft-section";

type GroupSourcesSectionProps = {
  group: GroupSummary;
  onDraftChange: GroupDraftStateChange;
};

const associationAccessChanged = "Source association access has changed";

export const GroupSourcesSection = forwardRef<GroupDraftSectionHandle, GroupSourcesSectionProps>(
  function GroupSourcesSection({ group, onDraftChange }, ref) {
    const ui = useAppTranslation();

    const sourceAuthority = useCapabilityAuthority("SOURCES_READ");
    const ordinaryGroup = group.systemKey === null;
    const canManage = ordinaryGroup && can(group, "manageSources");
    const canOpenSources = sourceAuthority === "global" || canManage;
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
    const baselineKey = [...baselineIds].sort().join("");
    const selectedKey = [...selectedIds].sort().join("");
    const dirty = baselineKey !== selectedKey;
    const incomingIds = useMemo(
      () => new Set((associated.data?.items ?? []).map((source) => source.id)),
      [associated.data?.items],
    );
    const removableIds = useMemo(
      () => new Set(associated.data?.removableSourceIds ?? []),
      [associated.data?.removableSourceIds],
    );
    const seededIncomingKeyRef = useRef<string | null>(null);
    const incomingKey = [...incomingIds].sort().join("");
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
        if (!canManage) throw new Error(associationAccessChanged);
        const additions = [...selectedIds].filter((sourceId) => !baselineIds.has(sourceId));
        const removals = [...baselineIds].filter((sourceId) => !selectedIds.has(sourceId));
        if (
          additions.some(
            (sourceId) =>
              !allSources.data?.some((source) => source.id === sourceId && can(source, "edit")),
          ) ||
          removals.some((sourceId) => !removableIds.has(sourceId))
        )
          throw new Error(associationAccessChanged);
        const additionReplacements = await Promise.all(
          additions.map(async (sourceId) => {
            const { data } = await listSourceGroups({
              path: { sourceId },
            });
            const groupIds = new Set(
              data.items.filter((item) => item.systemKey === null).map((item) => item.id),
            );
            groupIds.add(group.id);
            return { sourceId, groupIds: [...groupIds] };
          }),
        );
        await Promise.all([
          ...additionReplacements.map((replacement) =>
            updateSourceGroups({
              path: { sourceId: replacement.sourceId },
              body: { groupIds: replacement.groupIds },
            }),
          ),
          ...removals.map((sourceId) =>
            removeGroupSource({
              path: { groupId: group.id, sourceId },
            }),
          ),
        ]);
      },
    });

    const reset = useCallback(() => {
      setSelectedIds(new Set(baselineIds));
      setSearch("");
      setDropdownOpen(false);
      setError(null);
    }, [baselineIds]);

    const save = useCallback(async () => {
      if (!canManage || !dirty || saveAssociations.isPending) return true;
      setError(null);
      try {
        await saveAssociations.mutateAsync();
        setBaselineIds(new Set(selectedIds));
        return true;
      } catch (cause) {
        setError(
          cause instanceof Error && cause.message === associationAccessChanged
            ? "You can no longer change one of these Sources for this group. Refresh and try again."
            : groupMutationError(cause, "sources"),
        );
        return false;
      }
    }, [canManage, dirty, saveAssociations, selectedIds]);

    useEffect(
      () => onDraftChange(dirty, saveAssociations.isPending),
      [dirty, onDraftChange, saveAssociations.isPending],
    );
    useImperativeHandle(ref, () => ({ save, reset }), [reset, save]);

    const sources = associated.data?.items ?? [];
    const normalizedSearch = search.trim().toLocaleLowerCase();
    const candidates = (allSources.data ?? []).filter(
      (source) =>
        can(source, "edit") &&
        (!normalizedSearch || source.name.toLocaleLowerCase().includes(normalizedSearch)),
    );
    const unselectedCandidates = candidates.filter((source) => !selectedIds.has(source.id));
    const selectedSources = (allSources.data ?? []).filter((source) => selectedIds.has(source.id));
    const isLocked = (source: SourceSummary) =>
      baselineIds.has(source.id) && !removableIds.has(source.id);
    const lockReason = (source: SourceSummary) =>
      source.status === "DELETING"
        ? ui("This Source is being deleted.")
        : source.managerName
          ? ui("Only {{v1}}, the responsible manager, can remove this Source.", {
              v1: source.managerName,
            })
          : ui("Only administrators can remove this Source.");

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
      <section
        aria-labelledby="group-sources-heading"
        className="border-t border-border-subtle pt-7"
      >
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
                    <p
                      role="status"
                      className="px-4 py-6 text-center font-main-ui-body text-content-muted"
                    >
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
                        <ChipIcon
                          className="size-3.5 shrink-0 text-content-secondary"
                          aria-hidden="true"
                        />
                      ) : null}
                      <span className="truncate font-main-ui-action text-content-primary">
                        {source.name}
                      </span>
                      {isLocked(source) ? (
                        <span
                          className="ml-0.5 p-0.5 text-content-muted"
                          title={lockReason(source)}
                        >
                          <Lock className="size-3.5" aria-hidden="true" />
                          <span className="sr-only">{lockReason(source)}</span>
                        </span>
                      ) : (
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
                      )}
                    </span>
                  );
                })}
              </div>
            ) : (
              <div className="mt-3 rounded-xl border border-dashed border-border-default px-4 py-6 text-center font-secondary-body text-content-muted">
                {ui("No Sources are associated with this group.")}
              </div>
            )}

            {selectedSources.some(isLocked) ? (
              <p className="mt-2 font-secondary-body text-content-muted">
                {ui(
                  "Sources with a lock can't be removed from this group: they are being deleted, or only their responsible manager can remove them.",
                )}
              </p>
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
  },
);

function SourceIdentity({ source }: { source: SourceSummary }) {
  const ui = useAppTranslation();
  const ProviderIcon = findSourceProvider(source.type)?.icon;

  return (
    <span className="flex min-w-0 flex-1 items-center gap-3">
      {ProviderIcon ? (
        <ProviderIcon className="size-4 shrink-0 text-content-secondary" aria-hidden="true" />
      ) : null}
      <span className="min-w-0">
        <span className="block truncate font-main-ui-action text-content-primary">
          {source.name}
        </span>
        <span className="mt-0.5 block font-secondary-body text-content-muted">
          {source.type} · {source.documentCount.toLocaleString(uiLocale())} {ui("documents")}
        </span>
      </span>
    </span>
  );
}
