import { ChevronDown } from "lucide-react";
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
import { Separator } from "@/components/ui/separator";
import { useAppTranslation } from "@/i18n/use-app-translation";

export type SourceFilterOption = { value: string; label: string };

/** A single-choice filter whose trigger shows the chosen value, as in the Sources list. */
export function SourceFilterMenu({
  label,
  allLabel,
  options,
  value,
  onValueChange,
}: {
  label: string;
  allLabel: string;
  options: readonly SourceFilterOption[];
  value: string;
  onValueChange: (value: string) => void;
}) {
  const ui = useAppTranslation();

  const selected = options.find((option) => option.value === value);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button size="sm" prominence="secondary">
          {ui(label)}
          {selected ? (
            <>
              <Separator orientation="vertical" className="h-4" />
              <span className="text-content-primary">{ui(selected.label)}</span>
            </>
          ) : null}
          <ChevronDown aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-auto min-w-56">
        <DropdownMenuLabel>{ui(label)}</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuRadioGroup value={value} onValueChange={onValueChange}>
          <DropdownMenuRadioItem value="">{ui(allLabel)}</DropdownMenuRadioItem>
          {options.map((option) => (
            <DropdownMenuRadioItem key={option.value} value={option.value}>
              {ui(option.label)}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
