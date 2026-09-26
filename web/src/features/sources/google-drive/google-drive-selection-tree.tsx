import { useAppTranslation } from "@/i18n/use-app-translation";
import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, ChevronsDown } from "lucide-react";
import { StatusBadge } from "@/components/ui/status-badge";
import { memo, useCallback, useEffect, useId, useMemo, useRef, useSyncExternalStore } from "react";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { getGoogleDriveSelectionTreeInfiniteOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type {
  GetGoogleDriveConfigurationResponse,
  GoogleDriveSelectionItemResponse,
  GoogleDriveSelectionTreeItemResponse,
  GoogleDriveSelectionTreeResponse,
} from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { FileTypeIcon } from "@/features/sources/shared/file-type-icon";
import { isGoogleDriveRevisionConflict } from "@/features/sources/shared/source-errors";

/**
 * Drive is listed in one call whatever the page size, so a wider page means fewer of the round trips
 * that make expanding slow. It stays at the contract's maximum ceiling of 100.
 */
const pageSize = 50;
/** Branch pages stay usable this long, so collapsing and reopening a folder costs no Drive call. */
const branchFreshness = 10 * 60_000;
/** Levels past this one keep indenting with a narrower step, so deep chains stay readable without crowding narrow screens. */
const wideIndentDepth = 4;
/** Expanding everything loads one page per branch, so one press opens at most this many of them. */
const cascadeLimit = 50;

type SelectionControls = {
  approved: ReadonlySet<string> | null;
  disabled: boolean;
  allowSelection: boolean;
  onApprove: (id: string, checked: boolean) => void;
  onApproveBranch: (parentId: string, checked: boolean) => void;
  onSelect: (item: GoogleDriveSelectionItemResponse, control: HTMLElement) => void;
};
type TreeProps = SelectionControls & {
  sourceId: string;
  configuration: GetGoogleDriveConfigurationResponse;
  onRefresh: () => Promise<void>;
};
/** A node is open when bit 1 is set and still cascading when bit 2 is set. */
const openBit = 1;
const cascadeBit = 2;

/**
 * Expanded nodes, keyed by the node's path of ids, so reopening a branch restores what was open
 * inside it. Nodes subscribe to their own path, so opening one branch of a large tree re-renders
 * that branch instead of every node on screen.
 */
type TreeView = {
  subscribe: (path: string, listener: () => void) => () => void;
  stateOf: (path: string) => number;
  onExpand: (path: string, expanded: boolean) => void;
  onExpandAll: (path: string) => void;
  onCascade: (path: string, childPaths: string[]) => void;
};
type BranchProps = TreeProps & { view: TreeView; ancestors: string[] };

function useTreeView(): TreeView {
  const store = useRef({
    expanded: new Set<string>(),
    cascading: new Set<string>(),
    listeners: new Map<string, Set<() => void>>(),
    budget: cascadeLimit,
  });
  return useMemo(() => {
    const notify = (path: string) => {
      for (const listener of store.current.listeners.get(path) ?? []) listener();
    };
    return {
      subscribe: (path, listener) => {
        const listeners = store.current.listeners.get(path) ?? new Set();
        listeners.add(listener);
        store.current.listeners.set(path, listeners);
        return () => {
          listeners.delete(listener);
          if (!listeners.size) store.current.listeners.delete(path);
        };
      },
      stateOf: (path) =>
        (store.current.expanded.has(path) ? openBit : 0) |
        (store.current.cascading.has(path) ? cascadeBit : 0),
      onExpand: (path, open) => {
        const { expanded, cascading } = store.current;
        if (open) expanded.add(path);
        else {
          expanded.delete(path);
          // Collapsing a branch also stops the cascade inside it.
          for (const kept of [...cascading])
            if (kept === path || kept.startsWith(`${path}/`)) cascading.delete(kept);
        }
        notify(path);
      },
      onExpandAll: (path) => {
        store.current.budget = cascadeLimit;
        store.current.expanded.add(path);
        store.current.cascading.add(path);
        notify(path);
      },
      // Cascading is consumed once a branch has opened its children, so collapsing afterwards sticks.
      onCascade: (path, childPaths) => {
        const state = store.current;
        state.cascading.delete(path);
        for (const child of childPaths) {
          state.expanded.add(child);
          if (state.budget > 0) {
            state.cascading.add(child);
            state.budget -= 1;
          }
        }
        notify(path);
        for (const child of childPaths) notify(child);
      },
    };
  }, []);
}

/**
 * One branch's page query, shared by the branch that renders it and by the hover prefetch that warms
 * it: expanding a folder costs a Google Drive round trip, so a branch read once is not read again
 * until the selection changes and the panel invalidates it.
 */
function branchQuery(sourceId: string, parentId?: string) {
  return {
    ...getGoogleDriveSelectionTreeInfiniteOptions({
      path: { sourceId },
      query: { parentId, size: pageSize },
    }),
    initialPageParam: { path: { sourceId }, query: {} },
    // Loaded pages stay on screen, so a long folder grows instead of replacing what was read.
    getNextPageParam: (last: GoogleDriveSelectionTreeResponse) => last.nextCursor ?? undefined,
    retry: false,
    // A branch stays fresh for the length of a review session, so collapsing and reopening is free.
    staleTime: branchFreshness,
    gcTime: branchFreshness,
  };
}

/** Whether a page was read under the selection, discovery and credential the panel shows. */
function readUnder(
  page: GoogleDriveSelectionTreeResponse,
  configuration: GetGoogleDriveConfigurationResponse,
) {
  return (
    page.revision === configuration.revision &&
    page.discoveryRevision === configuration.discoveryRevision &&
    page.credentialRevision === configuration.credentialRevision
  );
}

export function GoogleDriveSelectionTree(props: TreeProps) {
  const view = useTreeView();
  return (
    <TooltipProvider>
      <SelectionBranch {...props} view={view} ancestors={[]} />
    </TooltipProvider>
  );
}

/** Re-renders only the node whose path changed, which keeps large trees responsive. */
function useNodeState(view: TreeView, path: string) {
  const subscribe = useCallback(
    (listener: () => void) => view.subscribe(path, listener),
    [path, view],
  );
  return useSyncExternalStore(
    subscribe,
    useCallback(() => view.stateOf(path), [path, view]),
  );
}

function SelectionBranch({
  parent,
  ...props
}: BranchProps & { parent?: GoogleDriveSelectionTreeItemResponse }) {
  const ui = useAppTranslation();

  const client = useQueryClient();
  const { view, ancestors } = props;
  // A branch's ancestors end with its parent, so they are the parent node's path.
  const path = ancestors.join("/");
  const options = branchQuery(props.sourceId, parent?.id);
  const queryKey = options.queryKey;
  const branch = useInfiniteQuery(options);
  // Pages read under earlier revisions are read again after a change; until then they are not shown.
  const outdated =
    branch.data?.pages.some((page) => !readUnder(page, props.configuration)) ?? false;
  const reading = branch.isPending || (outdated && branch.isFetching);
  const changed = isGoogleDriveRevisionConflict(branch.error) || (outdated && !branch.isFetching);
  const failed = changed || branch.isError;
  const items = useMemo(
    () => (outdated ? [] : (branch.data?.pages.flatMap((page) => page.items) ?? [])),
    [branch.data, outdated],
  );
  const label = parent ? `Contents of ${parent.name}` : "Selection items";
  // A node expanded with "expand everything" keeps opening the children of each page it loads.
  const cascading = (useNodeState(view, path) & cascadeBit) !== 0;
  const childPaths = useMemo(
    () =>
      items
        .filter((item) => item.expandable && !ancestors.includes(item.id))
        .map((item) => (path ? `${path}/${item.id}` : item.id)),
    [ancestors, items, path],
  );
  const { onCascade } = view;
  useEffect(() => {
    if (cascading && childPaths.length) onCascade(path, childPaths);
  }, [cascading, childPaths, onCascade, path]);
  return (
    <div className="flex min-w-0 flex-col gap-1">
      {reading ? (
        <p role="status" className="py-2 text-sm text-content-muted">
          {parent?.kind === "FOLDER"
            ? ui("Loading folder contents…")
            : parent
              ? ui("Loading recorded links…")
              : ui("Loading selected content…")}
        </p>
      ) : null}
      {failed && !reading ? (
        <div className="flex flex-col items-start gap-2 py-2">
          <p role="alert" className="text-sm text-status-danger-content">
            {changed
              ? ui(
                  "Selection or discovery changed. Refresh before expanding this content. Your draft is retained.",
                )
              : items.length
                ? ui("This page could not be loaded. Your draft is retained.")
                : ui(
                    "This content could not be loaded. It is not an empty folder or a completed discovery.",
                  )}
          </p>
          <Button
            prominence="secondary"
            disabled={branch.isFetching}
            onClick={() => {
              if (changed) {
                void props.onRefresh().then(() => client.resetQueries({ queryKey, exact: true }));
              } else void branch.refetch();
            }}
          >
            {changed ? ui("Refresh selected content") : ui("Retry loading content")}
          </Button>
        </div>
      ) : null}
      {!changed && items.length ? (
        <ul
          aria-label={label}
          aria-busy={branch.isFetchingNextPage || undefined}
          className={cn(
            "flex min-w-0 flex-col gap-px text-sm",
            !parent && "border-y border-border-subtle py-1",
          )}
        >
          {items.map((item) => (
            <SelectionTreeNode key={item.id} {...props} item={item} />
          ))}
        </ul>
      ) : null}
      {branch.isSuccess && !outdated && !items.length ? (
        <p className="py-2 text-sm text-content-muted">
          {branch.hasNextPage
            ? ui("No items were returned on this page. More pages are available.")
            : parent?.kind === "FOLDER"
              ? ui("No accessible items are currently returned for this folder.")
              : parent
                ? ui(
                    "No discovered links are recorded for this file. It may not have been checked, or discovery may be incomplete.",
                  )
                : ui("No selected content is available in this scope.")}
        </p>
      ) : null}
      {!changed && branch.hasNextPage ? (
        <Button
          prominence="tertiary"
          className="h-auto w-full justify-start gap-1 py-2 text-content-secondary"
          disabled={branch.isFetchingNextPage}
          aria-label={
            parent
              ? ui("Load more items in {{v1}}", { v1: parent.name })
              : ui("Load more selected content")
          }
          onClick={() => void branch.fetchNextPage()}
        >
          <ChevronDown aria-hidden="true" />
          {/* Drive reports that more pages exist, never how many items are left, so the label promises no count. */}
          {branch.isFetchingNextPage ? ui("Loading…") : ui("Load more")}
        </Button>
      ) : null}
    </div>
  );
}

const SelectionTreeNode = memo(function SelectionTreeNode({
  item,
  ...props
}: BranchProps & { item: GoogleDriveSelectionTreeItemResponse }) {
  const ui = useAppTranslation();

  const { view, ancestors } = props;
  const path = useMemo(() => [...ancestors, item.id].join("/"), [ancestors, item.id]);
  const branchId = useId();
  const repeated = ancestors.includes(item.id);
  const expandable = item.expandable && !repeated;
  const nodeState = useNodeState(view, path);
  const branchAncestors = useMemo(() => [...ancestors, item.id], [ancestors, item.id]);
  const expanded = expandable && (nodeState & openBit) !== 0;
  const client = useQueryClient();
  // Reaching for the control is a reliable signal, so the folder is read while the pointer travels.
  const warm = useCallback(() => {
    if (!expandable || expanded) return;
    void client.prefetchInfiniteQuery(branchQuery(props.sourceId, item.id));
  }, [client, expandable, expanded, item.id, props]);
  const branchControl = expandable && item.kind === "FILE" && props.allowSelection;
  return (
    <li className="min-w-0">
      <div className="flex min-w-0 items-center gap-1 rounded-md pr-1 hover:bg-surface-subtle">
        {expandable ? (
          <Button
            prominence="tertiary"
            className="size-8 shrink-0 p-0"
            aria-label={ui("{{v1}} {{v2}}", {
              v1: ui(expanded ? "Collapse" : "Expand"),
              v2: item.name,
            })}
            aria-expanded={expanded}
            aria-controls={expanded ? branchId : undefined}
            onPointerEnter={warm}
            onFocus={warm}
            onClick={() => view.onExpand(path, !expanded)}
          >
            <ChevronRight
              aria-hidden="true"
              className={cn(
                "transition-transform motion-reduce:transition-none",
                expanded && "rotate-90",
              )}
            />
          </Button>
        ) : (
          <span aria-hidden="true" className="w-8 shrink-0" />
        )}
        <div className="min-w-0 flex-1">
          <GoogleDriveSelectionRow {...props} item={item} showScopeBadge={ancestors.length === 0} />
          {repeated ? (
            <p className="pb-2 text-xs text-content-muted">
              {ui("Already shown earlier in this branch. Its sync selection is shared.")}
            </p>
          ) : null}
        </div>
        {expandable ? (
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                prominence="tertiary"
                className="size-8 shrink-0 p-0 text-content-muted"
                aria-label={ui("Expand everything in {{v1}}", { v1: item.name })}
                onClick={() => view.onExpandAll(path)}
              >
                <ChevronsDown aria-hidden="true" />
              </Button>
            </TooltipTrigger>
            <TooltipContent>{ui("Expand everything")}</TooltipContent>
          </Tooltip>
        ) : null}
        {branchControl ? <BranchSelectionControl {...props} item={item} /> : null}
      </div>
      {expanded ? (
        <div
          id={branchId}
          className={cn(
            "min-w-0 border-l border-border-subtle pb-1",
            ancestors.length < wideIndentDepth ? "ml-4 pl-2" : "ml-1 pl-1",
          )}
        >
          <SelectionBranch {...props} parent={item} ancestors={branchAncestors} />
        </div>
      ) : null}
    </li>
  );
});

export function GoogleDriveSelectionRow({
  item,
  showScopeBadge = true,
  approved,
  disabled,
  allowSelection,
  onApprove,
  onSelect,
}: SelectionControls & {
  item: GoogleDriveSelectionItemResponse;
  /** Descendants of a selected folder are in scope by construction, so the badge only adds noise there. */
  showScopeBadge?: boolean;
}) {
  const ui = useAppTranslation();

  const included = item.coveredByRoots || (approved ? approved.has(item.id) : item.selected);
  const canSelect = allowSelection && item.kind === "LINKED" && !item.coveredByRoots;
  const statusChip =
    item.status !== "AVAILABLE" ? (
      <StatusBadge tone="warning">
        {item.status === "UNSUPPORTED" ? ui("Unsupported") : ui("Unavailable")}
      </StatusBadge>
    ) : item.coveredByRoots ? (
      showScopeBadge ? (
        <StatusBadge tone="neutral">{ui("In scope")}</StatusBadge>
      ) : null
    ) : item.kind === "LINKED" ? (
      <StatusBadge tone="neutral">{ui("Linked")}</StatusBadge>
    ) : null;
  return (
    <div className="flex min-w-0 items-center gap-2 py-1">
      {canSelect ? (
        <div className="shrink-0" data-selection-control={item.id}>
          <Checkbox
            aria-label={ui("Sync {{v1}}", { v1: item.name })}
            checked={included}
            disabled={disabled || (!included && item.status !== "AVAILABLE")}
            onClick={(event) => {
              // Before a draft exists, checking loads the complete draft and approves this
              // target; the checkbox itself stays unchecked until the draft arrives.
              if (!approved)
                onSelect(
                  item,
                  event.currentTarget.closest<HTMLElement>("[data-selection-control]")!,
                );
            }}
            onCheckedChange={(event) => {
              if (approved) onApprove(item.id, event === true);
            }}
          />
        </div>
      ) : item.kind === "LINKED" ? (
        // Keep linked rows aligned with their selectable siblings.
        <span aria-hidden="true" className="w-4 shrink-0" />
      ) : null}
      <FileTypeIcon name={item.name} mimeType={item.mimeType} />
      <div className="min-w-0 flex-1">
        <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
          <p className="min-w-0 break-words text-content-primary">{item.name}</p>
          {statusChip}
        </div>
      </div>
    </div>
  );
}

function BranchSelectionControl({
  item,
  approved,
  disabled,
  onApproveBranch,
  ...props
}: BranchProps & { item: GoogleDriveSelectionTreeItemResponse }) {
  const ui = useAppTranslation();
  // enabled: false subscribes to the branch cache without fetching; the row only reflects children
  // the user already expanded or warmed.
  const branch = useInfiniteQuery({ ...branchQuery(props.sourceId, item.id), enabled: false });
  const children = branch.data?.pages.flatMap((page) => page.items) ?? [];
  const linked = children.filter((child) => child.kind === "LINKED" && !child.coveredByRoots);
  const selected = linked.filter((child) => (approved ? approved.has(child.id) : child.selected));
  const checked =
    linked.length === 0 || selected.length === 0
      ? false
      : selected.length >= linked.length
        ? true
        : "indeterminate";
  return (
    <label className="flex min-h-8 cursor-pointer items-center gap-2 text-xs">
      <Checkbox
        aria-label={ui("Sync all links in {{v1}}", { v1: item.name })}
        checked={checked}
        disabled={disabled || linked.length === 0}
        onCheckedChange={(event) => onApproveBranch(item.id, event === true)}
      />
      {ui("Select for sync")}
    </label>
  );
}
