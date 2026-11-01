import type { ReactNode } from "react";
import { Search, X } from "lucide-react";
import { TooltipIconButton } from "@/components/composites/tooltip-icon-button";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { useAppTranslation } from "@/i18n/use-app-translation";

/**
 * The search box of a list: a search icon, the text, and a button that clears it once something is typed. Every
 * list that filters by typing uses this one, so a change to its look or behaviour is made here. `children` is
 * anything the field carries at its end, such as a choice of what to search.
 */
export function SearchField({
  value,
  onChange,
  label,
  className,
  children,
}: {
  value: string;
  onChange: (next: string) => void;
  /** Placeholder and accessible name. */
  label: string;
  className?: string;
  children?: ReactNode;
}) {
  const ui = useAppTranslation();
  return (
    <InputGroup className={className}>
      <InputGroupAddon>
        <Search />
      </InputGroupAddon>
      <InputGroupInput
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={label}
        aria-label={label}
        maxLength={200}
      />
      {value.length > 0 && (
        <InputGroupAddon align="inline-end">
          <TooltipIconButton
            size="sm"
            prominence="internal"
            aria-label={ui("Xóa từ khóa tìm kiếm")}
            onClick={() => onChange("")}
          >
            <X />
          </TooltipIconButton>
        </InputGroupAddon>
      )}
      {children}
    </InputGroup>
  );
}
