import { useLayoutEffect, useRef, useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { FileText, RefreshCw, Search, ShieldCheck, TriangleAlert, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { StatusBadge } from "@/components/ui/status-badge";
import { TablePagination } from "@/components/ui/table-pagination";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { uiLocale } from "@/i18n/format";
import {
  getGoogleDriveAclOptions,
  listGoogleDriveAclOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { GetGoogleDriveAclResponse, GoogleDriveAclItem } from "@/lib/hey-api/types.gen";
import { ExpandableRow, ListDetailLayout } from "./list-detail-layout";
import { sourceMutationError, sourceStatusMessage } from "./source-errors";

type Snapshot = NonNullable<GetGoogleDriveAclResponse["snapshot"]>;
type Permission = Snapshot["permissions"][number];
const defaultPageSize = 5;
const detailPageSize = 25;

export function GoogleDriveAclPanel({ sourceId }: { sourceId: string }) {
  const ui = useAppTranslation();
  const [draft, setDraft] = useState("");
  const [query, setQuery] = useState("");
  const [pageSize, setPageSize] = useState(defaultPageSize);
  const [cursor, setCursor] = useState<string>();
  const [previous, setPrevious] = useState<Array<string | undefined>>([]);
  const [selected, setSelected] = useState<{ fileId: string; name: string } | null>(null);
  const openButton = useRef<HTMLButtonElement | null>(null);
  const closeButton = useRef<HTMLButtonElement | null>(null);
  const listing = useQuery({
    ...listGoogleDriveAclOptions({
      path: { sourceId },
      query: { size: pageSize, cursor, query: query || undefined },
    }),
    retry: false,
    placeholderData: keepPreviousData,
  });
  const selectedFileId = selected?.fileId;
  useLayoutEffect(() => {
    if (selectedFileId) closeButton.current?.focus();
  }, [selectedFileId]);
  return (
    <section
      aria-label={ui("Google Drive permissions")}
      className="rounded-xl border border-border-subtle bg-surface-raised p-4 sm:p-5"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 font-heading-h3 text-content-primary">
            <ShieldCheck className="size-5" aria-hidden="true" />
            {ui("Google Drive permissions")}
          </h2>
          <p className="mt-2 max-w-3xl text-sm text-content-muted">
            {ui(
              "Inspect the permissions collected from Google Drive. These observations do not determine who can search or chat with a document in MemoryOS.",
            )}
          </p>
        </div>
        <Button
          prominence="secondary"
          size="sm"
          pending={listing.isFetching}
          onClick={() => void listing.refetch()}
        >
          <RefreshCw />
          {ui("Refresh")}
        </Button>
      </div>
      <form
        className="my-5 flex flex-wrap items-end gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          setQuery(draft.trim());
          setCursor(undefined);
          setPrevious([]);
        }}
      >
        <label className="min-w-0 flex-1 space-y-1 text-sm text-content-secondary">
          <span>{ui("Find a file or folder")}</span>
          <Input
            value={draft}
            maxLength={200}
            onChange={(event) => setDraft(event.target.value)}
            placeholder={ui("Name or Google file ID")}
          />
        </label>
        <Button type="submit" prominence="secondary">
          <Search />
          {ui("Search")}
        </Button>
      </form>
      {listing.isError ? (
        <p role="alert" className="my-4 text-sm text-status-danger-content">
          {ui(sourceMutationError(listing.error, "google-drive"))}
        </p>
      ) : null}
      {listing.isPending ? (
        <p role="status" className="py-8 text-sm text-content-muted">
          {ui("Loading permission observations…")}
        </p>
      ) : listing.data ? (
        <>
          <p className="mb-3 text-sm text-content-muted">
            {ui("{{count}} tracked files and folders", { count: listing.data.totalItems })}
          </p>
          <ListDetailLayout
            detailLabel={ui("Observed permissions")}
            list={
              listing.data.items.length ? (
                <ul
                  aria-label={ui("Permission observations")}
                  className="divide-y divide-border-subtle rounded-lg border border-border-subtle"
                >
                  {listing.data.items.map((entry) => (
                    <AclListRow
                      key={entry.fileId}
                      entry={entry}
                      selected={selected?.fileId === entry.fileId}
                      onSelect={(button) => {
                        openButton.current = button;
                        setSelected({ fileId: entry.fileId, name: entry.name });
                      }}
                    />
                  ))}
                </ul>
              ) : (
                <div className="rounded-lg border border-dashed border-border-subtle p-8 text-center text-sm text-content-muted">
                  {query
                    ? ui("No files match this search.")
                    : ui(
                        "No tracked files yet. Synchronize the Source to collect file and permission observations.",
                      )}
                </div>
              )
            }
            detail={
              selected ? (
                <div className="min-w-0">
                  <div className="mb-4 flex items-start justify-between gap-3">
                    <h3 className="min-w-0 break-words font-heading-h3 text-content-primary [overflow-wrap:anywhere]">
                      {selected.name}
                    </h3>
                    <IconButton
                      ref={closeButton}
                      prominence="tertiary"
                      aria-label={ui("Close permission details")}
                      onClick={() => {
                        setSelected(null);
                        openButton.current?.focus();
                      }}
                    >
                      <X />
                    </IconButton>
                  </div>
                  <AclDetail key={selected.fileId} sourceId={sourceId} fileId={selected.fileId} />
                </div>
              ) : null
            }
          />
          <TablePagination
            className="mt-4"
            label={ui("Permission observation pages")}
            page={previous.length}
            totalPages={Math.ceil(listing.data.totalItems / pageSize)}
            previousDisabled={!previous.length || listing.isFetching}
            nextDisabled={!listing.data.nextCursor || listing.isFetching || listing.isError}
            onPrevious={() => {
              setCursor(previous.at(-1));
              setPrevious((pages) => pages.slice(0, -1));
            }}
            onNext={() => {
              setPrevious((pages) => [...pages, cursor]);
              setCursor(listing.data?.nextCursor ?? undefined);
            }}
          >
            <label className="flex items-center gap-2 font-secondary-body text-content-secondary">
              {ui("Rows")}
              <Select
                aria-label={ui("Permission observations per page")}
                size="sm"
                className="w-auto px-2"
                value={pageSize}
                disabled={listing.isFetching}
                onChange={(event) => {
                  setPageSize(Number(event.target.value));
                  setCursor(undefined);
                  setPrevious([]);
                }}
              >
                {[5, 10, 25, 50].map((size) => (
                  <option key={size} value={size}>
                    {size}
                  </option>
                ))}
              </Select>
            </label>
          </TablePagination>
        </>
      ) : null}
    </section>
  );
}

function AclListRow({
  entry,
  selected,
  onSelect,
}: {
  entry: GoogleDriveAclItem;
  selected: boolean;
  onSelect: (button: HTMLButtonElement) => void;
}) {
  const ui = useAppTranslation();
  return (
    <li className="min-w-0">
      <button
        type="button"
        aria-current={selected || undefined}
        className={`flex min-h-11 w-full min-w-0 items-center gap-3 px-3 py-2 text-left transition-colors focus-visible:outline-2 focus-visible:outline-focus-ring ${
          selected ? "bg-surface-subtle" : "hover:bg-surface-subtle/40"
        }`}
        onClick={(event) => onSelect(event.currentTarget)}
      >
        <FileText className="size-4 shrink-0 text-content-muted" aria-hidden="true" />
        <span className="min-w-0 flex-1">
          <span className="block break-words text-sm text-content-primary [overflow-wrap:anywhere]">
            {entry.name}
          </span>
          <span className="mt-0.5 block text-xs text-content-muted">
            {entry.permissionCount != null
              ? ui("{{count}} permission entries", { count: entry.permissionCount })
              : ui("Permission entries unknown")}
            {" · "}
            {ui("Last observed")} <ObservationTime value={entry.lastSuccessAt} />
          </span>
        </span>
        <AclObservationBadge status={entry.status} context={entry.contextStatus} />
      </button>
    </li>
  );
}

function AclObservationBadge({
  status,
  context,
}: {
  status: GoogleDriveAclItem["status"];
  context: GoogleDriveAclItem["contextStatus"];
}) {
  const ui = useAppTranslation();
  if (status === "FAILED")
    return (
      <StatusBadge tone="warning" size="sm">
        {ui("Refresh failed")}
      </StatusBadge>
    );
  if (context === "STALE")
    return (
      <StatusBadge tone="warning" size="sm">
        {ui("Stale")}
      </StatusBadge>
    );
  if (context === "INVALID")
    return (
      <StatusBadge tone="warning" size="sm">
        {ui("Inactive")}
      </StatusBadge>
    );
  if (status === "SUCCEEDED")
    return (
      <StatusBadge tone="success" size="sm">
        {ui("Observed")}
      </StatusBadge>
    );
  return (
    <StatusBadge tone="neutral" size="sm">
      {ui("Not observed")}
    </StatusBadge>
  );
}

function AclDetail({ sourceId, fileId }: { sourceId: string; fileId: string }) {
  const ui = useAppTranslation();
  const [page, setPage] = useState(0);
  const detail = useQuery({
    ...getGoogleDriveAclOptions({ path: { sourceId, fileId } }),
    retry: false,
  });
  const snapshot = detail.data?.snapshot;
  const permissionPages = Math.ceil((snapshot?.permissions.length ?? 0) / detailPageSize);
  if (page > 0 && page >= permissionPages) setPage(Math.max(0, permissionPages - 1));
  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5">
          {snapshot ? (
            <ObservationStatus status={snapshot.status} context={snapshot.contextStatus} />
          ) : null}
          <span className="text-xs text-content-muted">
            {ui("Last successful observation")}{" "}
            <ObservationTime value={snapshot?.lastSuccess?.at} />
            {" · "}
            {snapshot?.lastSuccess
              ? ui("{{count}} permission entries", { count: snapshot.permissions.length })
              : ui("Permission entries unknown")}
          </span>
        </div>
        <Button
          prominence="secondary"
          size="sm"
          pending={detail.isFetching}
          onClick={() => {
            setPage(0);
            void detail.refetch();
          }}
        >
          <RefreshCw />
          {ui("Refresh")}
        </Button>
      </div>
      {detail.isError ? (
        <p role="alert" className="text-sm text-status-danger-content">
          {ui(sourceMutationError(detail.error, "google-drive"))}
        </p>
      ) : detail.isPending ? (
        <p role="status">{ui("Loading permission observations…")}</p>
      ) : !snapshot ? (
        <p className="rounded-lg bg-surface-sunken p-4 text-sm text-content-muted">
          {ui("No permission observation has been recorded for this file yet.")}
        </p>
      ) : (
        <>
          {snapshot.status === "FAILED" ? (
            <div
              role="alert"
              className="space-y-2 rounded-lg bg-status-warning-surface p-4 text-sm text-status-warning-content"
            >
              <p>
                {snapshot.lastSuccess
                  ? ui(
                      "Last refresh failed. The permissions below are from the last successful observation.",
                    )
                  : ui("No successful permission snapshot yet.")}
              </p>
              {snapshot.errorCode ? <p>{ui(sourceStatusMessage(snapshot.errorCode))}</p> : null}
              {snapshot.errorMessage ? (
                <p className="[overflow-wrap:anywhere]">{snapshot.errorMessage}</p>
              ) : null}
            </div>
          ) : null}
          {snapshot.contextStatus === "STALE" || snapshot.contextStatus === "INVALID" ? (
            <p className="rounded-lg bg-status-warning-surface p-4 text-sm text-status-warning-content">
              {ui(
                "The Source context has changed. Treat this snapshot as historical evidence, not current access.",
              )}
            </p>
          ) : null}
          {snapshot.lastSuccess && !snapshot.permissions.length ? (
            <p className="rounded-lg border border-border-subtle p-4 text-sm text-content-muted">
              {ui(
                "Google returned an empty permission list. This does not mean the file is public.",
              )}
            </p>
          ) : null}
          <ul
            aria-label={ui("Permission entries")}
            className="divide-y divide-border-subtle rounded-lg border border-border-subtle"
          >
            {snapshot.permissions
              .slice(page * detailPageSize, (page + 1) * detailPageSize)
              .map((permission) => (
                <li key={permission.id} className="min-w-0">
                  <PermissionEntry permission={permission} />
                </li>
              ))}
          </ul>
          {snapshot.permissions.length > detailPageSize ? (
            <TablePagination
              label={ui("Permission entry pages")}
              page={page}
              totalPages={permissionPages}
              previousDisabled={!page}
              nextDisabled={(page + 1) * detailPageSize >= snapshot.permissions.length}
              onPrevious={() => setPage((current) => current - 1)}
              onNext={() => setPage((current) => current + 1)}
            />
          ) : null}
          <details className="rounded-lg border border-border-subtle p-3 text-sm">
            <summary className="cursor-pointer text-content-secondary">
              {ui("Observation provenance")}
            </summary>
            <dl className="mt-3 space-y-3 break-all">
              <div>
                <dt className="text-content-muted">{ui("Google file ID")}</dt>
                <dd>{fileId}</dd>
              </div>
              <div>
                <dt className="text-content-muted">{ui("Source operation")}</dt>
                <dd>{snapshot.lastAttempt.operationId}</dd>
              </div>
              <div>
                <dt className="text-content-muted">{ui("Read at")}</dt>
                <dd>
                  <ObservationTime value={snapshot.readAt} />
                </dd>
              </div>
            </dl>
          </details>
        </>
      )}
    </div>
  );
}

