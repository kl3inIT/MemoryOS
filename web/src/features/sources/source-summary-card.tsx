import type { ReactNode } from "react";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { SourceAccessBadge, SourceStatusBadge } from "./source-status-badge";

export function SourceSummaryCard({
  source,
  children,
}: {
  source: SourceSummary;
  children?: ReactNode;
}) {
  return (
    <dl
      aria-label="Source summary"
      className={cn(
        "my-6 grid gap-5 rounded-lg border border-border-subtle px-4 py-5 text-sm sm:grid-cols-2",
        children ? "lg:grid-cols-3 xl:grid-cols-5" : "lg:grid-cols-4",
      )}
    >
      <div>
        <dt className="text-content-muted">Source status</dt>
        <dd className="mt-2">
          <SourceStatusBadge status={source.status} />
        </dd>
      </div>
      <div>
        <dt className="text-content-muted">Access</dt>
        <dd className="mt-2">
          <SourceAccessBadge access={source.access} />
        </dd>
        {source.type === "GOOGLE_DRIVE" ? (
          <dd className="mt-2 text-xs text-content-muted">
            Google Drive document access is not configured here.
          </dd>
        ) : null}
      </div>
      <div>
        <dt className="text-content-muted">Documents indexed</dt>
        <dd className="mt-2 text-lg font-semibold tabular-nums text-content-primary">
          {source.documentCount}
        </dd>
      </div>
      <div>
        <dt className="text-content-muted">Last indexed successfully</dt>
        <dd className="mt-2 text-content-primary">
          {source.lastSucceededAt ? (
            <time dateTime={source.lastSucceededAt}>
              {new Date(source.lastSucceededAt).toLocaleString()}
            </time>
          ) : (
            "Not yet"
          )}
        </dd>
      </div>
      {children}
    </dl>
  );
}
