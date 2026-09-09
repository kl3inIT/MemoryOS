import { Check, ChevronDown } from "lucide-react";
import type { ReactNode } from "react";
import { DropdownMenu } from "radix-ui";
import { Button } from "@/components/ui/button";

export type SearchFilterOption = {
  value: string;
  label: string;
};

type SearchFilterMenuProps = {
  label: string;
  value: string;
  options: readonly SearchFilterOption[];
  icon: ReactNode;
  onChange: (value: string) => void;
};

export function SearchFilterMenu({ label, value, options, icon, onChange }: SearchFilterMenuProps) {
  const selected = options.find((option) => option.value === value) ?? options[0];

  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild>
        <Button
          type="button"
          size="sm"
          prominence="tertiary"
          aria-label={`${label}: ${selected.label}`}
          className="max-w-full gap-2 px-2.5 data-[state=open]:bg-surface-subtle"
        >
          <span className="grid size-4 shrink-0 place-items-center text-content-muted" aria-hidden>
            {icon}
          </span>
          <span className="truncate">{selected.label}</span>
          <ChevronDown className="size-3.5 shrink-0 text-content-muted" aria-hidden="true" />
        </Button>
      </DropdownMenu.Trigger>

      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align="start"
          sideOffset={6}
          collisionPadding={12}
          className="z-50 min-w-52 rounded-xl border border-border-default bg-surface-overlay p-1.5 shadow-md outline-none data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out data-[state=open]:fade-in motion-reduce:animate-none"
        >
          <DropdownMenu.Label className="px-3 py-2 font-secondary-action text-content-muted">
            {label}
          </DropdownMenu.Label>
          <DropdownMenu.RadioGroup value={value} onValueChange={onChange}>
            {options.map((option) => (
              <DropdownMenu.RadioItem
                key={option.value}
                value={option.value}
                className="relative flex min-h-9 cursor-pointer select-none items-center rounded-lg py-2 pr-3 pl-9 font-main-ui-body text-content-secondary outline-none data-[disabled]:pointer-events-none data-[highlighted]:bg-surface-subtle data-[highlighted]:text-content-primary data-[state=checked]:text-content-primary"
              >
                <DropdownMenu.ItemIndicator className="absolute left-3 grid size-4 place-items-center text-content-primary">
                  <Check className="size-3.5" aria-hidden="true" />
                </DropdownMenu.ItemIndicator>
                {option.label}
              </DropdownMenu.RadioItem>
            ))}
          </DropdownMenu.RadioGroup>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
