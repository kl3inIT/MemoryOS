import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronDown, ChevronRight } from "lucide-react";
import { useId, useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { getGoogleDriveSelectionTreeQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import { getGoogleDriveSelectionTree } from "@/lib/hey-api/sdk.gen";
import type {
  GetGoogleDriveConfigurationResponse,
  GoogleDriveLinkOriginResponse,
  GoogleDriveSelectionItemResponse,
  GoogleDriveSelectionTreeItemResponse,
} from "@/lib/hey-api/types.gen";
import { GoogleDriveMimeIcon } from "./google-drive-links";
import { isGoogleDriveRevisionConflict } from "./source-errors";

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

class SelectionTreeChangedError extends Error {}

export function GoogleDriveSelectionTree(props: TreeProps) {
  return <SelectionBranch {...props} ancestors={[]} />;
}

function SelectionBranch({
  parent,
  ancestors,
  ...props
}: TreeProps & {
  parent?: GoogleDriveSelectionTreeItemResponse;
  ancestors: string[];
}) {
  const client = useQueryClient();
  const { configuration, sourceId, actorId } = props;
  const request = { path: { sourceId }, query: { parentId: parent?.id, size: 25 } };
  const queryKey = [
    ...getGoogleDriveSelectionTreeQueryKey(request),
    actorId,
    configuration.revision,
    configuration.discoveryRevision,
    configuration.credentialRevision,
  ];
  const branch = useInfiniteQuery({
    queryKey,
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ signal, pageParam }) => {
      const { data } = await getGoogleDriveSelectionTree({
        ...request,
        query: { ...request.query, cursor: pageParam },
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
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    retry: false,
    staleTime: 60_000,
  });
  const changed =
    branch.error instanceof SelectionTreeChangedError ||
    isGoogleDriveRevisionConflict(branch.error);
  const items = useMemo(() => {
    const unique = new Map<string, GoogleDriveSelectionTreeItemResponse>();
    for (const page of branch.data?.pages ?? []) {
      for (const item of page.items) unique.set(item.id, item);
    }
    return [...unique.values()];
  }, [branch.data]);
  const label = parent ? `Contents of ${parent.name}` : "Selection items";
  return (
    <div className="min-w-0 space-y-2">
      {branch.isPending ? (
        <p role="status" className="py-2 text-sm text-content-muted">
          {parent?.kind === "FOLDER"
            ? "Loading folder contents…"
            : parent
              ? "Loading recorded links…"
              : "Loading selected content…"}
        </p>
      ) : null}
      {branch.isError ? (
        <div className="space-y-2 py-2">
          <p role="alert" className="text-sm text-status-danger-content">
            {changed
              ? "Selection or discovery changed. Refresh before expanding this content. Your draft is retained."
              : branch.isFetchNextPageError
                ? "More content could not be loaded. The items already shown and your draft are retained."
                : "This content could not be loaded. It is not an empty folder or a completed discovery."}
          </p>
          <Button
            prominence="secondary"
            disabled={branch.isFetching}
            onClick={() => {
              if (changed) {
                void props.onRefresh().then(() => client.resetQueries({ queryKey, exact: true }));
              } else if (branch.isFetchNextPageError) void branch.fetchNextPage();
              else void branch.refetch();
            }}
          >
            {changed ? "Refresh selected content" : "Retry loading content"}
          </Button>
        </div>
      ) : null}
      {!changed && items.length ? (
        <ul
          aria-label={label}
          className="min-w-0 divide-y divide-border-subtle border-y border-border-subtle text-sm"
        >
          {items.map((item) => (
            <SelectionTreeNode key={item.id} {...props} item={item} ancestors={ancestors} />
          ))}
        </ul>
      ) : null}
      {branch.isSuccess && !items.length ? (
        <p className="py-2 text-sm text-content-muted">
          {branch.hasNextPage
            ? "No items were returned on this page. More pages are available."
            : parent?.kind === "FOLDER"
              ? "No accessible items are currently returned for this folder."
              : parent
                ? "No discovered links are recorded for this file. It may not have been checked, or discovery may be incomplete."
                : "No selected content is available in this scope."}
        </p>
      ) : null}
      {!changed && branch.hasNextPage ? (
        <Button
          prominence="secondary"
          pending={branch.isFetchingNextPage}
          disabled={branch.isFetching}
          aria-label={parent ? `Load more in ${parent.name}` : "Load more selected content"}
          onClick={() => void branch.fetchNextPage()}
        >
          Load more
        </Button>
      ) : null}
    </div>
  );
}

function SelectionTreeNode({
  item,
  ancestors,
  ...props
}: TreeProps & { item: GoogleDriveSelectionTreeItemResponse; ancestors: string[] }) {
  const [expanded, setExpanded] = useState(false);
  const branchId = useId();
  const repeated = ancestors.includes(item.id);
  const expandable = item.expandable && !repeated;
  return (
    <li className="min-w-0">
      <div className="flex min-w-0 items-start gap-1">
        {expandable ? (
          <Button
            prominence="tertiary"
            className="mt-1 size-11 shrink-0 p-0"
            aria-label={`${expanded ? "Collapse" : "Expand"} ${item.name}`}
            aria-expanded={expanded}
            aria-controls={expanded ? branchId : undefined}
            onClick={() => setExpanded(!expanded)}
          >
            {expanded ? <ChevronDown aria-hidden="true" /> : <ChevronRight aria-hidden="true" />}
          </Button>
        ) : (
          <span aria-hidden="true" className="w-11 shrink-0" />
        )}
        <div className="min-w-0 flex-1">
          <GoogleDriveSelectionRow {...props} item={item} parentId={ancestors.at(-1)} />
          {repeated ? (
            <p className="pb-2 text-xs text-content-muted">
              Already shown earlier in this branch. Its sync selection is shared.
            </p>
          ) : null}
        </div>
      </div>
      {expanded && expandable ? (
        <div
          id={branchId}
          className="min-w-0 border-l border-border-subtle pb-2"
          style={{
            marginLeft: ancestors.length < 3 ? 12 : 0,
            paddingLeft: ancestors.length < 3 ? 8 : 0,
          }}
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
  approved,
  disabled,
  allowSelection,
  onApprove,
  onSelect,
}: SelectionControls & { item: GoogleDriveSelectionItemResponse; parentId?: string }) {
  const included = item.coveredByRoots || (approved ? approved.has(item.id) : item.selected);
  const canSelect = allowSelection && item.kind === "LINKED" && !item.coveredByRoots;
  const origins = useMemo(
    () => (parentId ? item.origins.filter((origin) => origin.parentId === parentId) : item.origins),
    [item.origins, parentId],
  );
  return (
    <div className="flex min-w-0 items-start gap-2 py-2">
      <GoogleDriveMimeIcon mimeType={item.mimeType} />
      <div className="min-w-0 flex-1 space-y-1">
        <div className="flex min-w-0 flex-wrap items-start justify-between gap-x-3 gap-y-1">
          <div className="min-w-0 flex-1 basis-36">
            <p className="break-words text-content-primary">{item.name}</p>
            <p className="break-words text-xs text-content-muted">
              {item.kind === "FOLDER"
                ? "Folder · Contents included"
                : item.kind === "FILE"
                  ? item.coveredByRoots
                    ? "File · Included in scope"
                    : "File · Selected directly"
                  : item.coveredByRoots
                    ? "Linked document · Included in selected scope"
                    : included
                      ? approved
                        ? "Linked document · Selected in draft"
                        : "Linked document · Selected for sync"
                      : approved
                        ? "Linked document · Not selected in draft"
                        : "Linked document · Not selected for sync"}
              {item.status !== "AVAILABLE" ? ` · ${item.status.toLowerCase()}` : ""}
            </p>
          </div>
          {canSelect ? (
            <div className="min-w-0">
              {approved ? (
                <label className="flex min-h-11 items-center gap-2 text-xs">
                  <input
                    type="checkbox"
                    aria-label={`Sync ${item.name}`}
                    checked={included}
                    disabled={disabled || (!included && item.status !== "AVAILABLE")}
                    className="size-4 accent-primary focus-visible:ring-3 focus-visible:ring-focus-ring"
                    onChange={(event) => onApprove(item.id, event.target.checked)}
                  />
                  Select for sync
                </label>
              ) : (
                <Button
                  prominence="secondary"
                  className="h-auto min-h-11 max-w-full whitespace-normal text-left"
                  disabled={disabled || (!included && item.status !== "AVAILABLE")}
                  aria-label={`${included ? "Deselect" : "Select"} ${item.name} for sync`}
                  onClick={(event) => onSelect(item, event.currentTarget.parentElement!)}
                >
                  {included ? "Deselect for sync" : "Select for sync"}
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
    <details aria-label={`References for ${name}`} className="min-w-0">
      <summary className="min-h-11 cursor-pointer py-3 text-xs text-content-muted focus-visible:outline-2 focus-visible:outline-focus-ring">
        {referenceCount} {referenceCount === 1 ? "reference" : "references"} · {parents.length}{" "}
        {parents.length === 1 ? "source document" : "source documents"}
      </summary>
      <ul className="space-y-2 border-l border-border-subtle pl-3 pb-2 text-xs text-content-muted">
        {parents.map(([parentId, parent]) => (
          <li key={parentId} className="min-w-0 space-y-1">
            <p className="break-words font-medium text-content-primary">{parent.name}</p>
            <ul aria-label="Reference locations" className="flex flex-wrap gap-x-3 gap-y-1">
              {[...parent.locations].map((location) => (
                <li key={location} className="break-all">
                  {location || "Location not recorded"}
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
    </details>
  );
}
