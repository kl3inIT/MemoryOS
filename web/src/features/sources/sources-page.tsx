import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { BookOpen, ChevronDown, Files, Settings, TriangleAlert, X } from "lucide-react";
import { useMemo, useState } from "react";
import { BrandLoader } from "@/components/brand-loader";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { Separator } from "@/components/ui/separator";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import {
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useCapabilityAuthority } from "@/features/identity/application-session-context";
import { listSourcesOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { findSourceProvider, sourceProviders } from "./source-provider-catalog";
import { SourceAccessBadge, SourceStatusBadge } from "./source-status-badge";
import { sourceAccessOptions, sourceStatusOptions } from "./source-status-presentation";

type SourceFilterKey = "type" | "status" | "access";
type SourceFilterOption = { value: string; label: string };

/** An empty value leaves that attribute unfiltered. */
const noFilters: Record<SourceFilterKey, string> = { type: "", status: "", access: "" };

const sourceFilterMenus: {
  key: SourceFilterKey;
  label: string;
  allLabel: string;
  options: readonly SourceFilterOption[];
}[] = [
  {
    key: "type",
    label: "Provider",
    allLabel: "All providers",
    options: sourceProviders.map(({ type, name }) => ({ value: type, label: name })),
  },
  { key: "status", label: "Status", allLabel: "All statuses", options: sourceStatusOptions },
  { key: "access", label: "Access", allLabel: "All access", options: sourceAccessOptions },
];

export function SourcesPage() {
  const ui = useAppTranslation();

  const canCreate = useCapabilityAuthority("SOURCES_MANAGE") !== "none";
  const sourcesQuery = useQuery({
    ...listSourcesOptions(),
    retry: false,
    refetchInterval: (query) =>
      query.state.data?.some((source) => source.pendingWork) ? 1_500 : false,
  });
  const sources = sourcesQuery.data ?? [];

  return (
    <SettingsLayout wide>
      <PageHeader
        icon={<BookOpen />}
        title={ui("Existing sources")}
        description={ui("Manage connected content and monitor indexing.")}
        actions={
          canCreate ? (
            <Button asChild>
              <Link to="/admin/sources/new">{ui("Add source")}</Link>
            </Button>
          ) : null
        }
      />

      {sourcesQuery.isPending ? (
        <div className="flex min-h-52 items-center justify-center">
          <BrandLoader label={ui("Loading sources")} />
        </div>
      ) : sourcesQuery.isError ? (
        <Empty className="border border-dashed border-border-subtle py-14">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <TriangleAlert aria-hidden="true" />
            </EmptyMedia>
            <EmptyTitle>{ui("Sources unavailable")}</EmptyTitle>
            <EmptyDescription>
              {ui("The Source list could not be loaded. Try again in a moment.")}
            </EmptyDescription>
          </EmptyHeader>
          <EmptyContent>
            <Button prominence="secondary" size="sm" onClick={() => void sourcesQuery.refetch()}>
              {ui("Try again")}
            </Button>
          </EmptyContent>
        </Empty>
      ) : sources.length === 0 ? (
        <Empty className="border border-dashed border-border-subtle py-14">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <Files aria-hidden="true" />
            </EmptyMedia>
            <EmptyTitle>{ui("No sources yet")}</EmptyTitle>
            <EmptyDescription>
              {ui("Connect files or Google Drive to make their content searchable in MemoryOS.")}
            </EmptyDescription>
          </EmptyHeader>
          {canCreate ? (
            <EmptyContent>
              <Button asChild size="sm">
                <Link to="/admin/sources/new">{ui("Add source")}</Link>
              </Button>
            </EmptyContent>
          ) : null}
        </Empty>
      ) : (
        <SourceList sources={sources} />
      )}
    </SettingsLayout>
  );
}

function SourceList({ sources }: { sources: SourceSummary[] }) {
  const ui = useAppTranslation();

  const [searchQuery, setSearchQuery] = useState("");
  const [filters, setFilters] = useState(noFilters);
  const visibleSources = useMemo(() => {
    const query = searchQuery.trim().toLocaleLowerCase();
    return sources.filter(
      (source) =>
        (!query ||
          source.name.toLocaleLowerCase().includes(query) ||
          ui(findSourceProvider(source.type)?.name ?? source.type)
            .toLocaleLowerCase()
            .includes(query)) &&
        sourceFilterMenus.every(({ key }) => !filters[key] || source[key] === filters[key]),
    );
  }, [filters, searchQuery, sources, ui]);
  const hasActiveFilters = sourceFilterMenus.some(({ key }) => filters[key]);

  return (
    <>
      <SourceOverview sources={sources} />

      <div className="flex flex-wrap items-center gap-2">
        <Input
          type="search"
          size="sm"
          value={searchQuery}
          placeholder={ui("Search sources")}
          aria-label={ui("Search sources")}
          className="min-w-48 flex-1 bg-surface-sunken sm:max-w-xs"
          onChange={(event) => setSearchQuery(event.target.value)}
        />
        {sourceFilterMenus.map((menu) => (
          <SourceFilterMenu
            key={menu.key}
            label={menu.label}
            allLabel={menu.allLabel}
            options={menu.options}
            value={filters[menu.key]}
            onValueChange={(value) => setFilters((current) => ({ ...current, [menu.key]: value }))}
          />
        ))}
        {hasActiveFilters ? (
          <Button size="sm" prominence="tertiary" onClick={() => setFilters(noFilters)}>
            {ui("Clear filters")}
            <X aria-hidden="true" />
          </Button>
        ) : null}
      </div>

      <div
        role="region"
        aria-label={ui("Connected sources table")}
        className="overflow-hidden rounded-xl border border-border-subtle"
      >
        <Table className="min-w-[52rem]">
          <TableCaption className="sr-only">{ui("Connected sources")}</TableCaption>
          <TableHeader className="bg-surface-subtle">
            <TableRow>
              <TableHead className="px-4">{ui("Name")}</TableHead>
              <TableHead className="w-40">{ui("Status")}</TableHead>
              <TableHead className="w-56">{ui("Access")}</TableHead>
              <TableHead className="w-32 text-right">{ui("Total docs")}</TableHead>
              <TableHead className="w-52">{ui("Last indexed")}</TableHead>
              <TableHead className="w-14 pr-4">
                <span className="sr-only">{ui("Manage")}</span>
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {visibleSources.map((source) => (
              <SourceRow key={source.id} source={source} />
            ))}
            {visibleSources.length === 0 ? (
              <TableRow className="hover:bg-transparent">
                <TableCell colSpan={6}>
                  <Empty className="gap-3 py-10">
                    <EmptyHeader>
                      <EmptyTitle className="font-main-ui-action">
                        {ui("No sources match your search and filters.")}
                      </EmptyTitle>
                    </EmptyHeader>
                    <EmptyContent>
                      <Button
                        size="sm"
                        prominence="secondary"
                        onClick={() => {
                          setSearchQuery("");
                          setFilters(noFilters);
                        }}
                      >
                        {ui("Clear search and filters")}
                      </Button>
                    </EmptyContent>
                  </Empty>
                </TableCell>
              </TableRow>
            ) : null}
          </TableBody>
        </Table>
      </div>
    </>
  );
}

function SourceOverview({ sources }: { sources: SourceSummary[] }) {
  const ui = useAppTranslation();

  const activeCount = sources.filter((source) => source.status === "ACTIVE").length;
  const failedCount = sources.filter((source) => source.status === "FAILED").length;
  const documentCount = sources.reduce((total, source) => total + source.documentCount, 0);
  const metrics = [
    { label: "Total sources", value: formatCount(sources.length) },
    {
      label: "Active sources",
      value: `${formatCount(activeCount)}/${formatCount(sources.length)}`,
    },
    { label: "Failed sources", value: formatCount(failedCount), attention: failedCount > 0 },
    { label: "Total docs indexed", value: formatCount(documentCount) },
  ];

  return (
    <dl className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      {metrics.map(({ label, value, attention }) => (
        <div
          key={label}
          className="rounded-xl border border-border-subtle bg-surface-raised px-4 py-3"
        >
          <dt className="font-secondary-body text-content-muted">{ui(label)}</dt>
          <dd
            className={cn(
              "mt-1 font-heading-h3 tabular-nums text-content-primary",
              attention && "text-status-danger-content",
            )}
          >
            {value}
          </dd>
        </div>
      ))}
    </dl>
  );
}

function SourceFilterMenu({
  label,
  allLabel,
  options,
  value,
  onValueChange,
}: {
  label: string;
  allLabel: string;
  options: readonly SourceFilterOption[];
  value: string;
  onValueChange: (value: string) => void;
}) {
  const ui = useAppTranslation();

  const selected = options.find((option) => option.value === value);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button size="sm" prominence="secondary">
          {ui(label)}
          {selected ? (
            <>
              <Separator orientation="vertical" className="h-4" />
              <span className="text-content-primary">{ui(selected.label)}</span>
            </>
          ) : null}
          <ChevronDown aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent className="w-56">
        <DropdownMenuLabel>{ui(label)}</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuRadioGroup value={value} onValueChange={onValueChange}>
          <DropdownMenuRadioItem value="">{ui(allLabel)}</DropdownMenuRadioItem>
          {options.map((option) => (
            <DropdownMenuRadioItem key={option.value} value={option.value}>
              {ui(option.label)}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function SourceRow({ source }: { source: SourceSummary }) {
  const ui = useAppTranslation();

  const provider = findSourceProvider(source.type);
  const ProviderIcon = provider?.icon ?? Files;

  return (
    <TableRow id={`source-${source.id}`} className="h-16">
      <TableCell className="px-4">
        <div className="flex min-w-0 items-center gap-3">
          <span className="grid size-9 shrink-0 place-items-center rounded-lg border border-border-subtle bg-surface-subtle text-content-secondary">
            <ProviderIcon className="size-4" aria-hidden="true" />
          </span>
          <div className="min-w-0 max-w-md">
            <Link
              to="/admin/sources/$sourceId"
              params={{ sourceId: source.id }}
              className="block truncate font-main-ui-action text-content-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
            >
              {source.name}
            </Link>
            <span className="block font-secondary-body text-content-muted">
              {ui(provider?.name ?? source.type)}
            </span>
          </div>
        </div>
      </TableCell>
      <TableCell>
        <SourceStatusBadge status={source.status} />
      </TableCell>
      <TableCell>
        <SourceAccessBadge access={source.access} />
      </TableCell>
      <TableCell className="text-right tabular-nums text-content-secondary">
        {formatCount(source.documentCount)}
      </TableCell>
      <TableCell className="text-content-muted">
        <LastIndexed value={source.lastSucceededAt} />
      </TableCell>
      <TableCell className="pr-4 text-right">
        {Object.values(source.permissions).some(Boolean) ? (
          <IconButton
            asChild
            size="sm"
            prominence="tertiary"
            aria-label={ui("Manage {{v1}}", { v1: source.name })}
          >
            <Link to="/admin/sources/$sourceId" params={{ sourceId: source.id }}>
              <Settings />
            </Link>
          </IconButton>
        ) : null}
      </TableCell>
    </TableRow>
  );
}

function LastIndexed({ value }: { value: string | null }) {
  const ui = useAppTranslation();

  if (!value) return <>{ui("Not yet")}</>;
  return (
    <time dateTime={value}>
      {new Intl.DateTimeFormat(uiLocale(), { dateStyle: "medium", timeStyle: "short" }).format(
        new Date(value),
      )}
    </time>
  );
}

function formatCount(value: number) {
  return new Intl.NumberFormat(uiLocale()).format(value);
}
