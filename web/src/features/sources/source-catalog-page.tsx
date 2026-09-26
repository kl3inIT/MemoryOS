import { useAppTranslation } from "@/i18n/use-app-translation";
import { Link, useNavigate } from "@tanstack/react-router";
import { CloudUpload } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import {
  sourceCategories,
  sourceProviders,
} from "@/features/sources/shared/source-provider-catalog";

export function SourceCatalogPage() {
  const ui = useAppTranslation();

  const navigate = useNavigate({ from: "/admin/sources/new/" });
  const [searchQuery, setSearchQuery] = useState("");
  const search = useRef<HTMLInputElement>(null);
  // The catalog is a search first: the field takes focus as the page opens.
  useEffect(() => search.current?.focus(), []);
  const normalizedQuery = searchQuery.trim().toLowerCase();
  const matchingProviders = sourceProviders.filter(
    (provider) =>
      !normalizedQuery ||
      ui(provider.name).toLocaleLowerCase().includes(normalizedQuery) ||
      ui(provider.category).toLocaleLowerCase().includes(normalizedQuery),
  );

  return (
    <SettingsLayout wide>
      <PageHeader
        icon={<CloudUpload />}
        title={ui("Add a source")}
        description={ui("Connect the content you want to keep in MemoryOS.")}
        actions={
          <Button asChild prominence="secondary">
            <Link to="/admin">{ui("See sources")}</Link>
          </Button>
        }
      />

      <Input
        ref={search}
        type="search"
        size="sm"
        value={searchQuery}
        placeholder={ui("Search sources")}
        aria-label={ui("Search sources")}
        onChange={(event) => setSearchQuery(event.target.value)}
        onKeyDown={(event) => {
          if (event.key !== "Enter") return;
          const currentQuery = event.currentTarget.value.trim().toLowerCase();
          const navigationMatches = sourceProviders.filter(
            (provider) =>
              !currentQuery ||
              ui(provider.name).toLocaleLowerCase().includes(currentQuery) ||
              ui(provider.category).toLocaleLowerCase().includes(currentQuery),
          );
          const provider = navigationMatches.at(0);
          if (provider) {
            void navigate({ to: provider.setupPath });
          }
        }}
      />

      <div>
        {sourceCategories.map((category) => {
          const providers = matchingProviders.filter((provider) => provider.category === category);
          if (providers.length === 0) return null;
          return (
            <section
              key={category}
              aria-labelledby={`source-category-${category.toLowerCase()}`}
              className="pt-2"
            >
              <h2
                id={`source-category-${category.toLowerCase()}`}
                className="font-secondary-action text-content-primary"
              >
                {ui(category)}
              </h2>
              <div className="mt-4 grid grid-cols-2 gap-4 sm:flex sm:flex-wrap">
                {providers.map((provider) => {
                  const ProviderIcon = provider.icon;
                  return (
                    <Link
                      key={provider.type}
                      to={provider.setupPath}
                      className="flex min-h-36 min-w-0 flex-col items-center justify-center gap-3 rounded-xl border border-border-subtle bg-surface-sunken p-4 text-center text-content-primary transition-colors hover:border-border-default hover:bg-surface-subtle focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring sm:w-40"
                    >
                      <ProviderIcon className="size-8" aria-hidden="true" />
                      <span className="text-sm font-medium">{ui(provider.name)}</span>
                    </Link>
                  );
                })}
              </div>
            </section>
          );
        })}
      </div>

      {matchingProviders.length === 0 ? (
        <p className="pt-14 text-sm text-content-muted">{ui("No sources match your search.")}</p>
      ) : null}
    </SettingsLayout>
  );
}
