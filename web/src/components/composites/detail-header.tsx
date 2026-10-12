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
import { PageHeader } from "@/components/composites/settings-layout";
import { cn } from "@/lib/utils";

type DetailBreadcrumbProps<
  TRouter extends RegisteredRouter = RegisteredRouter,
  TOptions = unknown,
> = {
  /** The list this resource belongs to: its label and the router link options that open it. */
  parent: ValidateLinkOptions<TRouter, TOptions> & { label: string };
  /** Focus lands here after the resource is deleted or becomes unreadable. */
  backRef?: Ref<HTMLAnchorElement>;
  /** The resource's own name, the last breadcrumb; absent while it is still loading. */
  title?: string;
  /** Kept on one line and cut short, for the shell header, which has one. */
  oneLine?: boolean;
};

/**
 * The way back from a resource to the list it came from. A page whose shell header is shown puts it there, in
 * place of a title the page already carries; every other detail page gets it from `DetailHeader`.
 */
export function DetailBreadcrumb<TRouter extends RegisteredRouter, TOptions>(
  props: DetailBreadcrumbProps<TRouter, TOptions>,
): ReactNode;
export function DetailBreadcrumb({ parent, backRef, title, oneLine }: DetailBreadcrumbProps) {
  const { label, ...link } = parent;
  return (
    <Breadcrumb className="min-w-0">
      <BreadcrumbList className={cn(oneLine && "flex-nowrap")}>
        <BreadcrumbItem className="shrink-0">
          <BreadcrumbLink asChild>
            <Link
              {...link}
              ref={backRef}
              className="rounded-sm focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:outline-none"
            >
              {label}
            </Link>
          </BreadcrumbLink>
        </BreadcrumbItem>
        {title ? (
          <>
            <BreadcrumbSeparator />
            <BreadcrumbItem className="min-w-0">
              <BreadcrumbPage className="min-w-0">
                <span className={cn(oneLine && "block truncate")}>{title}</span>
              </BreadcrumbPage>
            </BreadcrumbItem>
          </>
        ) : null}
      </BreadcrumbList>
    </Breadcrumb>
  );
}

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
  return (
    <>
      <DetailBreadcrumb parent={parent} backRef={backRef} title={title} />
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
