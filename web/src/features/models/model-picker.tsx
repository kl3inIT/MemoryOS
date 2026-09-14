import { Check, ChevronDown, ChevronRight, Search } from "lucide-react";
import { useMemo, useState } from "react";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { ChatModelLogo } from "@/features/chat/chat-model-logo";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import type { ManagedModel, ManagedProvider } from "./model-catalog";

export type ModelPickerOption = {
  model: ManagedModel;
  provider: ManagedProvider;
  disabled?: boolean;
  note?: string;
};

/** Onyx-style default-model picker: search + collapsible provider groups + model logos. */
export function ModelPicker({
  value,
  options,
  disabled,
  placeholder,
  inheritLabel,
  ariaLabel,
  onChange,
}: {
  value: string;
  options: ModelPickerOption[];
  disabled?: boolean;
  placeholder: string;
  /** Persona pickers offer an explicit Inherit choice. */
  inheritLabel?: string;
  ariaLabel: string;
  onChange: (modelId: string) => void;
}) {
  const ui = useAppTranslation();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const selected = options.find((option) => option.model.id === value);
  const groups = useMemo(() => {
    const needle = query.trim().toLowerCase();
    const visible = needle
      ? options.filter((option) =>
          `${option.model.displayName} ${option.model.modelName} ${option.provider.name}`
            .toLowerCase()
            .includes(needle),
        )
      : options;
    const byProvider = new Map<string, { provider: ManagedProvider; options: ModelPickerOption[] }>();
    for (const option of visible) {
      const group = byProvider.get(option.provider.id) ?? {
        provider: option.provider,
        options: [],
      };
      group.options.push(option);
      byProvider.set(option.provider.id, group);
    }
    return [...byProvider.values()];
  }, [options, query]);

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) setQuery("");
      }}
    >
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label={ariaLabel}
          disabled={disabled}
          className="flex h-10 w-full min-w-0 items-center gap-2.5 rounded-full border border-border-subtle bg-surface-sunken px-4 text-left font-main-ui-body text-content-primary shadow-sm transition-colors hover:border-border-default hover:bg-surface-base disabled:cursor-not-allowed disabled:opacity-60 sm:max-w-xs"
        >
          {selected ? (
            <>
              <span className="grid size-5 shrink-0 place-items-center [&_svg]:size-4">
                <ChatModelLogo modelName={selected.model.modelName} />
              </span>
              <span className="min-w-0 flex-1 truncate font-medium">
                {selected.model.displayName}
              </span>
            </>
          ) : (
            <span className="min-w-0 flex-1 truncate text-content-muted">
              {value === "" && inheritLabel ? inheritLabel : placeholder}
            </span>
          )}
          <ChevronDown
            className={cn(
              "size-4 shrink-0 text-content-muted transition-transform",
              open && "rotate-180",
            )}
            aria-hidden="true"
          />
        </button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-[min(24rem,calc(100vw-2rem))] gap-1 p-2">
        <div className="relative">
          <Search
            className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-content-muted"
            aria-hidden="true"
          />
          <Input
            autoFocus
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={ui("Search models…")}
            aria-label={ui("Search models")}
            className="pl-8"
          />
        </div>
        {inheritLabel && (
          <button
            type="button"
            onClick={() => {
              onChange("");
              setOpen(false);
            }}
            className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left font-main-ui-body hover:bg-surface-base"
          >
            <span className="size-4" aria-hidden="true" />
            <span className="min-w-0 flex-1 truncate">{inheritLabel}</span>
            {value === "" && <Check className="size-4 text-content-primary" aria-hidden="true" />}
          </button>
        )}
        <div className="max-h-72 overflow-y-auto">
          {groups.map((group) => {
            const isCollapsed = collapsed[group.provider.id] ?? false;
            return (
              <div key={group.provider.id}>
                <button
                  type="button"
                  aria-expanded={!isCollapsed}
                  onClick={() =>
                    setCollapsed((current) => ({
                      ...current,
                      [group.provider.id]: !isCollapsed,
                    }))
                  }
                  className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left font-main-ui-action text-content-secondary hover:bg-surface-base"
                >
                  {isCollapsed ? (
                    <ChevronRight className="size-4" aria-hidden="true" />
                  ) : (
                    <ChevronDown className="size-4" aria-hidden="true" />
                  )}
                  <span className="min-w-0 flex-1 truncate">{group.provider.name}</span>
                </button>
                {!isCollapsed &&
                  group.options.map((option) => (
                    <button
                      key={option.model.id}
                      type="button"
                      disabled={option.disabled}
                      onClick={() => {
                        onChange(option.model.id);
                        setOpen(false);
                      }}
                      className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 pl-8 text-left hover:bg-surface-base disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      <ChatModelLogo modelName={option.model.modelName} />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate font-main-ui-body">
                          {option.model.displayName}
                        </span>
                        <span className="block truncate font-secondary-body text-content-muted">
                          {option.model.modelName}
                          {option.note ? ` · ${option.note}` : ""}
                        </span>
                      </span>
                      {value === option.model.id && (
                        <Check className="size-4 shrink-0 text-content-primary" aria-hidden="true" />
                      )}
                    </button>
                  ))}
              </div>
            );
          })}
          {!groups.length && (
            <p role="status" className="px-2 py-3 font-secondary-body text-content-muted">
              {ui("No matching models.")}
            </p>
          )}
        </div>
      </PopoverContent>
    </Popover>
  );
}