const permissionTypes: Record<string, string> = {
  user: "User",
  group: "Group",
  domain: "Domain",
  anyone: "Anyone",
};
const permissionRoles: Record<string, string> = {
  owner: "Owner",
  organizer: "Organizer",
  fileOrganizer: "File organizer",
  writer: "Editor",
  commenter: "Commenter",
  reader: "Viewer",
};

function PermissionEntry({ permission }: { permission: Permission }) {
  const ui = useAppTranslation();
  const identity =
    permission.emailAddress ||
    permission.domain ||
    (permission.type === "anyone" ? ui("Anyone") : permission.id);
  const warning = permission.deleted
    ? ui("Google marks this account as deleted.")
    : permission.pendingOwner
      ? ui("Ownership transfer pending")
      : null;
  return (
    <ExpandableRow
      label={ui("Permission details for {{v1}}", { v1: identity })}
      summary={
        <span className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1">
          <span className="min-w-0 break-all text-sm font-medium text-content-primary">
            {identity}
          </span>
          <StatusBadge tone="neutral">
            {permissionRoles[permission.role]
              ? ui(permissionRoles[permission.role])
              : permission.role}
          </StatusBadge>
          <span className="text-xs text-content-muted">
            {permissionTypes[permission.type]
              ? ui(permissionTypes[permission.type])
              : permission.type}
            {permission.expirationTime != null ? (
              <>
                {" · "}
                {ui("Expires")} <ObservationTime value={permission.expirationTime} />
              </>
            ) : null}
          </span>
          {warning ? (
            <span className="flex items-center gap-1 text-xs text-status-warning-content">
              <TriangleAlert aria-hidden="true" className="size-3.5" />
              {warning}
            </span>
          ) : null}
        </span>
      }
    >
      <dl className="grid gap-3 text-xs sm:grid-cols-2">
        <div>
          <dt className="text-content-muted">{ui("Expiration")}</dt>
          <dd>
            <ObservationTime value={permission.expirationTime} unknown="No expiry" />
          </dd>
        </div>
        <div>
          <dt className="text-content-muted">{ui("Discovery")}</dt>
          <dd>
            {permission.allowFileDiscovery == null
              ? ui("Not returned by Google")
              : permission.allowFileDiscovery
                ? ui("Discoverable")
                : ui("Link only")}
          </dd>
        </div>
      </dl>
      <dl className="mt-3 space-y-2 break-all text-xs">
        <div>
          <dt className="text-content-muted">{ui("Permission ID")}</dt>
          <dd>{permission.id}</dd>
        </div>
        <div>
          <dt className="text-content-muted">{ui("Permission view")}</dt>
          <dd>{permission.view ?? ui("Not returned by Google")}</dd>
        </div>
        <div>
          <dt className="text-content-muted">{ui("Inherited permissions disabled")}</dt>
          <dd>
            {permission.inheritedPermissionsDisabled == null
              ? ui("Not returned by Google")
              : permission.inheritedPermissionsDisabled
                ? ui("Yes")
                : ui("No")}
          </dd>
        </div>
      </dl>
      {permission.permissionDetails.length ? (
        <ul className="mt-3 space-y-2 text-xs">
          {permission.permissionDetails.map((entry, index) => (
            <li key={index} className="rounded bg-surface-sunken p-3">
              <p>
                {entry.inherited == null
                  ? ui("Inheritance not returned")
                  : entry.inherited
                    ? ui("Inherited")
                    : ui("Direct")}
                {entry.inheritedFrom ? ui(" · {{v1}}", { v1: entry.inheritedFrom }) : ""}
              </p>
              <p className="mt-1 text-content-muted">
                {entry.permissionType ?? ui("Unknown")}
                {entry.role
                  ? ui(" · {{v1}}", {
                      v1: permissionRoles[entry.role]
                        ? ui(permissionRoles[entry.role])
                        : entry.role,
                    })
                  : ""}
              </p>
            </li>
          ))}
        </ul>
      ) : (
        <p className="mt-3 text-xs text-content-muted">
          {ui("No inheritance details returned. This does not prove the permission is direct.")}
        </p>
      )}
    </ExpandableRow>
  );
}

function ObservationStatus({
  status,
  context,
}: {
  status?: string | null;
  context?: string | null;
}) {
  const ui = useAppTranslation();
  const contexts: Record<string, string> = {
    CURRENT: "Context matches",
    STALE: "Stale context",
    INVALID: "Inactive context",
    UNOBSERVED: "Not observed",
  };
  return (
    <div className="flex flex-wrap gap-1.5">
      <StatusBadge tone={status === "FAILED" ? "warning" : "neutral"}>
        {status === "FAILED"
          ? ui("Refresh failed")
          : status === "SUCCEEDED"
            ? ui("Snapshot collected")
            : ui("Not observed")}
      </StatusBadge>
      {context && context !== "UNOBSERVED" ? (
        <StatusBadge tone={context === "CURRENT" ? "neutral" : "warning"}>
          {ui(contexts[context] ?? "Unknown")}
        </StatusBadge>
      ) : null}
    </div>
  );
}

function ObservationTime({
  value,
  unknown = "Not yet",
}: {
  value?: string | null;
  unknown?: string;
}) {
  const ui = useAppTranslation();
  return value ? (
    <time dateTime={value}>{new Date(value).toLocaleString(uiLocale())}</time>
  ) : (
    <>{ui(unknown)}</>
  );
}
