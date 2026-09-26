import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { Link } from "@tanstack/react-router";
import { Lock, Search, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Alert, AlertAction, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Empty, EmptyDescription } from "@/components/ui/empty";
import { IconButton } from "@/components/ui/icon-button";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { Spinner } from "@/components/ui/spinner";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { findSourceProvider } from "@/features/sources/shared/source-provider-catalog";
import { can } from "@/lib/resource-permissions";
import type { GroupSourcesDraft } from "./group-sources-draft";

/** The Group's Source associations; edits stay in the page's draft until Save Changes. */
export function GroupSourcesSection({ draft }: { draft: GroupSourcesDraft }) {
  const ui = useAppTranslation();

  const { associated, allSources, canManage, canOpenSources, selectedIds, error } = draft;
  const [search, setSearch] = useState("");
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const [generation, setGeneration] = useState(draft.generation);
  if (generation !== draft.generation) {
    setGeneration(draft.generation);
    setSearch("");
    setDropdownOpen(false);
  }

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
    draft.savedIds.has(source.id) && !draft.removableIds.has(source.id);
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
  if (!draft.ordinaryGroup || (!canOpenSources && !canManage)) return null;

  return (
    <section
      aria-labelledby="group-sources-heading"
      className="flex flex-col gap-4 border-t border-border-subtle pt-7"
    >
      <h2 id="group-sources-heading" className="font-heading-h3 text-content-primary">
        {ui("Sources")}
      </h2>

      {error ? (
        <Alert variant="destructive">
          <AlertDescription>{ui(error)}</AlertDescription>
        </Alert>
      ) : null}

      {associated.isPending ? (
        <p
          role="status"
          className="flex items-center gap-2 py-6 font-main-ui-body text-content-muted"
        >
          <Spinner aria-hidden="true" />
          {ui("Loading associated Sources")}
        </p>
      ) : associated.isError ? (
        <Alert variant="destructive">
          <AlertDescription>{ui("Source associations could not be loaded.")}</AlertDescription>
          <AlertAction>
            <Button size="sm" prominence="secondary" onClick={() => void associated.refetch()}>
              {ui("Try again")}
            </Button>
          </AlertAction>
        </Alert>
      ) : canManage ? (
        <div className="flex flex-col gap-3">
          <div className="relative">
            <InputGroup>
              <InputGroupAddon>
                <Search aria-hidden="true" />
              </InputGroupAddon>
              <InputGroupInput
                type="search"
                value={search}
                placeholder={ui("Search Sources…")}
                aria-label={ui("Search Sources")}
                onChange={(event) => {
                  setSearch(event.target.value);
                  setDropdownOpen(true);
                }}
                onFocus={() => setDropdownOpen(true)}
              />
            </InputGroup>

            {dropdownOpen ? (
              <div
                ref={dropdownRef}
                className="absolute z-50 mt-1 max-h-72 w-full overflow-y-auto rounded-xl border border-border-subtle bg-surface-sunken shadow-md"
              >
                {allSources.isPending ? (
                  <p
                    role="status"
                    className="flex items-center justify-center gap-2 px-4 py-6 font-main-ui-body text-content-muted"
                  >
                    <Spinner aria-hidden="true" />
                    {ui("Loading Sources")}
                  </p>
                ) : allSources.isError ? (
                  <div className="p-2">
                    <Alert variant="destructive">
                      <AlertDescription>
                        {ui("Source choices could not be loaded.")}
                      </AlertDescription>
                      <AlertAction>
                        <Button
                          size="sm"
                          prominence="secondary"
                          onClick={() => void allSources.refetch()}
                        >
                          {ui("Try again")}
                        </Button>
                      </AlertAction>
                    </Alert>
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
                        draft.add(source.id);
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
            <div className="flex flex-wrap gap-1.5">
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
                      <span className="ml-0.5 p-0.5 text-content-muted" title={lockReason(source)}>
                        <Lock className="size-3.5" aria-hidden="true" />
                        <span className="sr-only">{lockReason(source)}</span>
                      </span>
                    ) : (
                      <IconButton
                        size="sm"
                        prominence="internal"
                        aria-label={ui("Remove {{v1}}", { v1: source.name })}
                        disabled={draft.pending}
                        onClick={() => draft.remove(source.id)}
                      >
                        <X />
                      </IconButton>
                    )}
                  </span>
                );
              })}
            </div>
          ) : (
            <Empty>
              <EmptyDescription>
                {ui("No Sources are associated with this group.")}
              </EmptyDescription>
            </Empty>
          )}

          {selectedSources.some(isLocked) ? (
            <p className="font-secondary-body text-content-muted">
              {ui(
                "Sources with a lock can't be removed from this group: they are being deleted, or only their responsible manager can remove them.",
              )}
            </p>
          ) : null}
        </div>
      ) : sources.length === 0 ? (
        <Empty>
          <EmptyDescription>{ui("No Sources are associated with this group.")}</EmptyDescription>
        </Empty>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
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
