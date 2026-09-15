import { useAppTranslation } from "@/i18n/use-app-translation";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronRight } from "lucide-react";
import { StatusBadge } from "@/components/ui/status-badge";
import { useId, useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { getGoogleDriveSelectionTreeQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import { getGoogleDriveSelectionTree } from "@/lib/hey-api/sdk.gen";
import type {
  GetGoogleDriveConfigurationResponse,
  GoogleDriveLinkOriginResponse,
  GoogleDriveSelectionItemResponse,
  GoogleDriveSelectionTreeItemResponse,
} from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { FileTypeIcon } from "./file-type-icon";
import { SelectionPager } from "./google-drive-selection-pager";
import {
  firstSelectionPage,
  nextSelectionPage,
  previousSelectionPage,
  type SelectionPaging,
} from "./google-drive-selection-paging";
import { isGoogleDriveRevisionConflict } from "./source-errors";

const pageSize = 25;
/** Deeper levels stop indenting so rows keep their width on narrow screens. */
const indentedDepth = 4;

type SelectionControls = {
  approved: ReadonlySet<string> | null;
  disabled: boolean;
  allowSelection: boolean;
  onApprove: (id: string, checked: boolean) => void;
  onSelect: (item: GoogleDriveSelectionItemResponse, control: HTMLElement) => void;
};
type TreeProps = SelectionControls & {
  sourceId: string;
  actorId: string;
  configuration: GetGoogleDriveConfigurationResponse;
  onRefresh: () => Promise<void>;
};
/**
 * Expanded nodes and branch pages, keyed by the node's path of ids. The tree keeps them so
 * paging a branch away and back restores what was open inside it.
 */
type TreeView = {
  expanded: ReadonlySet<string>;
  pages: ReadonlyMap<string, SelectionPaging>;
  onExpand: (path: string, expanded: boolean) => void;
  onPage: (path: string, paging: SelectionPaging) => void;
};
type BranchProps = TreeProps & { view: TreeView; ancestors: string[] };

class SelectionTreeChangedError extends Error {}

export function GoogleDriveSelectionTree(props: TreeProps) {
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set());
  const [pages, setPages] = useState<ReadonlyMap<string, SelectionPaging>>(() => new Map());
  const view = useMemo<TreeView>(
    () => ({
      expanded,
      pages,
      onExpand: (path, open) =>
        setExpanded((current) => {
          const next = new Set(current);
          if (open) next.add(path);
          else next.delete(path);
          return next;
        }),
      onPage: (path, paging) => setPages((current) => new Map(current).set(path, paging)),
    }),
    [expanded, pages],
  );
  return <SelectionBranch {...props} view={view} ancestors={[]} />;
}

function SelectionBranch({
  parent,
  ...props
}: BranchProps & { parent?: GoogleDriveSelectionTreeItemResponse }) {
  const ui = useAppTranslation();

  const client = useQueryClient();
  const { configuration, sourceId, actorId, view, ancestors } = props;
  // A branch's ancestors end with its parent, so they are the parent node's path.
  const path = ancestors.join("/");
  const paging = view.pages.get(path) ?? firstSelectionPage;
  const request = {
    path: { sourceId },
    query: { parentId: parent?.id, size: pageSize, cursor: paging.cursor },
  };
  const queryKey = [
    ...getGoogleDriveSelectionTreeQueryKey(request),
    actorId,
    configuration.revision,
    configuration.discoveryRevision,
    configuration.credentialRevision,
  ];
  const branch = useQuery({
    queryKey,
    queryFn: async ({ signal }) => {
      const { data } = await getGoogleDriveSelectionTree({
        ...request,
        signal,
        throwOnError: true,
      });
      signal.throwIfAborted();
      if (
        data.revision !== configuration.revision ||
        data.discoveryRevision !== configuration.discoveryRevision ||
        data.credentialRevision !== configuration.credentialRevision
      ) {
        throw new SelectionTreeChangedError("Selection authority changed");
      }
      return data;
    },
    // The shown page stays while the next one loads, so the pager keeps its place and focus.
    placeholderData: keepPreviousData,
    retry: false,
    staleTime: 60_000,
  });
  const changed =
    branch.error instanceof SelectionTreeChangedError ||
    isGoogleDriveRevisionConflict(branch.error);
  const page = branch.data;
  const items = page?.items ?? [];
  const label = parent ? `Contents of ${parent.name}` : "Selection items";
  return (
    <div className="min-w-0 space-y-1">
      {branch.isPending ? (
        <p role="status" className="py-2 text-sm text-content-muted">
          {parent?.kind === "FOLDER"
            ? ui("Loading folder contents…")
            : parent
              ? ui("Loading recorded links…")
              : ui("Loading selected content…")}
        </p>
      ) : null}
      {branch.isError ? (
        <div className="space-y-2 py-2">
          <p role="alert" className="text-sm text-status-danger-content">
            {changed
              ? ui(
                  "Selection or discovery changed. Refresh before expanding this content. Your draft is retained.",
                )
              : paging.previous.length
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
          aria-busy={branch.isPlaceholderData || undefined}
          className={cn(
            "min-w-0 space-y-px text-sm transition-opacity motion-reduce:transition-none",
            !parent && "border-y border-border-subtle py-1",
            branch.isPlaceholderData && "opacity-60",
          )}
        >
          {items.map((item) => (
            <SelectionTreeNode key={item.id} {...props} item={item} />
          ))}
        </ul>
      ) : null}
      {branch.isSuccess && !items.length ? (
        <p className="py-2 text-sm text-content-muted">
          {page?.nextCursor
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
      {!changed && (paging.previous.length || page?.nextCursor) ? (
        <SelectionPager
          paging={paging}
          count={items.length}
          hasNext={Boolean(page?.nextCursor)}
          busy={branch.isPlaceholderData}
          previousLabel={
            parent
              ? ui("Previous page in {{v1}}", { v1: parent.name })
              : ui("Previous page of selected content")
          }
          nextLabel={
            parent
              ? ui("Next page in {{v1}}", { v1: parent.name })
              : ui("Next page of selected content")
          }
          onPrevious={() => view.onPage(path, previousSelectionPage(paging))}
          onNext={() => {
            if (page?.nextCursor)
              view.onPage(path, nextSelectionPage(paging, page.nextCursor, items.length));
          }}
        />
      ) : null}
    </div>
  );
}

function SelectionTreeNode({
  item,
  ...props
}: BranchProps & { item: GoogleDriveSelectionTreeItemResponse }) {
  const ui = useAppTranslation();

  const { view, ancestors } = props;
  const path = [...ancestors, item.id].join("/");
  const branchId = useId();
  const repeated = ancestors.includes(item.id);
  const expandable = item.expandable && !repeated;
  const expanded = expandable && view.expanded.has(path);
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
          <GoogleDriveSelectionRow
            {...props}
            item={item}
            parentId={ancestors.at(-1)}
            showScopeBadge={ancestors.length === 0}
          />
          {repeated ? (
            <p className="pb-2 text-xs text-content-muted">
              {ui("Already shown earlier in this branch. Its sync selection is shared.")}
            </p>
          ) : null}
        </div>
      </div>
      {expanded ? (
        <div
          id={branchId}
          className={cn(
            "min-w-0 pb-1",
            ancestors.length < indentedDepth && "ml-4 border-l border-border-subtle pl-2",
          )}
        >
          <SelectionBranch {...props} parent={item} ancestors={[...ancestors, item.id]} />
        </div>
      ) : null}
    </li>
  );
}

export function GoogleDriveSelectionRow({
  item,
  parentId,
  showScopeBadge = true,
  approved,
  disabled,
  allowSelection,
  onApprove,
  onSelect,
}: SelectionControls & {
  item: GoogleDriveSelectionItemResponse;
  parentId?: string;
  /** Descendants of a selected folder are in scope by construction, so the badge only adds noise there. */
  showScopeBadge?: boolean;
}) {
  const ui = useAppTranslation();

  const included = item.coveredByRoots || (approved ? approved.has(item.id) : item.selected);
  const canSelect = allowSelection && item.kind === "LINKED" && !item.coveredByRoots;
  const origins = useMemo(
    () => (parentId ? item.origins.filter((origin) => origin.parentId === parentId) : item.origins),
    [item.origins, parentId],
  );
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
    <div className="flex min-w-0 items-center gap-2 py-1.5">
      <FileTypeIcon name={item.name} mimeType={item.mimeType} />
      <div className="min-w-0 flex-1">
        <div className="flex min-w-0 flex-wrap items-center justify-between gap-x-3 gap-y-1">
          <div className="flex min-w-0 flex-1 basis-36 items-center gap-2">
            <p className="min-w-0 break-words text-content-primary">{item.name}</p>
            {statusChip}
          </div>
          {canSelect ? (
            <div className="min-w-0" data-selection-control={item.id}>
              {approved ? (
                <label className="flex min-h-11 items-center gap-2 text-xs">
                  <Checkbox
                    aria-label={ui("Sync {{v1}}", { v1: item.name })}
                    checked={included}
                    disabled={disabled || (!included && item.status !== "AVAILABLE")}
                    onCheckedChange={(event) => onApprove(item.id, event === true)}
                  />
                  {ui("Select for sync")}
                </label>
              ) : (
                <Button
                  prominence="secondary"
                  className="h-auto min-h-11 max-w-full whitespace-normal text-left"
                  disabled={disabled || (!included && item.status !== "AVAILABLE")}
                  aria-label={ui("{{v1}} {{v2}} for sync", {
                    v1: ui(included ? "Deselect" : "Select"),
                    v2: item.name,
                  })}
                  onClick={(event) => onSelect(item, event.currentTarget.parentElement!)}
                >
                  {included ? ui("Deselect for sync") : ui("Select for sync")}
                </Button>
              )}
            </div>
          ) : null}
        </div>
        {origins.length ? <SelectionProvenance origins={origins} name={item.name} /> : null}
      </div>
    </div>
  );
}

function SelectionProvenance({
  origins,
  name,
}: {
  origins: GoogleDriveLinkOriginResponse[];
  name: string;
}) {
  const ui = useAppTranslation();

  const { parents, referenceCount } = useMemo(() => {
    const grouped = new Map<string, { name: string; locations: Set<string> }>();
    let referenceCount = 0;
    for (const origin of origins) {
      let parent = grouped.get(origin.parentId);
      if (!parent) {
        parent = { name: origin.parentName, locations: new Set() };
        grouped.set(origin.parentId, parent);
      }
      if (!parent.locations.has(origin.location)) {
        parent.locations.add(origin.location);
        referenceCount++;
      }
    }
    return { parents: [...grouped], referenceCount };
  }, [origins]);
  return (
    <Collapsible aria-label={ui("References for {{v1}}", { v1: name })} className="min-w-0">
      <CollapsibleTrigger className="min-h-11 cursor-pointer py-2 text-xs text-content-muted focus-visible:outline-2 focus-visible:outline-focus-ring">
        {referenceCount} {referenceCount === 1 ? ui("reference") : ui("references")} ·{" "}
        {parents.length} {parents.length === 1 ? ui("source document") : ui("source documents")}
      </CollapsibleTrigger>
      <CollapsibleContent>
        <ul className="space-y-2 border-l border-border-subtle pl-3 pb-2 text-xs text-content-muted">
          {parents.map(([parentId, parent]) => (
            <li key={parentId} className="min-w-0 space-y-1">
              <p className="break-words font-medium text-content-primary">{parent.name}</p>
              <ul aria-label={ui("Reference locations")} className="flex flex-wrap gap-x-3 gap-y-1">
                {[...parent.locations].map((location) => (
                  <li key={location} className="break-all">
                    {location || ui("Location not recorded")}
                  </li>
                ))}
              </ul>
            </li>
          ))}
        </ul>
      </CollapsibleContent>
    </Collapsible>
  );
}
