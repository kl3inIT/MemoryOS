import { Link, useNavigate } from "@tanstack/react-router";
import { CloudUpload } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { sourceCategories, sourceProviders } from "./source-provider-catalog";

export function SourceCatalogPage() {
  const navigate = useNavigate({ from: "/admin/sources/new/" });
  const [searchQuery, setSearchQuery] = useState("");
  const normalizedQuery = searchQuery.trim().toLowerCase();
  const matchingProviders = sourceProviders.filter(
    (provider) =>
      !normalizedQuery ||
      provider.name.toLowerCase().includes(normalizedQuery) ||
      provider.category.toLowerCase().includes(normalizedQuery),
  );

  return (
    <section className="w-full px-5 py-8 sm:px-8">
      <header className="flex items-center justify-between gap-4 border-b border-border-subtle pb-6">
        <div>
          <CloudUpload className="size-7 text-content-primary" aria-hidden="true" />
          <h1 className="mt-2 font-heading-h3 text-content-primary">Add a source</h1>
        </div>
        <Button asChild size="sm">
          <Link to="/admin">See sources</Link>
        </Button>
      </header>

      <Input
        type="search"
        size="sm"
        value={searchQuery}
        autoFocus
        placeholder="Search sources"
        aria-label="Search sources"
        className="mt-5 bg-surface-sunken"
        onChange={(event) => setSearchQuery(event.target.value)}
        onKeyDown={(event) => {
          if (event.key !== "Enter") return;
          const currentQuery = event.currentTarget.value.trim().toLowerCase();
          const navigationMatches = sourceProviders.filter(
            (provider) =>
              !currentQuery ||
              provider.name.toLowerCase().includes(currentQuery) ||
              provider.category.toLowerCase().includes(currentQuery),
          );
          const provider = navigationMatches.at(0);
          if (navigationMatches.length === 1 && provider) {
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
              className="pt-14"
            >
              <h2
                id={`source-category-${category.toLowerCase()}`}
                className="font-secondary-action text-content-primary"
              >
                {category}
              </h2>
              <div className="flex flex-wrap gap-4 p-4">
                {providers.map((provider) => {
                  const ProviderIcon = provider.icon;
                  return (
                    <Link
                      key={provider.type}
                      to={provider.setupPath}
                      className="flex w-40 cursor-pointer flex-col items-center justify-center rounded-lg bg-surface-sunken p-4 text-center text-content-primary shadow-md transition-colors hover:bg-surface-subtle focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
                    >
                      <ProviderIcon className="size-6" aria-hidden="true" />
                      <span className="mt-2 text-sm font-medium">{provider.name}</span>
                    </Link>
                  );
                })}
              </div>
            </section>
          );
        })}
      </div>

      {matchingProviders.length === 0 ? (
        <p className="pt-14 text-sm text-content-muted">No sources match your search.</p>
      ) : null}
    </section>
  );
}
