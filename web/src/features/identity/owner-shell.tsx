import { ArrowUp } from "lucide-react";
import { Brand } from "@/components/brand";
import { AppShell } from "@/components/app-shell/app-shell";
import { Button } from "@/components/ui/button";

export function OwnerShell() {
  return (
    <AppShell pageTitle="New Session">
      <section className="flex min-h-full items-center justify-center px-5 py-12 sm:px-8">
        <div className="w-full max-w-2xl -translate-y-8 sm:-translate-y-12">
          <header className="mb-7">
            <Brand compact />
            <h1 className="mt-5 font-heading-h2 text-content-primary">How can I help?</h1>
          </header>

          <div
            className="overflow-hidden rounded-2xl border border-border-default bg-surface-raised shadow-md"
            aria-label="Assistant composer"
          >
            <textarea
              aria-label="Ask MemoryOS"
              placeholder="How can I help you today?"
              disabled
              rows={3}
              className="block w-full resize-none bg-transparent px-5 pt-5 font-main-ui-body text-content-primary outline-none placeholder:text-content-muted disabled:cursor-not-allowed disabled:opacity-100"
            />
            <div className="flex justify-end px-4 pb-4">
              <Button
                type="button"
                size="icon-lg"
                aria-label="Send message"
                disabled
                className="rounded-xl"
              >
                <ArrowUp />
              </Button>
            </div>
          </div>
        </div>
      </section>
    </AppShell>
  );
}
