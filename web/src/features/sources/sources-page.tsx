import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ArrowRight, Files, LoaderCircle, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { listSourcesOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { findSourceProvider } from "./source-provider-catalog";
import { SourceStatusBadge } from "./source-status-badge";

export function SourcesPage() {
  const sourcesQuery = useQuery({
    ...listSourcesOptions(),
    retry: false,
    refetchInterval: (query) =>
      query.state.data?.some((source) => source.pendingWork) ? 1_500 : false,
  });
  const sources = sourcesQuery.data ?? [];

  return (
    <section className="mx-auto w-full max-w-6xl px-5 py-8 sm:px-8 sm:py-12">
      <header className="flex items-center justify-between gap-4 border-b border-border-subtle pb-6">
        <h1 className="font-heading-h2 text-content-primary">Sources</h1>
        <Button asChild>
          <Link to="/admin/sources/new">
            <Plus />
            Add source
          </Link>
        </Button>
      </header>

      {sourcesQuery.isPending ? (
        <div className="flex min-h-52 items-center justify-center">
          <span className="inline-flex items-center gap-2 text-sm text-content-muted">
            <LoaderCircle
              className="size-4 animate-spin motion-reduce:animate-none"
              aria-hidden="true"
            />
            Loading sources
          </span>
        </div>
      ) : sourcesQuery.isError ? (
        <div className="py-14 text-center">
          <h2 className="font-heading-h3 text-content-primary">Sources unavailable</h2>
          <Button
            prominence="secondary"
            size="sm"
            className="mt-4"
            onClick={() => void sourcesQuery.refetch()}
          >
            Try again
          </Button>
        </div>
      ) : sources.length === 0 ? (
        <div className="py-14 text-center">
          <span className="mx-auto grid size-10 place-items-center rounded-xl border border-border-subtle bg-surface-subtle text-content-secondary">
            <Files className="size-5" aria-hidden="true" />
          </span>
          <h2 className="mt-4 font-heading-h3 text-content-primary">No sources yet</h2>
          <Button asChild size="sm" className="mt-4">
            <Link to="/admin/sources/new">
              <Plus />
              Add source
            </Link>
          </Button>
        </div>
      ) : (
        <SourceList sources={sources} />
      )}
    </section>
  );
}

function SourceList({ sources }: { sources: SourceSummary[] }) {
  return (
    <div className="mt-6 overflow-hidden rounded-xl border border-border-subtle bg-surface-raised">
      <div
        aria-hidden="true"
        className="hidden grid-cols-[minmax(0,1.5fr)_minmax(8rem,0.7fr)_auto_6rem_1.5rem] items-center gap-5 border-b border-border-subtle bg-surface-subtle px-4 py-2.5 font-secondary-body text-content-muted md:grid"
      >
        <span>Source</span>
        <span>Last indexed</span>
        <span>Status</span>
        <span className="text-right">Documents</span>
        <span />
      </div>
      <ul aria-label="Connected sources" className="divide-y divide-border-subtle">
        {sources.map((source) => (
          <SourceRow key={source.id} source={source} />
        ))}
      </ul>
    </div>
  );
}

function SourceRow({ source }: { source: SourceSummary }) {
  const provider = findSourceProvider(source.type);
  const ProviderIcon = provider?.icon ?? Files;
  return (
    <li>
      <Link
        to="/admin/sources/$sourceId"
        params={{ sourceId: source.id }}
        className="group grid min-h-16 grid-cols-[minmax(0,1fr)_auto_1.25rem] items-center gap-4 px-4 py-3 transition-colors hover:bg-surface-subtle focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-focus-ring md:grid-cols-[minmax(0,1.5fr)_minmax(8rem,0.7fr)_auto_6rem_1.5rem] md:gap-5"
      >
        <span className="flex min-w-0 items-center gap-3">
          <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-surface-subtle text-content-secondary group-hover:bg-surface-raised">
            <ProviderIcon className="size-4" aria-hidden="true" />
          </span>
          <span className="min-w-0">
            <span className="block truncate text-sm font-medium text-content-primary">
              {source.name}
            </span>
            <span className="mt-0.5 block font-secondary-body text-content-muted">
              {provider?.name ?? source.type}
            </span>
          </span>
        </span>
        <span className="hidden font-secondary-body text-content-muted md:block">
          <LastIndexed value={source.lastSucceededAt} />
        </span>
        <SourceStatusBadge status={source.status} />
        <span className="hidden text-right text-sm tabular-nums text-content-secondary md:block">
          {source.documentCount}
        </span>
        <ArrowRight
          className="size-4 text-content-muted transition-transform group-hover:translate-x-0.5 group-hover:text-content-primary"
          aria-hidden="true"
        />
      </Link>
    </li>
  );
}

function LastIndexed({ value }: { value: string | null }) {
  if (!value) return <>Never</>;
  const date = new Date(value);
  return (
    <time dateTime={value} title={date.toLocaleString()}>
      {new Intl.DateTimeFormat(undefined, { dateStyle: "medium" }).format(date)}
    </time>
  );
}
