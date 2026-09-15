import { useAppTranslation } from "@/i18n/use-app-translation";
import { Link, useNavigate } from "@tanstack/react-router";
import { ArrowRight, CloudUpload, SearchX } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Empty, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { Input } from "@/components/ui/input";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { sourceCategories, sourceProviders } from "./source-provider-catalog";

export function SourceCatalogPage() {
  const ui = useAppTranslation();

  const navigate = useNavigate({ from: "/admin/sources/new/" });
  const [searchQuery, setSearchQuery] = useState("");
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
        type="search"
        size="sm"
        value={searchQuery}
        autoFocus
        placeholder={ui("Search sources")}
        aria-label={ui("Search sources")}
        className="bg-surface-sunken"
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
              <ul className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                {providers.map((provider) => {
                  const ProviderIcon = provider.icon;
                  const titleId = `source-provider-${provider.type.toLowerCase()}`;
                  return (
                    <li key={provider.type}>
                      <Link
                        to={provider.setupPath}
                        aria-labelledby={titleId}
                        aria-describedby={`${titleId}-description`}
                        className="group block h-full rounded-2xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
                      >
                        <Card
                          size="sm"
                          className="h-full transition-colors group-hover:border-border-default group-hover:bg-surface-subtle"
                        >
                          <CardHeader className="gap-3">
                            <span className="grid size-10 place-items-center rounded-xl border border-border-subtle bg-surface-base text-content-primary">
                              <ProviderIcon className="size-5" aria-hidden="true" />
                            </span>
                            <CardTitle id={titleId} className="font-main-ui-action">
                              {ui(provider.name)}
                            </CardTitle>
                            <CardDescription id={`${titleId}-description`}>
                              {ui(provider.description)}
                            </CardDescription>
                          </CardHeader>
                          <CardContent className="mt-auto flex items-center gap-1 font-secondary-action text-content-secondary group-hover:text-content-primary">
                            {ui("Set up")}
                            <ArrowRight
                              className="size-4 transition-transform group-hover:translate-x-0.5 motion-reduce:transition-none"
                              aria-hidden="true"
                            />
                          </CardContent>
                        </Card>
                      </Link>
                    </li>
                  );
                })}
              </ul>
            </section>
          );
        })}
      </div>

      {matchingProviders.length === 0 ? (
        <Empty className="border border-dashed border-border-subtle py-12">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <SearchX aria-hidden="true" />
            </EmptyMedia>
            <EmptyTitle className="font-main-ui-action">
              {ui("No sources match your search.")}
            </EmptyTitle>
          </EmptyHeader>
        </Empty>
      ) : null}
    </SettingsLayout>
  );
}
