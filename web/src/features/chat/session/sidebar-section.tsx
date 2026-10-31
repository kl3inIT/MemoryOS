import type { ReactNode } from "react";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { useSidebarSectionOpen } from "./sidebar-preferences";

/**
 * A section of the home sidebar whose heading folds it, remembered per browser as LobeHub's sidebar remembers each
 * section, so a long list never pushes the next section out of reach.
 */
export function SidebarSection({
  id,
  title,
  storageKey,
  action,
  className,
  children,
}: {
  id: string;
  title: string;
  storageKey: string;
  action?: ReactNode;
  className?: string;
  children: ReactNode;
}) {
  const [open, setOpen] = useSidebarSectionOpen(storageKey);
  return (
    <Collapsible asChild open={open} onOpenChange={setOpen}>
      <section aria-labelledby={id} className={className}>
        <div className="mb-1 flex h-8 items-center justify-between rounded-lg pr-0.5 pl-2 hover:bg-surface-sunken">
          <h2 id={id} className="min-w-0 flex-1 text-sm font-medium text-content-secondary">
            <CollapsibleTrigger asChild>
              <button
                type="button"
                className="flex h-8 w-full items-center rounded-lg text-left outline-none hover:text-content-primary focus-visible:ring-2 focus-visible:ring-ring"
              >
                {title}
              </button>
            </CollapsibleTrigger>
          </h2>
          {action}
        </div>
        <CollapsibleContent>{children}</CollapsibleContent>
      </section>
    </Collapsible>
  );
}
