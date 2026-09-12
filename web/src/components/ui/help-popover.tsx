import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ReactNode } from "react";
import { CircleHelp } from "lucide-react";
import { Popover } from "radix-ui";
import { IconButton } from "@/components/ui/icon-button";

export function HelpPopover({ label, children }: { label: string; children: ReactNode }) {
  const ui = useAppTranslation();

  return (
    <Popover.Root>
      <Popover.Trigger asChild>
        <IconButton
          aria-label={ui("{{v1}} help", { v1: label })}
          size="sm"
          className="text-content-muted"
        >
          <CircleHelp aria-hidden="true" />
        </IconButton>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          aria-label={ui("{{v1}} help", { v1: label })}
          side="bottom"
          align="start"
          sideOffset={8}
          collisionPadding={12}
          className="z-50 max-h-[var(--radix-popover-content-available-height)] w-80 max-w-[calc(100vw-1.5rem)] space-y-3 overflow-y-auto rounded-lg border border-border-subtle bg-surface-overlay p-4 text-sm text-content-secondary shadow-md outline-none"
        >
          {children}
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}
