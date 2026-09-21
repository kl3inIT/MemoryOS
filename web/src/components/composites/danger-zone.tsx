import type { ReactNode } from "react";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";

/**
 * The last block of a detail page: what deleting or emptying this resource destroys, and the action that does it.
 * A destructive action lives here rather than in the page's action menu, where a mis-click is one press away.
 */
export function DangerZone({
  icon,
  title,
  description,
  action,
  className,
  children,
}: {
  icon?: ReactNode;
  /** What the action destroys, named for this resource. */
  title: ReactNode;
  description?: ReactNode;
  /** Usually a `ConfirmDialog` whose trigger is a danger-toned Button. */
  action?: ReactNode;
  className?: string;
  /** Further rows, for a resource with more than one destructive action. */
  children?: ReactNode;
}) {
  const ui = useAppTranslation();
  return (
    <section aria-labelledby="danger-zone-heading" className={cn("flex flex-col gap-3", className)}>
      <h2 id="danger-zone-heading" className="font-heading-h3 text-content-primary">
        {ui("Danger Zone")}
      </h2>
      <SettingRows className="border-status-danger-border">
        <SettingRow icon={icon} title={title} description={description} control={action} />
        {children}
      </SettingRows>
    </section>
  );
}
