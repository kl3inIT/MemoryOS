import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ChevronDown, Files, LoaderCircle, Plus } from "lucide-react";
import { useMemo, useState } from "react";
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
  const groups = useMemo(() => groupSources(sources), [sources]);
  const [collapsedTypes, setCollapsedTypes] = useState<Set<string>>(() => new Set());

  function toggle(type: string) {
    setCollapsedTypes((current) => {
      const next = new Set(current);
      if (next.has(type)) next.delete(type);
      else next.add(type);
      return next;
    });
  }

  return (
    <div className="mt-6 overflow-x-auto rounded-xl border border-border-subtle bg-surface-raised">
      <table className="w-full min-w-[48rem] border-collapse">
        <caption className="sr-only">Connected sources</caption>
        <thead className="border-b border-border-subtle bg-surface-subtle text-left">
          <tr>
            <th scope="col" className="px-4 py-3 font-secondary-action text-content-secondary">
              Name
            </th>
            <th scope="col" className="px-4 py-3 font-secondary-action text-content-secondary">
              Last indexed
            </th>
            <th scope="col" className="px-4 py-3 font-secondary-action text-content-secondary">
              Status
            </th>
            <th
              scope="col"
              className="px-4 py-3 text-right font-secondary-action text-content-secondary"
            >
              Documents
            </th>
            <th scope="col" className="px-4 py-3 text-right">
              <span className="sr-only">Manage</span>
            </th>
          </tr>
        </thead>
        {groups.map((group) => (
          <SourceGroupBody
            key={group.type}
            group={group}
            collapsed={collapsedTypes.has(group.type)}
            onToggle={() => toggle(group.type)}
          />
        ))}
      </table>
    </div>
  );
}

type SourceGroup = {
  type: string;
  sources: SourceSummary[];
};

function SourceGroupBody({
  group,
  collapsed,
  onToggle,
}: {
  group: SourceGroup;
  collapsed: boolean;
  onToggle: () => void;
}) {
  const provider = findSourceProvider(group.type);
  const ProviderIcon = provider?.icon ?? Files;
  const documentCount = group.sources.reduce((total, source) => total + source.documentCount, 0);
  return (
    <tbody className="divide-y divide-border-subtle">
      <tr className="bg-surface-subtle/40">
        <th scope="rowgroup" colSpan={5} className="p-0 text-left">
          <button
            type="button"
            aria-expanded={!collapsed}
            onClick={onToggle}
            className="flex w-full items-center gap-3 px-4 py-3 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-focus-ring"
          >
            <ProviderIcon className="size-4 text-content-secondary" aria-hidden="true" />
            <span className="text-sm font-medium text-content-primary">
              {provider?.name ?? group.type}
            </span>
            <span className="font-secondary-body text-content-muted">
              {group.sources.length} {group.sources.length === 1 ? "source" : "sources"} ·{" "}
              {documentCount} documents
            </span>
            <ChevronDown
              className={`ml-auto size-4 text-content-muted transition-transform ${
                collapsed ? "-rotate-90" : ""
              }`}
              aria-hidden="true"
            />
          </button>
        </th>
      </tr>
      {!collapsed
        ? group.sources.map((source) => <SourceRow key={source.id} source={source} />)
        : null}
    </tbody>
  );
}

function SourceRow({ source }: { source: SourceSummary }) {
  return (
    <tr id={`source-${source.id}`} className="align-middle hover:bg-surface-subtle/50">
      <td className="px-4 py-3">
        <Link
          to="/admin/sources/$sourceId"
          params={{ sourceId: source.id }}
          className="text-sm font-medium text-content-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
        >
          {source.name}
        </Link>
      </td>
      <td className="px-4 py-3 font-secondary-body text-content-muted">
        <LastIndexed value={source.lastSucceededAt} />
      </td>
      <td className="px-4 py-3">
        <SourceStatusBadge status={source.status} />
      </td>
      <td className="px-4 py-3 text-right text-sm tabular-nums text-content-secondary">
        {source.documentCount}
      </td>
      <td className="px-4 py-3 text-right">
        <Button asChild prominence="tertiary" size="sm">
          <Link to="/admin/sources/$sourceId" params={{ sourceId: source.id }}>
            Manage
          </Link>
        </Button>
      </td>
    </tr>
  );
}

function groupSources(sources: SourceSummary[]) {
  const groups = new Map<string, SourceSummary[]>();
  for (const source of sources) {
    const group = groups.get(source.type);
    if (group) group.push(source);
    else groups.set(source.type, [source]);
  }
  return Array.from(groups, ([type, groupedSources]) => ({ type, sources: groupedSources }));
}

const sourceDateFormatter = new Intl.DateTimeFormat(undefined, { dateStyle: "medium" });

function LastIndexed({ value }: { value: string | null }) {
  if (!value) return <>Never</>;
  const date = new Date(value);
  return (
    <time dateTime={value} title={date.toLocaleString()}>
      {sourceDateFormatter.format(date)}
    </time>
  );
}
