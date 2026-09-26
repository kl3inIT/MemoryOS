import type { HTMLAttributes, ReactNode } from "react";
import {
  Item,
  ItemActions,
  ItemContent,
  ItemDescription,
  ItemMedia,
  ItemTitle,
} from "@/components/ui/item";
import { cn } from "@/lib/utils";

/** Unframed brand-mark slot shared by provider cards and the expandable connection rows on the Models page. */
export const providerTileClassName =
  "grid size-9 shrink-0 place-items-center [&_img]:size-7 [&_svg:not([class*='size-'])]:size-6";

type ProviderCardProps = Omit<HTMLAttributes<HTMLElement>, "title"> & {
  as?: "div" | "section" | "li";
  logo: ReactNode;
  name: ReactNode;
  description?: ReactNode;
  /** The provider currently in use for its capability. */
  selected?: boolean;
  actions?: ReactNode;
};

/**
 * One connectable provider (model vendor, search engine, crawler): brand tile, name, a one-line
 * description, then status and actions. The page decides what connecting or selecting means.
 */
export function ProviderCard({
  as: Element = "div",
  logo,
  name,
  description,
  selected = false,
  actions,
  className,
  children,
  ...props
}: ProviderCardProps) {
  return (
    <Item
      asChild
      variant="outline"
      size="lg"
      data-selected={selected || undefined}
      className={className}
    >
      <Element {...props}>
        <ItemMedia
          className={cn(
            providerTileClassName,
            "group-has-data-[slot=item-description]/item:translate-y-0 group-has-data-[slot=item-description]/item:self-center",
          )}
        >
          {logo}
        </ItemMedia>
        {/* A readable minimum makes actions wrap below the name instead of squeezing it. */}
        <ItemContent className="min-w-28">
          <ItemTitle>{name}</ItemTitle>
          {description && (
            <ItemDescription className="wrap-anywhere">{description}</ItemDescription>
          )}
        </ItemContent>
        {actions && <ItemActions className="flex-wrap justify-end">{actions}</ItemActions>}
        {children}
      </Element>
    </Item>
  );
}
