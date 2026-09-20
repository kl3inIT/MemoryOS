import type { ReactNode, Ref } from "react";
import { Link } from "@tanstack/react-router";
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb";
import { PageHeader } from "@/components/ui/settings-layout";

/**
 * The top of a detail page: the way back to the list this resource came from, then the resource itself.
 * Every detail page returns the same way, instead of a breadcrumb here and a Cancel button there.
 */
export function DetailHeader({
  parent,
  backRef,
  icon,
  iconSize,
  title,
  description,
  actions,
}: {
  /** The list this resource belongs to. `to` and `search` are passed straight to the router link. */
  parent: { label: string; to: string; search?: Record<string, unknown> };
  /** Focus lands here after the resource is deleted or becomes unreadable. */
  backRef?: Ref<HTMLAnchorElement>;
  icon?: ReactNode;
  iconSize?: "sm" | "lg";
  /** The resource's own name, which is also the last breadcrumb. */
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <>
      <Breadcrumb>
        <BreadcrumbList>
          <BreadcrumbItem>
            <BreadcrumbLink asChild>
              <Link
                ref={backRef}
                // The router types every known route; a shared composite takes the path as given.
                to={parent.to as never}
                search={parent.search as never}
                className="rounded-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
              >
                {parent.label}
              </Link>
            </BreadcrumbLink>
          </BreadcrumbItem>
          {title ? (
            <>
              <BreadcrumbSeparator />
              <BreadcrumbItem className="min-w-0">
                <BreadcrumbPage className="truncate">{title}</BreadcrumbPage>
              </BreadcrumbItem>
            </>
          ) : null}
        </BreadcrumbList>
      </Breadcrumb>
      <PageHeader
        icon={icon}
        iconSize={iconSize}
        title={title}
        description={description}
        actions={actions}
      />
    </>
  );
}
