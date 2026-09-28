import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState } from "react";
import { Command as CommandPrimitive } from "cmdk";
import { Library, Search, X } from "lucide-react";
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandItem,
  CommandList,
} from "@/components/ui/command";
import { ClampedList } from "@/components/ui/clamped-list";
import { Empty, EmptyDescription } from "@/components/ui/empty";
import { IconButton } from "@/components/ui/icon-button";
import { InputGroup, InputGroupAddon } from "@/components/ui/input-group";
import { findSourceProvider } from "@/features/sources/shared/source-provider-catalog";

export type SourceOption = { id: string; name: string; type: string };

/** Rows of chips kept in view before the rest move behind "+N". */
const visibleRows = 4;

/** Onyx `ConnectorMultiSelect`: a search field that lists unselected Sources, with the selection shown as chips below. */
export function DocumentSetSourcePicker({
  options,
  known,
  value,
  disabled,
  invalid,
  onChange,
}: {
  options: SourceOption[];
  known: { id: string; name: string }[];
  value: string[];
  disabled: boolean;
  invalid: boolean;
  onChange: (ids: string[]) => void;
}) {
  const ui = useAppTranslation();
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const unselected = options.filter((source) => !value.includes(source.id));
  const allSelected = options.length > 0 && unselected.length === 0;
  const chosen = value.map(
    (id) =>
      options.find((source) => source.id === id) ?? {
        id,
        name: known.find((source) => source.id === id)?.name ?? "",
        type: "",
      },
  );

  return (
    <div className="flex flex-col gap-3">
      {/* cmdk labels its input from this text; without it the field is nameless whatever the placeholder says. */}
      <Command label={ui("Tìm nguồn…")} className="relative overflow-visible">
        <InputGroup>
          <InputGroupAddon>
            <Search aria-hidden="true" />
          </InputGroupAddon>
          <CommandPrimitive.Input
            data-slot="input-group-control"
            value={search}
            disabled={disabled || allSelected}
            aria-invalid={invalid || undefined}
            onValueChange={(next) => {
              setSearch(next);
              setOpen(true);
            }}
            onFocus={() => setOpen(true)}
            onBlur={() => setOpen(false)}
            onKeyDown={(event) => {
              if (event.key === "Escape") setOpen(false);
            }}
            placeholder={allSelected ? ui("Đã chọn tất cả nguồn") : ui("Tìm nguồn…")}
            className="h-full min-w-0 flex-1 bg-transparent pr-2.5 font-main-ui-body outline-none placeholder:text-content-muted disabled:cursor-not-allowed"
          />
        </InputGroup>
        {/* With every Source chosen the field is disabled, so its empty dropdown would only cover the chips. */}
        {open && !allSelected && (
          <div className="absolute top-full right-0 left-0 z-50 mt-1 rounded-xl border border-border-subtle bg-surface-overlay p-1 shadow-md">
            <CommandList onMouseDown={(event) => event.preventDefault()}>
              <CommandEmpty>
                {options.length === 0
                  ? ui("Không có nguồn nào bạn được phép chọn.")
                  : ui("Không tìm thấy nguồn.")}
              </CommandEmpty>
              <CommandGroup>
                {unselected.map((source) => {
                  const Icon = findSourceProvider(source.type)?.icon ?? Library;
                  return (
                    <CommandItem
                      key={source.id}
                      value={`${source.name} ${source.id}`}
                      onSelect={() => {
                        onChange([...value, source.id]);
                        setSearch("");
                      }}
                    >
                      <Icon aria-hidden="true" className="size-4" />
                      <span className="flex-1 truncate" title={source.name}>
                        {source.name}
                      </span>
                    </CommandItem>
                  );
                })}
              </CommandGroup>
            </CommandList>
          </div>
        )}
      </Command>
      {chosen.length > 0 ? (
        <ClampedList
          maxRows={visibleRows}
          label={ui("Nguồn đã chọn")}
          items={chosen.map((source) => {
            const Icon = findSourceProvider(source.type)?.icon ?? Library;
            const name = source.name || ui("Nguồn không còn khả dụng");
            return (
              <span
                key={source.id}
                className="flex max-w-full items-center gap-1.5 rounded-xl border border-border-subtle bg-surface-raised py-1 pr-1 pl-2.5 font-secondary-body"
              >
                <Icon aria-hidden="true" className="size-4 shrink-0" />
                <span className="truncate" title={name}>
                  {name}
                </span>
                <IconButton
                  prominence="internal"
                  size="sm"
                  disabled={disabled}
                  aria-label={ui("Bỏ {{v1}}", { v1: name })}
                  onClick={() => onChange(value.filter((id) => id !== source.id))}
                >
                  <X />
                </IconButton>
              </span>
            );
          })}
        />
      ) : (
        <Empty>
          <EmptyDescription>
            {ui("Chưa chọn nguồn nào. Tìm và chọn nguồn ở ô phía trên.")}
          </EmptyDescription>
        </Empty>
      )}
    </div>
  );
}
