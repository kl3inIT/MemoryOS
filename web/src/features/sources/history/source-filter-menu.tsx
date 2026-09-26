import { ChevronDown, CirclePlus, X } from "lucide-react";
import { useRef } from "react";
import { Button } from "@/components/ui/button";
import { ButtonGroup } from "@/components/ui/button-group";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuLabel,
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
 * A multi-choice filter chip in Stripe's list style: a dashed "+ Filter status" while unset, then
 * "Status | chosen values" with its own clear button. The menu stays open while values are ticked.
 */
export function SourceFilterMenu({
  label,
  addLabel,
  clearLabel,
  options,
  value,
  onValueChange,
}: {
  label: string;
  /** Names the action while the filter is unset, where the bare label reads as a column heading. */
  addLabel: string;
  clearLabel: string;
  options: readonly SourceFilterOption[];
  value: readonly string[];
  onValueChange: (value: string[]) => void;
}) {
  const ui = useAppTranslation();
  const trigger = useRef<HTMLButtonElement>(null);
  const selected = options.filter((option) => value.includes(option.value));
  const toggle = (changed: string, checked: boolean) =>
    // Values follow the option order, so the chip and the request stay stable.
    onValueChange(
      options
        .map((option) => option.value)
        .filter((option) => (option === changed ? checked : value.includes(option))),
    );

  return (
    <div
      className={cn(
        "inline-flex max-w-full items-center rounded-lg border border-border-default",
        selected.length ? "bg-surface-raised" : "border-dashed",
      )}
    >
      <ButtonGroup className="min-w-0">
        {selected.length ? (
          <IconButton
            size="sm"
            aria-label={ui(clearLabel)}
            onClick={() => {
              onValueChange([]);
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
              className={cn("min-w-0", selected.length && "pl-1")}
            >
              {selected.length ? null : <CirclePlus aria-hidden="true" />}
              {selected.length ? ui(label) : ui(addLabel)}
              {selected.length ? (
                <>
                  <span className="sr-only">: </span>
                  <span aria-hidden="true" className="h-4 w-px shrink-0 bg-border-default" />
                  <span aria-hidden="true" className="flex shrink-0 -space-x-0.5">
                    {selected.map((option) => (
                      <ToneDot key={option.value} tone={option.tone} />
                    ))}
                  </span>
                  <span className="max-w-56 truncate text-content-primary">
                    {selected.map((option) => ui(option.label)).join(", ")}
                  </span>
                  <ChevronDown aria-hidden="true" />
                </>
              ) : null}
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="w-auto min-w-56">
            <DropdownMenuGroup>
              <DropdownMenuLabel>{ui(label)}</DropdownMenuLabel>
              <DropdownMenuSeparator />
              {options.map((option) => (
                <DropdownMenuCheckboxItem
                  key={option.value}
                  checked={value.includes(option.value)}
                  onCheckedChange={(checked) => toggle(option.value, checked)}
                  // Several values are usually ticked in one visit.
                  onSelect={(event) => event.preventDefault()}
                >
                  <ToneDot tone={option.tone} />
                  {ui(option.label)}
                </DropdownMenuCheckboxItem>
              ))}
            </DropdownMenuGroup>
          </DropdownMenuContent>
        </DropdownMenu>
      </ButtonGroup>
    </div>
  );
}
