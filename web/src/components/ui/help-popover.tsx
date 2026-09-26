import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ReactNode } from "react";
import { CircleHelp } from "lucide-react";
import { IconButton } from "@/components/ui/icon-button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";

export function HelpPopover({ label, children }: { label: string; children: ReactNode }) {
  const ui = useAppTranslation();

  return (
    <Popover>
      <PopoverTrigger asChild>
        <IconButton
          aria-label={ui("{{v1}} help", { v1: label })}
          size="sm"
          className="text-content-muted"
        >
          <CircleHelp aria-hidden="true" />
        </IconButton>
      </PopoverTrigger>
      <PopoverContent
        aria-label={ui("{{v1}} help", { v1: label })}
        side="bottom"
        align="start"
        sideOffset={8}
        collisionPadding={12}
        className="max-h-(--radix-popover-content-available-height) w-80 max-w-[calc(100vw-1.5rem)] overflow-y-auto p-4"
      >
        {children}
      </PopoverContent>
    </Popover>
  );
}
