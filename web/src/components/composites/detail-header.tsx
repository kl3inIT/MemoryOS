import type { ReactNode, Ref } from "react";
import { Link, type RegisteredRouter, type ValidateLinkOptions } from "@tanstack/react-router";
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb";
import { PageHeader } from "@/components/ui/settings-layout";

type DetailHeaderProps<TRouter extends RegisteredRouter = RegisteredRouter, TOptions = unknown> = {
  /** The list this resource belongs to: its label and the router link options that open it. */
  parent: ValidateLinkOptions<TRouter, TOptions> & { label: string };
  /** Focus lands here after the resource is deleted or becomes unreadable. */
  backRef?: Ref<HTMLAnchorElement>;
  icon?: ReactNode;
  iconSize?: "sm" | "lg";
  /** The resource's own name, which is also the last breadcrumb; absent while it is still loading. */
  title?: string;
  description?: ReactNode;
  actions?: ReactNode;
};

/**
 * The top of a detail page: the way back to the list this resource came from, then the resource itself.
 * Every detail page returns the same way, instead of a breadcrumb here and a Cancel button there.
 */
export function DetailHeader<TRouter extends RegisteredRouter, TOptions>(
  props: DetailHeaderProps<TRouter, TOptions>,
): ReactNode;
export function DetailHeader({
  parent,
  backRef,
  icon,
  iconSize,
  title,
  description,
  actions,
}: DetailHeaderProps): ReactNode {
  const { label, ...link } = parent;
  return (
    <>
      <Breadcrumb>
        <BreadcrumbList>
          <BreadcrumbItem>
            <BreadcrumbLink asChild>
              <Link
                {...link}
                ref={backRef}
                className="rounded-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
              >
                {label}
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
      {title ? (
        <PageHeader
          icon={icon}
          iconSize={iconSize}
          title={title}
          description={description}
          actions={actions}
        />
      ) : null}
    </>
  );
}
