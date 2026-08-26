import { DatabaseZap } from "lucide-react";

export function SourcesPage() {
  return (
    <section className="mx-auto w-full max-w-5xl px-5 py-8 sm:px-8 sm:py-12">
      <header className="mb-8">
        <h1 className="font-heading-h2 text-content-primary">Sources</h1>
      </header>

      <div className="rounded-2xl border border-dashed border-border-default bg-surface-subtle px-6 py-14 text-center">
        <span className="mx-auto mb-5 grid size-11 place-items-center rounded-xl border border-border-subtle bg-surface-raised text-content-secondary shadow-xs">
          <DatabaseZap className="size-5" aria-hidden="true" />
        </span>
        <h2 className="font-heading-h3 text-content-primary">No sources connected</h2>
      </div>
    </section>
  );
}
