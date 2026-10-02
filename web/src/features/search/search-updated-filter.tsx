import { Check, ChevronDown, Clock3 } from "lucide-react";
import { useState } from "react";
import type { DateRange } from "react-day-picker";
import { enUS, vi } from "react-day-picker/locale";
import { Button } from "@/components/ui/button";
import { Calendar } from "@/components/ui/calendar";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { useIsMobile } from "@/hooks/use-mobile";
import { formatUiDate, uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { TIME_RANGE_OPTIONS, type SearchTimeRange } from "./search-options";

/** A `YYYY-MM-DD` day of the Search address as that calendar day in the browser. */
function toDate(day: string) {
  const [year = 1970, month = 1, date = 1] = day.split("-").map(Number);
  return new Date(year, month - 1, date);
}

function toDay(date: Date) {
  const month = String(date.getMonth() + 1).padStart(2, "0");
  return `${date.getFullYear()}-${month}-${String(date.getDate()).padStart(2, "0")}`;
}

type SearchUpdatedFilterProps = {
  timeRange: SearchTimeRange;
  /** The picked range's first and last day, when a range rather than a preset is applied. */
  from: string | undefined;
  to: string | undefined;
  onPreset: (value: SearchTimeRange) => void;
  onRange: (from: string, to: string) => void;
};

/**
 * The update filter, as Hex and Slite offer it: a preset applies at once, a range of days is picked on the calendar
 * and applied. The trigger names the choice like the other filter menus.
 */
export function SearchUpdatedFilter({
  timeRange,
  from,
  to,
  onPreset,
  onRange,
}: SearchUpdatedFilterProps) {
  const ui = useAppTranslation();
  const mobile = useIsMobile();
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<DateRange | undefined>();
  const day = (value: string) =>
    formatUiDate(toDate(value), { day: "2-digit", month: "2-digit", year: "numeric" });
  const last = to ?? from;
  const label =
    from === undefined
      ? ui(TIME_RANGE_OPTIONS.find((option) => option.value === timeRange)?.label ?? "All time")
      : from === last || last === undefined
        ? day(from)
        : `${day(from)} – ${day(last)}`;
  // Read once when the filter mounts, so a render stays pure.
  const [today] = useState(() => new Date());
  const shownMonth =
    draft?.from ?? new Date(today.getFullYear(), today.getMonth() - (mobile ? 0 : 1), 1);

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (next) setDraft(from ? { from: toDate(from), to: toDate(last ?? from) } : undefined);
      }}
    >
      <PopoverTrigger asChild>
        <Button
          type="button"
          size="sm"
          prominence="tertiary"
          aria-label={ui("{{v1}}: {{v2}}", { v1: ui("Updated"), v2: label })}
        >
          <span data-icon="inline-start" className="text-content-muted" aria-hidden>
            <Clock3 />
          </span>
          <span className="max-w-56 truncate">{label}</span>
          <ChevronDown data-icon="inline-end" />
        </Button>
      </PopoverTrigger>
      <PopoverContent
        align="start"
        sideOffset={6}
        collisionPadding={12}
        className="w-auto max-w-[calc(100vw-1.5rem)]"
      >
        <div className="flex flex-col gap-2 md:flex-row">
          <div
            role="group"
            aria-label={ui("Updated")}
            className="flex flex-col gap-0.5 border-b border-border-subtle pb-2 md:w-44 md:border-r md:border-b-0 md:pr-2 md:pb-0"
          >
            {TIME_RANGE_OPTIONS.map((option) => {
              const selected = from === undefined && option.value === timeRange;
              return (
                <Button
                  key={option.value}
                  type="button"
                  size="sm"
                  prominence="tertiary"
                  aria-pressed={selected}
                  className="justify-start"
                  onClick={() => {
                    setOpen(false);
                    onPreset(option.value as SearchTimeRange);
                  }}
                >
                  {ui(option.label)}
                  {selected ? <Check data-icon="inline-end" className="ml-auto" /> : null}
                </Button>
              );
            })}
          </div>
          <div className="flex flex-col">
            <Calendar
              mode="range"
              numberOfMonths={mobile ? 1 : 2}
              selected={draft}
              onSelect={setDraft}
              defaultMonth={shownMonth}
              disabled={{ after: today }}
              locale={uiLocale() === "en-US" ? enUS : vi}
            />
            <div className="flex items-center justify-end gap-2 border-t border-border-subtle pt-2">
              <Button
                type="button"
                size="sm"
                prominence="tertiary"
                disabled={!draft?.from}
                onClick={() => setDraft(undefined)}
              >
                {ui("Clear")}
              </Button>
              <Button
                type="button"
                size="sm"
                disabled={!draft?.from}
                onClick={() => {
                  if (!draft?.from) return;
                  setOpen(false);
                  onRange(toDay(draft.from), toDay(draft.to ?? draft.from));
                }}
              >
                {ui("Apply")}
              </Button>
            </div>
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
}
