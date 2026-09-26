import { useAppTranslation } from "@/i18n/use-app-translation";
import { Check, ChevronRight, Ellipsis, Library, Plus, X } from "lucide-react";
import { EmptyState } from "@/components/composites/empty-state";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { Button } from "@/components/ui/button";
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from "@/components/ui/command";
import { IconButton } from "@/components/ui/icon-button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { findSourceProvider } from "@/features/sources/shared/source-provider-catalog";
import { LoadFailure } from "./agent-editor-fields";

type SourceOption = { id: string; name: string; type?: string };

/** Chosen entries kept in view before the rest move behind "+N". */
const visibleRows = 4;

/** Chosen sources as rows with provider icons, added from a searchable menu (Chatbase, Lemni). */
export function AgentSourcePicker({
  options,
  kind = "source",
  known,
  value,
  pending,
  failed,
  disabled,
  onRetry,
  onChange,
}: {
  options: SourceOption[];
  known: SourceOption[];
  value: string[];
  pending: boolean;
  failed: boolean;
  disabled: boolean;
  onRetry: () => void;
  onChange: (ids: string[]) => void;
  kind?: "source" | "document-set";
}) {
  const ui = useAppTranslation();
  const isDocumentSet = kind === "document-set";
  const chosen = value.map(
    (id) =>
      options.find((source) => source.id === id) ??
      known.find((source) => source.id === id) ?? { id, name: "", type: "" },
  );
  const row = (source: SourceOption) => {
    const provider = findSourceProvider(source.type);
    const Icon = provider?.icon ?? Library;
    return (
      <SettingRow
        key={source.id}
        icon={<Icon />}
        title={
          source.name ||
          ui(
            isDocumentSet
              ? "Bộ tài liệu không còn khả dụng (đang giữ lựa chọn)"
              : "Nguồn không còn khả dụng (đang giữ lựa chọn)",
          )
        }
        description={isDocumentSet ? undefined : provider?.name}
        control={
          !disabled && (
            <IconButton
              type="button"
              size="sm"
              prominence="tertiary"
              aria-label={ui(isDocumentSet ? "Bỏ bộ tài liệu {{v1}}" : "Bỏ nguồn {{v1}}", {
                v1: source.name,
              })}
              onClick={() => onChange(value.filter((id) => id !== source.id))}
            >
              <X />
            </IconButton>
          )
        }
      />
    );
  };
  const add = !disabled && (
    <SourceMenu
      kind={kind}
      options={options}
      value={value}
      disabled={pending || failed}
      onChange={onChange}
    />
  );
  return (
    <div className="flex flex-col gap-3">
      {failed && (
        <LoadFailure onRetry={onRetry}>
          {isDocumentSet ? ui("Không tải được bộ tài liệu.") : ui("Không tải được nguồn.")}
        </LoadFailure>
      )}
      {chosen.length === 0 ? (
        <EmptyState
          icon={<Library />}
          title={
            isDocumentSet
              ? ui("Không có bộ tài liệu nào được chọn")
              : ui("Tìm trong mọi nguồn người dùng được phép đọc")
          }
          detail={
            isDocumentSet
              ? ui("Chọn bộ tài liệu để trợ lý chỉ trả lời từ các nguồn trong đó.")
              : ui("Chọn nguồn để trợ lý chỉ trả lời từ những tài liệu đó.")
          }
          action={add || undefined}
        />
      ) : (
        <>
          <SettingRows>
            {chosen.slice(0, visibleRows).map(row)}
            {chosen.length > visibleRows && (
              <Popover>
                <PopoverTrigger asChild>
                  <button
                    type="button"
                    className="flex w-full items-center gap-3 px-4 py-3 text-left outline-none hover:bg-surface-subtle/50 focus-visible:ring-3 focus-visible:ring-focus-ring/40"
                  >
                    <span
                      aria-hidden="true"
                      className="grid size-9 shrink-0 place-items-center rounded-lg bg-surface-sunken text-content-secondary"
                    >
                      <Ellipsis className="size-4.5" />
                    </span>
                    <span className="min-w-0 flex-1 font-main-ui-action text-content-secondary">
                      {isDocumentSet
                        ? ui("Xem thêm {{n}} bộ tài liệu", { n: chosen.length - visibleRows })
                        : ui("Xem thêm {{n}} nguồn", { n: chosen.length - visibleRows })}
                    </span>
                    <ChevronRight
                      aria-hidden="true"
                      className="size-4 shrink-0 text-content-muted"
                    />
                  </button>
                </PopoverTrigger>
                <PopoverContent className="max-h-80 w-96 overflow-y-auto p-0">
                  <SettingRows className="border-0">
                    {chosen.slice(visibleRows).map(row)}
                  </SettingRows>
                </PopoverContent>
              </Popover>
            )}
          </SettingRows>
          <div>{add}</div>
        </>
      )}
    </div>
  );
}

function SourceMenu({
  kind,
  options,
  value,
  disabled,
  onChange,
}: {
  kind: "source" | "document-set";
  options: SourceOption[];
  value: string[];
  disabled: boolean;
  onChange: (ids: string[]) => void;
}) {
  const ui = useAppTranslation();
  const isDocumentSet = kind === "document-set";
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button type="button" size="sm" prominence="secondary" disabled={disabled}>
          <Plus data-icon="inline-start" aria-hidden="true" />
          {isDocumentSet ? ui("Thêm bộ tài liệu") : ui("Thêm nguồn")}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-80 p-0">
        <Command>
          <CommandInput placeholder={isDocumentSet ? ui("Tìm bộ tài liệu…") : ui("Tìm nguồn…")} />
          <CommandList>
            <CommandEmpty>
              {isDocumentSet ? ui("Không tìm thấy bộ tài liệu.") : ui("Không tìm thấy nguồn.")}
            </CommandEmpty>
            <CommandGroup>
              {options.map((source) => {
                const Icon = findSourceProvider(source.type)?.icon ?? Library;
                const checked = value.includes(source.id);
                return (
                  <CommandItem
                    key={source.id}
                    value={`${source.name} ${source.id}`}
                    onSelect={() =>
                      onChange(
                        checked ? value.filter((id) => id !== source.id) : [...value, source.id],
                      )
                    }
                  >
                    <Icon aria-hidden="true" />
                    <span className="flex-1 truncate">{source.name}</span>
                    {checked && <Check aria-hidden="true" />}
                  </CommandItem>
                );
              })}
            </CommandGroup>
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  );
}
