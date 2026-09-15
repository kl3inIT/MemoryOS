import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/radix-select";

/** Rows-per-page control shared by every paginated table. */
export function PageSizeSelect({
  label,
  rowsLabel,
  value,
  sizes,
  disabled = false,
  onSizeChange,
}: {
  /** Accessible name of the control, such as "Rows per page". */
  label: string;
  /** Visible text before the control, such as "Rows". */
  rowsLabel: string;
  value: number;
  sizes: readonly number[];
  disabled?: boolean;
  onSizeChange: (size: number) => void;
}) {
  return (
    <div className="flex items-center gap-2 font-secondary-body text-content-secondary">
      <span>{rowsLabel}</span>
      <Select
        value={String(value)}
        disabled={disabled}
        onValueChange={(next) => onSizeChange(Number(next))}
      >
        <SelectTrigger size="sm" aria-label={label} className="w-auto">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {sizes.map((size) => (
            <SelectItem key={size} value={String(size)}>
              {size}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}
