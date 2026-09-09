import { Link } from "@tanstack/react-router";
import { FileUp, Library, Plus } from "lucide-react";
import type { ReactNode } from "react";
import { DropdownMenu } from "radix-ui";
import { IconButton } from "@/components/ui/icon-button";
import {
  useCapabilityAuthority,
  useGlobalCapability,
} from "@/features/identity/application-session-context";

export function SearchAddMenu() {
  const canManageSources = useGlobalCapability("SOURCES_MANAGE");
  const canReadSources = useCapabilityAuthority("SOURCES_READ") !== "none";

  if (!canManageSources && !canReadSources) return null;

  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild>
        <IconButton
          type="button"
          size="lg"
          prominence="internal"
          aria-label="Add to search"
          title="Add to search"
          className="data-[state=open]:bg-surface-subtle"
        >
          <Plus aria-hidden="true" />
        </IconButton>
      </DropdownMenu.Trigger>

      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align="start"
          sideOffset={8}
          collisionPadding={12}
          className="z-50 w-[min(20rem,calc(100vw-1.5rem))] rounded-xl border border-border-default bg-surface-overlay p-1.5 shadow-md outline-none data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out data-[state=open]:fade-in data-[state=open]:slide-in-from-bottom-1 motion-reduce:animate-none"
        >
          <DropdownMenu.Label className="px-3 pt-2 pb-1 font-secondary-action text-content-muted">
            Add content to Search
          </DropdownMenu.Label>
          {canManageSources ? (
            <SearchAddMenuItem
              to="/admin/sources/new/file"
              icon={<FileUp className="size-4.5" />}
              label="Upload a document"
              description="Add a file to a searchable source"
            />
          ) : null}
          {canReadSources ? (
            <SearchAddMenuItem
              to="/admin"
              icon={<Library className="size-4.5" />}
              label="Browse connected sources"
              description="Review the content available to Search"
            />
          ) : null}
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}

function SearchAddMenuItem({
  to,
  icon,
  label,
  description,
}: {
  to: "/admin" | "/admin/sources/new/file";
  icon: ReactNode;
  label: string;
  description: string;
}) {
  return (
    <DropdownMenu.Item asChild>
      <Link
        to={to}
        className="flex min-h-14 cursor-pointer select-none items-center gap-3 rounded-lg px-3 py-2 text-left outline-none data-[highlighted]:bg-surface-subtle focus-visible:ring-3 focus-visible:ring-focus-ring/30"
      >
        <span
          className="grid size-9 shrink-0 place-items-center rounded-lg bg-surface-sunken text-content-secondary"
          aria-hidden="true"
        >
          {icon}
        </span>
        <span className="min-w-0">
          <span className="block font-main-ui-body text-content-primary">{label}</span>
          <span className="mt-0.5 block font-secondary-body text-content-muted">{description}</span>
        </span>
      </Link>
    </DropdownMenu.Item>
  );
}
