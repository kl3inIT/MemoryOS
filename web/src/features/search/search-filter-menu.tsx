import { useAppTranslation } from "@/i18n/use-app-translation";
import { ChevronDown } from "lucide-react";
import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

export type SearchFilterOption = {
  value: string;
  label: string;
  /** Result count shown after the label, when the option has one. */
  count?: number;
  disabled?: boolean;
};

type SearchFilterMenuProps = {
  label: string;
  value: string;
  options: readonly SearchFilterOption[];
  icon: ReactNode;
  onChange: (value: string) => void;
  className?: string;
};

/** One compact filter: the trigger names the filter and its choice, the menu offers every choice. */
export function SearchFilterMenu({
  label,
  value,
  options,
  icon,
  onChange,
  className,
}: SearchFilterMenuProps) {
  const ui = useAppTranslation();

  const selected = options.find((option) => option.value === value) ?? options[0];
  if (!selected) return null;

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          type="button"
          size="sm"
          prominence="tertiary"
          aria-label={ui("{{v1}}: {{v2}}", { v1: label, v2: ui(selected.label) })}
          className={className}
        >
          <span data-icon="inline-start" className="text-content-muted" aria-hidden>
            {icon}
          </span>
          <span className="max-w-48 truncate">{ui(selected.label)}</span>
          <ChevronDown data-icon="inline-end" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" sideOffset={6} collisionPadding={12} className="min-w-52">
        <DropdownMenuGroup>
          <DropdownMenuLabel>{label}</DropdownMenuLabel>
          <DropdownMenuRadioGroup value={value} onValueChange={onChange}>
            {options.map((option) => (
              <DropdownMenuRadioItem
                key={option.value}
                value={option.value}
                disabled={option.disabled}
              >
                {ui(option.label)}
                {option.count === undefined ? null : (
                  <span className="ml-auto pl-4 font-secondary-action text-content-muted tabular-nums">
                    {option.count}
                  </span>
                )}
              </DropdownMenuRadioItem>
            ))}
          </DropdownMenuRadioGroup>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
