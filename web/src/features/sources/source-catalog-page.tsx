import { Link } from "@tanstack/react-router";
import { Button } from "@/components/ui/button";
import { sourceCategories, sourceProviders } from "./source-provider-catalog";

export function SourceCatalogPage() {
  return (
    <section className="mx-auto w-full max-w-5xl px-5 py-8 sm:px-8 sm:py-12">
      <header className="flex items-center justify-between gap-4 border-b border-border-subtle pb-6">
        <h1 className="font-heading-h2 text-content-primary">Add a source</h1>
        <Button asChild prominence="secondary">
          <Link to="/admin">View sources</Link>
        </Button>
      </header>

      <div className="space-y-8 pt-7">
        {sourceCategories.map((category) => {
          const providers = sourceProviders.filter((provider) => provider.category === category);
          if (providers.length === 0) return null;
          return (
            <section key={category} aria-labelledby={`source-category-${category.toLowerCase()}`}>
              <h2
                id={`source-category-${category.toLowerCase()}`}
                className="font-heading-h3 text-content-primary"
              >
                {category}
              </h2>
              <div className="mt-3 flex flex-wrap gap-4">
                {providers.map((provider) => {
                  const ProviderIcon = provider.icon;
                  return (
                    <Link
                      key={provider.type}
                      to={provider.setupPath}
                      className="flex h-28 w-40 flex-col items-center justify-center rounded-lg bg-surface-raised p-4 text-center text-content-primary shadow-sm ring-1 ring-border-subtle transition-colors hover:bg-surface-subtle focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
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
    </section>
  );
}
