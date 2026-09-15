import { ChevronDown, CirclePlus, X } from "lucide-react";
import { useRef } from "react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { IconButton } from "@/components/ui/icon-button";
import type { StatusTone } from "@/components/ui/status-badge";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";

export type SourceFilterOption = { value: string; label: string; tone: StatusTone };

const toneDots: Record<StatusTone, string> = {
  success: "bg-status-success-content",
  warning: "bg-status-warning-content",
  danger: "bg-status-danger-content",
  info: "bg-status-info-content",
  neutral: "bg-content-muted",
};

function ToneDot({ tone }: { tone: StatusTone }) {
  return <span aria-hidden="true" className={cn("size-2 shrink-0 rounded-full", toneDots[tone])} />;
}

/**
 * A single-choice filter chip in Stripe's list style: a dashed "+ Status" while unset, then
 * "Status | value" with its own clear button.
 */
export function SourceFilterMenu({
  label,
  clearLabel,
  options,
  value,
  onValueChange,
}: {
  label: string;
  clearLabel: string;
  options: readonly SourceFilterOption[];
  value: string;
  onValueChange: (value: string) => void;
}) {
  const ui = useAppTranslation();
  const trigger = useRef<HTMLButtonElement>(null);
  const selected = options.find((option) => option.value === value);

  return (
    <div
      className={cn(
        "inline-flex items-center rounded-lg border border-border-default",
        selected ? "bg-surface-raised" : "border-dashed",
      )}
    >
      {selected ? (
        <IconButton
          size="sm"
          aria-label={ui(clearLabel)}
          className="rounded-r-none"
          onClick={() => {
            onValueChange("");
            // The clear button unmounts with the value, so focus stays on the chip.
            trigger.current?.focus();
          }}
        >
          <X aria-hidden="true" />
        </IconButton>
      ) : null}
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            ref={trigger}
            size="sm"
            prominence="tertiary"
            className={selected ? "rounded-l-none pl-1" : undefined}
          >
            {selected ? null : <CirclePlus aria-hidden="true" />}
            {ui(label)}
            {selected ? (
              <>
                <span className="sr-only">: </span>
                <span aria-hidden="true" className="h-4 w-px bg-border-default" />
                <ToneDot tone={selected.tone} />
                <span className="text-content-primary">{ui(selected.label)}</span>
                <ChevronDown aria-hidden="true" />
              </>
            ) : null}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" className="w-auto min-w-56">
          <DropdownMenuLabel>{ui(label)}</DropdownMenuLabel>
          <DropdownMenuSeparator />
          <DropdownMenuRadioGroup value={value} onValueChange={onValueChange}>
            {options.map((option) => (
              <DropdownMenuRadioItem key={option.value} value={option.value}>
                <ToneDot tone={option.tone} />
                {ui(option.label)}
              </DropdownMenuRadioItem>
            ))}
          </DropdownMenuRadioGroup>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}
