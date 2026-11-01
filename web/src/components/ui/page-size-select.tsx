import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

/** Rows-per-page control shared by every paginated table. */
export function PageSizeSelect<TSize extends number | string = number>({
  label,
  rowsLabel,
  value,
  sizes,
  sizeLabel = String,
  disabled = false,
  onSizeChange,
}: {
  /** Accessible name of the control, such as "Rows per page". */
  label: string;
  /** Visible text before the control, such as "Rows". */
  rowsLabel: string;
  value: TSize;
  sizes: readonly TSize[];
  /** How a size reads in the list, for a choice that is not a number such as "All". */
  sizeLabel?: (size: TSize) => string;
  disabled?: boolean;
  onSizeChange: (size: TSize) => void;
}) {
  return (
    <div className="flex items-center gap-2 font-secondary-body text-content-secondary">
      <span>{rowsLabel}</span>
      <Select
        value={String(value)}
        disabled={disabled}
        onValueChange={(next) => {
          const size = sizes.find((offered) => String(offered) === next);
          if (size !== undefined) onSizeChange(size);
        }}
      >
        <SelectTrigger size="sm" aria-label={label} className="w-auto">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {sizes.map((size) => (
            <SelectItem key={size} value={String(size)}>
              {sizeLabel(size)}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}
