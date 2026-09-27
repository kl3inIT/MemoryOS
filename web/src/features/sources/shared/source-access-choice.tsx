import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { sourceAccessPresentation } from "./source-status-presentation";

type SourceAccess = SourceSummary["access"];

/** Access modes in one dropdown, each named and described like the access badges. */
export function SourceAccessChoice({
  id,
  labelledBy,
  modes,
  value,
  disabled,
  onValueChange,
}: {
  id: string;
  labelledBy: string;
  modes: readonly SourceAccess[];
  value: SourceAccess;
  disabled?: boolean;
  onValueChange: (value: SourceAccess) => void;
}) {
  const ui = useAppTranslation();
  const selected = sourceAccessPresentation[value];
  const SelectedIcon = selected.icon;

  return (
    <Select
      value={value}
      disabled={disabled}
      onValueChange={(next) => onValueChange(next as SourceAccess)}
    >
      <SelectTrigger
        id={id}
        aria-labelledby={labelledBy}
        className="w-full data-[size=default]:h-[var(--control-height-md)]"
      >
        <SelectValue>
          <span className="flex items-center gap-2 text-content-primary">
            <SelectedIcon className="size-4 text-content-secondary" aria-hidden="true" />
            {ui(selected.label)}
          </span>
        </SelectValue>
      </SelectTrigger>
      <SelectContent position="popper" className="max-w-(--radix-select-trigger-width)">
        <SelectGroup>
          {modes.map((mode) => {
            const { label, title, icon: AccessIcon } = sourceAccessPresentation[mode];
            return (
              <SelectItem key={mode} value={mode} className="items-start">
                <AccessIcon className="mt-0.5 text-content-secondary" aria-hidden="true" />
                <span className="flex min-w-0 flex-col gap-0.5">
                  <span className="text-content-primary">{ui(label)}</span>
                  <span className="text-xs whitespace-normal text-content-muted">{ui(title)}</span>
                </span>
              </SelectItem>
            );
          })}
        </SelectGroup>
      </SelectContent>
    </Select>
  );
}
