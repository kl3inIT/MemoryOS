import { keepPreviousData, useMutation, useQuery } from "@tanstack/react-query";
import { LoaderCircle, Search, ShieldCheck, UsersRound } from "lucide-react";
import { useMemo, useRef, useState, type RefObject } from "react";
import { Dialog } from "radix-ui";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  listGroupsOptions,
  replaceUserGroupsMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { UserListItem } from "@/lib/hey-api/types.gen";
import { membershipActionError } from "./user-action-errors";

export type UserGroupOption = {
  id: string;
  name: string;
  systemKey: "ADMIN" | "BASIC" | null;
};

type UserGroupsDialogProps = {
  entry: UserListItem;
  restoreFocusRef: RefObject<HTMLElement | null>;
  fallbackFocusRef?: RefObject<HTMLElement | null>;
  onOpenChange: (open: boolean) => void;
  onSaved: () => Promise<void>;
};

export function UserGroupsDialog({
  entry,
  restoreFocusRef,
  fallbackFocusRef,
  onOpenChange,
  onSaved,
}: UserGroupsDialogProps) {
  const replaceGroups = useMutation(replaceUserGroupsMutation());
  const initialIds = useMemo(
    () => entry.groups.filter((group) => group.systemKey === null).map((group) => group.id),
    [entry.groups],
  );
  const [selectedIds, setSelectedIds] = useState(() => new Set(initialIds));
  const [searchDraft, setSearchDraft] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const pendingRef = useRef(false);
  const actorId = entry.actorId;
  const label = entry.displayName?.trim() || entry.email?.trim() || "this user";
  const groupOptions = useQuery({
    ...listGroupsOptions({ query: { search: search || undefined, page, size: 20 } }),
    placeholderData: keepPreviousData,
    retry: false,
  });
  const normalizedSearch = search.toLocaleLowerCase();
  const matchingCurrentGroups = entry.groups.filter(
    (group) =>
      group.systemKey === null &&
      (!normalizedSearch || group.name.toLocaleLowerCase().includes(normalizedSearch)),
  );
  const ordinaryGroups = Array.from(
    new Map(
      [...(groupOptions.data?.items ?? []), ...matchingCurrentGroups]
        .filter((group) => group.systemKey === null)
        .map((group) => [group.id, group]),
    ).values(),
  ).sort((left, right) => left.name.localeCompare(right.name));
  const systemGroups = entry.groups.filter((group) => group.systemKey !== null);
  const initialKey = [...initialIds].sort().join("\u0000");
  const selectedKey = [...selectedIds].sort().join("\u0000");
  const dirty = initialKey !== selectedKey;

  async function save() {
    if (!actorId || !dirty || pendingRef.current) return;
    pendingRef.current = true;
    setError(null);
    try {
      await replaceGroups.mutateAsync({
        path: { actorId },
        headers: sameOriginMutationHeaders,
        body: { groupIds: [...selectedIds] },
      });
      onOpenChange(false);
      fallbackFocusRef?.current?.focus();
      await onSaved();
    } catch (cause) {
      setError(membershipActionError(cause));
    } finally {
      pendingRef.current = false;
    }
  }

  return (
    <Dialog.Root open onOpenChange={(open) => !pendingRef.current && onOpenChange(open)}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-content-primary/20 backdrop-blur-[2px] data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out data-[state=open]:fade-in motion-reduce:animate-none" />
        <Dialog.Content
          className="fixed top-1/2 left-1/2 z-50 flex max-h-[min(44rem,calc(100dvh-2rem))] w-[min(38rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 flex-col overflow-hidden rounded-2xl border border-border-default bg-surface-overlay shadow-md outline-none"
          onOpenAutoFocus={(event) => {
            event.preventDefault();
            searchRef.current?.focus();
          }}
          onCloseAutoFocus={(event) => {
            const target = restoreFocusRef.current?.isConnected
              ? restoreFocusRef.current
              : fallbackFocusRef?.current;
            if (!target?.isConnected) return;
            event.preventDefault();
            target.focus();
          }}
          onEscapeKeyDown={(event) => {
            if (pendingRef.current) event.preventDefault();
          }}
          onPointerDownOutside={(event) => {
            if (pendingRef.current) event.preventDefault();
          }}
        >
          <header className="border-b border-border-subtle px-5 py-5 sm:px-6">
            <Dialog.Title className="font-heading-h3 text-content-primary">
              Edit groups
            </Dialog.Title>
            <Dialog.Description className="mt-1 font-main-ui-body text-content-secondary">
              Choose ordinary group memberships for {label}. System memberships are preserved.
            </Dialog.Description>
          </header>

          <div className="min-h-0 flex-1 overflow-y-auto px-5 py-5 sm:px-6">
            {systemGroups.length > 0 ? (
              <section aria-labelledby="protected-user-groups">
                <h2
                  id="protected-user-groups"
                  className="font-secondary-action text-content-primary"
                >
                  System groups
                </h2>
                <div className="mt-2 divide-y divide-border-subtle overflow-hidden rounded-xl border border-border-subtle">
                  {systemGroups.map((group) => (
                    <div key={group.id} className="flex items-center gap-3 px-4 py-3">
                      <ShieldCheck
                        className="size-4 shrink-0 text-content-muted"
                        aria-hidden="true"
                      />
                      <span className="min-w-0 flex-1 truncate font-main-ui-body text-content-primary">
                        {group.name}
                      </span>
                      <span className="font-secondary-body text-content-muted">Protected</span>
                    </div>
                  ))}
                </div>
              </section>
            ) : null}

            <section
              aria-labelledby="ordinary-user-groups"
              className={systemGroups.length ? "mt-6" : ""}
            >
              <div className="flex items-center justify-between gap-3">
                <h2
                  id="ordinary-user-groups"
                  className="font-secondary-action text-content-primary"
                >
                  Ordinary groups
                </h2>
                <span className="font-secondary-body tabular-nums text-content-muted">
                  {selectedIds.size} selected
                </span>
              </div>
              <form
                role="search"
                className="mt-2 flex gap-2"
                onSubmit={(event) => {
                  event.preventDefault();
                  setSearch(searchDraft.trim());
                  setPage(0);
                }}
              >
                <label className="relative min-w-0 flex-1">
                  <span className="sr-only">Search ordinary groups</span>
                  <Search
                    className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-content-muted"
                    aria-hidden="true"
                  />
                  <Input
                    ref={searchRef}
                    type="search"
                    size="sm"
                    value={searchDraft}
                    maxLength={200}
                    placeholder="Search groups…"
                    className="bg-surface-sunken pl-9"
                    onChange={(event) => setSearchDraft(event.target.value)}
                  />
                </label>
                <Button type="submit" size="sm" prominence="secondary">
                  Search
                </Button>
              </form>

              {groupOptions.isPending ? (
                <p
                  role="status"
                  className="mt-5 flex items-center gap-2 font-main-ui-body text-content-muted"
                >
                  <LoaderCircle
                    className="size-4 animate-spin motion-reduce:animate-none"
                    aria-hidden="true"
                  />
                  Loading groups
                </p>
              ) : groupOptions.isError ? (
                <div className="mt-5 rounded-xl border border-border-subtle p-4">
                  <p role="alert" className="font-main-ui-body text-content-secondary">
                    Groups could not be loaded. Existing memberships have not been changed.
                  </p>
                  <Button
                    size="sm"
                    prominence="secondary"
                    className="mt-3"
                    onClick={() => void groupOptions.refetch()}
                  >
                    Try again
                  </Button>
                </div>
              ) : ordinaryGroups.length === 0 ? (
                <div className="mt-5 rounded-xl border border-dashed border-border-default px-4 py-8 text-center">
                  <UsersRound className="mx-auto size-5 text-content-muted" aria-hidden="true" />
                  <p className="mt-2 font-main-ui-body text-content-muted">
                    {search ? "No groups match your search." : "No ordinary groups are available."}
                  </p>
                </div>
              ) : (
                <div className="mt-3 divide-y divide-border-subtle overflow-hidden rounded-xl border border-border-subtle">
                  {ordinaryGroups.map((group) => {
                    const checked = selectedIds.has(group.id);
                    const limitReached = selectedIds.size >= 100 && !checked;
                    return (
                      <label
                        key={group.id}
                        className={`flex items-center gap-3 px-4 py-3 transition-colors has-[:focus-visible]:ring-3 has-[:focus-visible]:ring-inset has-[:focus-visible]:ring-focus-ring/30 ${limitReached ? "cursor-not-allowed text-content-disabled" : "cursor-pointer hover:bg-surface-subtle"}`}
                      >
                        <input
                          type="checkbox"
                          checked={checked}
                          disabled={limitReached}
                          className="size-4 shrink-0 accent-content-primary outline-none"
                          onChange={() => {
                            setSelectedIds((current) => {
                              const next = new Set(current);
                              if (checked) next.delete(group.id);
                              else next.add(group.id);
                              return next;
                            });
                          }}
                        />
                        <span className="min-w-0 flex-1 truncate font-main-ui-body text-content-primary">
                          {group.name}
                        </span>
                      </label>
                    );
                  })}
                </div>
              )}

              {groupOptions.data && groupOptions.data.totalPages > 1 ? (
                <nav
                  aria-label="User group option pages"
                  className="mt-3 flex items-center justify-end gap-2"
                >
                  <Button
                    size="sm"
                    prominence="secondary"
                    disabled={page === 0}
                    onClick={() => setPage(page - 1)}
                  >
                    Previous
                  </Button>
                  <span className="min-w-24 text-center font-secondary-body tabular-nums text-content-muted">
                    Page {page + 1} of {groupOptions.data.totalPages}
                  </span>
                  <Button
                    size="sm"
                    prominence="secondary"
                    disabled={page + 1 >= groupOptions.data.totalPages}
                    onClick={() => setPage(page + 1)}
                  >
                    Next
                  </Button>
                </nav>
              ) : null}
            </section>

            {error ? (
              <p
                role="alert"
                className="mt-4 rounded-lg bg-status-danger-surface px-4 py-3 font-secondary-body text-status-danger-content"
              >
                {error}
              </p>
            ) : null}
          </div>

          <footer className="flex flex-col-reverse gap-2 border-t border-border-subtle bg-surface-subtle px-5 py-4 sm:flex-row sm:justify-end sm:px-6">
            <Button
              prominence="secondary"
              disabled={replaceGroups.isPending}
              onClick={() => onOpenChange(false)}
            >
              Cancel
            </Button>
            <Button
              pending={replaceGroups.isPending}
              disabled={!dirty || !actorId}
              onClick={() => void save()}
            >
              {replaceGroups.isPending ? "Saving groups…" : "Save groups"}
            </Button>
          </footer>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
