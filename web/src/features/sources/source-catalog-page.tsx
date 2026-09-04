import { Link } from "@tanstack/react-router";
import { ArrowLeft, ArrowRight } from "lucide-react";
import { sourceProviders } from "./source-provider-catalog";

export function SourceCatalogPage() {
  return (
    <section className="mx-auto w-full max-w-6xl px-5 py-8 sm:px-8 sm:py-12">
      <Link
        to="/admin"
        className="inline-flex items-center gap-2 font-secondary-action text-content-secondary transition-colors hover:text-content-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
      >
        <ArrowLeft className="size-4" aria-hidden="true" />
        Connected sources
      </Link>

      <header className="mt-6 border-b border-border-subtle pb-7">
        <p className="font-secondary-action text-content-muted">Knowledge sources</p>
        <h1 className="mt-1 font-heading-h2 text-content-primary">Add a source</h1>
        <p className="mt-2 max-w-2xl font-main-ui-body text-content-secondary">
          Choose where MemoryOS should import durable Tenant knowledge from.
        </p>
      </header>

      <div className="mt-8">
        <h2 className="font-heading-h3 text-content-primary">Files</h2>
        <p className="mt-1 font-secondary-body text-content-muted">
          Add documents that your organization owns and manages directly.
        </p>

        <div className="mt-4 grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {sourceProviders.map((provider) => {
            const ProviderIcon = provider.icon;
            return (
              <Link
                key={provider.type}
                to={provider.setupPath}
                className="group flex min-h-44 flex-col rounded-2xl border border-border-subtle bg-surface-raised p-5 transition-[background-color,border-color,transform] duration-200 hover:-translate-y-0.5 hover:border-border-default hover:bg-surface-subtle focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
              >
                <span className="grid size-11 place-items-center rounded-xl border border-border-subtle bg-surface-subtle text-content-primary transition-colors group-hover:bg-surface-raised">
                  <ProviderIcon className="size-5" aria-hidden="true" />
                </span>
                <div className="mt-5 flex items-end justify-between gap-5">
                  <div className="min-w-0">
                    <p className="font-heading-h3 text-content-primary">{provider.name}</p>
                    <p className="mt-1 font-secondary-body text-content-muted">
                      {provider.description}
                    </p>
                  </div>
                  <ArrowRight
                    className="mb-1 size-4 shrink-0 text-content-muted transition-transform group-hover:translate-x-0.5 group-hover:text-content-primary"
                    aria-hidden="true"
                  />
                </div>
              </Link>
            );
          })}
        </div>
      </div>
    </section>
  );
}
