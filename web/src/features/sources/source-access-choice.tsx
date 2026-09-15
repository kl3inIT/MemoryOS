import { useAppTranslation } from "@/i18n/use-app-translation";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { sourceAccessPresentation } from "./source-status-presentation";

type SourceAccess = SourceSummary["access"];

/** Access modes as selectable cards, named and described like the access badges. */
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

  return (
    <RadioGroup
      aria-labelledby={labelledBy}
      value={value}
      disabled={disabled}
      onValueChange={(next) => onValueChange(next as SourceAccess)}
      className="gap-3 sm:grid-cols-[repeat(auto-fit,minmax(11rem,1fr))]"
    >
      {modes.map((mode) => {
        const { label, title, icon: AccessIcon } = sourceAccessPresentation[mode];
        const itemId = `${id}-${mode.toLowerCase()}`;
        return (
          <label
            key={mode}
            htmlFor={itemId}
            className="flex cursor-pointer items-start gap-3 rounded-xl border border-border-subtle bg-surface-raised p-4 transition-colors hover:bg-surface-subtle has-focus-visible:ring-2 has-focus-visible:ring-focus-ring has-disabled:cursor-default has-disabled:opacity-60 has-[[data-state=checked]]:border-content-primary has-[[data-state=checked]]:bg-surface-subtle"
          >
            <RadioGroupItem
              id={itemId}
              value={mode}
              aria-labelledby={`${itemId}-label`}
              aria-describedby={`${itemId}-description`}
              className="mt-0.5"
            />
            <span className="min-w-0">
              <span
                id={`${itemId}-label`}
                className="flex items-center gap-1.5 font-secondary-action text-content-primary"
              >
                <AccessIcon className="size-4 shrink-0 text-content-secondary" aria-hidden="true" />
                {ui(label)}
              </span>
              <span
                id={`${itemId}-description`}
                className="mt-1 block font-secondary-body text-content-muted"
              >
                {ui(title)}
              </span>
            </span>
          </label>
        );
      })}
    </RadioGroup>
  );
}
