import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState, type ReactNode } from "react";
import { Check, ImagePlus, Library, Pencil, Plus, Tag, X } from "lucide-react";
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
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";
import { ChatFilePicker } from "@/features/chat/chat-file-picker";
import type { AgentRef } from "@/features/chat/chat-workspace-api";
import { findSourceProvider } from "@/features/sources/source-provider-catalog";
import { AgentAvatar } from "./agent-avatar";
import { agentIconTones, agentIcons } from "./agent-icons";

const avatarTypes = new Set(["image/png", "image/jpeg", "image/webp", "image/gif"]);

/** A titled editor section; sections are divided by a rule, as in Langdock's agent builder. */
export function EditorSection({
  id,
  title,
  description,
  children,
}: {
  id: string;
  title: string;
  description: string;
  children: ReactNode;
}) {
  return (
    <section
      id={id}
      aria-labelledby={`${id}-title`}
      className="flex scroll-mt-20 flex-col gap-5 border-t border-border-subtle py-8 first-of-type:border-t-0 first-of-type:pt-0"
    >
      <header>
        <h2 id={`${id}-title`} className="font-heading-h3 text-content-primary">
          {title}
        </h2>
        <p className="mt-1 font-secondary-body text-content-muted">{description}</p>
      </header>
      {children}
    </section>
  );
}

export function Field({
  label,
  htmlFor,
  hint,
  counter,
  children,
}: {
  label: string;
  htmlFor?: string;
  hint?: ReactNode;
  counter?: ReactNode;
  children: ReactNode;
}) {
  const Label = htmlFor ? "label" : "span";
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <div className="flex items-baseline justify-between gap-3">
        <Label htmlFor={htmlFor} className="font-main-ui-action text-content-primary">
          {label}
        </Label>
        {counter !== undefined && (
          <span className="font-secondary-body text-content-muted tabular-nums">{counter}</span>
        )}
      </div>
      {children}
      {hint && <p className="font-secondary-body text-content-muted">{hint}</p>}
    </div>
  );
}

/** Large avatar that opens a topic icon grid, with an uploaded image as the alternative (Sana). */
export function AgentIconPicker({
  agentId,
  name,
  iconName,
  hasAvatar,
  avatarFileId,
  allowImage,
  disabled,
  onIcon,
  onImage,
  onClearImage,
}: {
  agentId: string;
  name: string;
  iconName: string;
  hasAvatar: boolean;
  avatarFileId: string | null;
  allowImage: boolean;
  disabled: boolean;
  onIcon: (key: string) => void;
  onImage: (fileId: string) => void;
  onClearImage: () => void;
}) {
  const ui = useAppTranslation();
  const [error, setError] = useState<string>();
  const usesImage = hasAvatar || !!avatarFileId;
  return (
    <div className="flex flex-col items-start gap-1">
      <Popover>
        <PopoverTrigger asChild disabled={disabled}>
          <button
            type="button"
            aria-label={ui("Đổi biểu tượng")}
            className="group relative rounded-3xl outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/40 disabled:cursor-default"
          >
            <AgentAvatar
              agent={{ id: agentId, name, iconName, hasAvatar: hasAvatar && !avatarFileId }}
              size="xl"
            />
            {avatarFileId && (
              <span className="absolute inset-0 grid place-items-center rounded-3xl bg-surface-sunken font-secondary-action text-content-secondary">
                {ui("Ảnh mới")}
              </span>
            )}
            {!disabled && (
              <span className="absolute -right-1 -bottom-1 grid size-7 place-items-center rounded-full border border-border-subtle bg-surface-raised text-content-secondary shadow-hover">
                <Pencil aria-hidden="true" className="size-3.5" />
              </span>
            )}
          </button>
        </PopoverTrigger>
        <PopoverContent align="start" className="w-[19.5rem] p-3">
          <p className="mb-2 font-secondary-action text-content-muted">{ui("Biểu tượng")}</p>
          <div role="radiogroup" aria-label={ui("Biểu tượng")} className="grid grid-cols-7 gap-1.5">
            {Object.entries(agentIcons).map(([key, Icon]) => {
              const checked = !usesImage && iconName === key;
              return (
                <button
                  key={key}
                  type="button"
                  role="radio"
                  aria-checked={checked}
                  aria-label={key}
                  onClick={() => onIcon(key)}
                  className={cn(
                    "grid size-9 place-items-center rounded-lg outline-none transition-shadow focus-visible:ring-3 focus-visible:ring-focus-ring/40",
                    agentIconTones[key],
                    checked
                      ? "ring-2 ring-content-primary ring-offset-2 ring-offset-surface-overlay"
                      : "hover:ring-1 hover:ring-border-default",
                  )}
                >
                  <Icon aria-hidden="true" className="size-4.5" strokeWidth={1.75} />
                </button>
              );
            })}
          </div>
          {allowImage && (
            <div className="mt-3 flex items-center gap-2 border-t border-border-subtle pt-3">
              <ChatFilePicker
                selected={avatarFileId ? [avatarFileId] : []}
                onSelect={(ids, files) => {
                  const image = files.filter((file) => ids.includes(file.id)).at(-1);
                  if (!image) return;
                  if (!avatarTypes.has(image.mediaType) || image.sizeBytes > 2 * 1024 * 1024) {
                    setError(ui("Dùng ảnh PNG, JPEG, WebP hoặc GIF tối đa 2 MiB."));
                    return;
                  }
                  setError(undefined);
                  onImage(image.id);
                }}
                trigger={
                  <Button type="button" size="sm" prominence="secondary">
                    <ImagePlus aria-hidden="true" />
                    {ui("Dùng ảnh")}
                  </Button>
                }
              />
              {usesImage && (
                <Button type="button" size="sm" prominence="tertiary" onClick={onClearImage}>
                  {ui("Bỏ ảnh đại diện")}
                </Button>
              )}
            </div>
          )}
        </PopoverContent>
      </Popover>
      {error && (
        <p role="alert" className="font-secondary-body text-status-danger-content">
          {error}
        </p>
      )}
    </div>
  );
}

/** Selected labels as removable chips plus a searchable add/create menu (Langdock). */
export function AgentLabelPicker({
  labels,
  value,
  disabled,
  onChange,
  onCreate,
}: {
  labels: AgentRef[];
  value: string[];
  disabled: boolean;
  onChange: (ids: string[]) => void;
  onCreate: (name: string) => Promise<void>;
}) {
  const ui = useAppTranslation();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [error, setError] = useState<string>();
  const selected = value.flatMap((id) => labels.filter((label) => label.id === id));
  const trimmed = query.trim();
  const exact = labels.some(
    (label) => label.name.toLocaleLowerCase() === trimmed.toLocaleLowerCase(),
  );
  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-1.5">
        {selected.map((label) => (
          <span
            key={label.id}
            className="inline-flex h-7 items-center gap-1 rounded-full border border-border-subtle bg-surface-raised pr-1 pl-3 font-secondary-action text-content-secondary"
          >
            {label.name}
            {!disabled && (
              <button
                type="button"
                aria-label={ui("Bỏ nhãn {{v1}}", { v1: label.name })}
                onClick={() => onChange(value.filter((id) => id !== label.id))}
                className="grid size-5 place-items-center rounded-full text-content-muted outline-none hover:bg-surface-sunken hover:text-content-primary focus-visible:ring-2 focus-visible:ring-focus-ring/40"
              >
                <X aria-hidden="true" className="size-3" />
              </button>
            )}
          </span>
        ))}
        {!disabled && (
          <Popover open={open} onOpenChange={setOpen}>
            <PopoverTrigger asChild>
              <button
                type="button"
                className="inline-flex h-7 items-center gap-1 rounded-full border border-dashed border-border-default px-3 font-secondary-action text-content-muted outline-none hover:border-content-muted hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/40"
              >
                <Plus aria-hidden="true" className="size-3.5" />
                {ui("Thêm nhãn")}
              </button>
            </PopoverTrigger>
            <PopoverContent align="start" className="w-64 p-0">
              <Command>
                <CommandInput
                  value={query}
                  onValueChange={setQuery}
                  placeholder={ui("Tìm hoặc tạo nhãn…")}
                />
                <CommandList>
                  <CommandEmpty>{ui("Chưa có nhãn nào.")}</CommandEmpty>
                  <CommandGroup>
                    {labels.map((label) => {
                      const checked = value.includes(label.id);
                      return (
                        <CommandItem
                          key={label.id}
                          value={label.name}
                          onSelect={() =>
                            onChange(
                              checked
                                ? value.filter((id) => id !== label.id)
                                : [...value, label.id],
                            )
                          }
                        >
                          <Tag aria-hidden="true" className="text-content-muted" />
                          <span className="flex-1 truncate">{label.name}</span>
                          {checked && <Check aria-hidden="true" />}
                        </CommandItem>
                      );
                    })}
                    {trimmed && !exact && (
                      <CommandItem
                        value={`__create__${trimmed}`}
                        onSelect={() => {
                          setError(undefined);
                          onCreate(trimmed)
                            .then(() => setQuery(""))
                            .catch((cause: unknown) =>
                              setError(cause instanceof Error ? cause.message : String(cause)),
                            );
                        }}
                      >
                        <Plus aria-hidden="true" />
                        {ui("Tạo nhãn “{{v1}}”", { v1: trimmed })}
                      </CommandItem>
                    )}
                  </CommandGroup>
                </CommandList>
              </Command>
            </PopoverContent>
          </Popover>
        )}
      </div>
      {error && (
        <p role="alert" className="font-secondary-body text-status-danger-content">
          {error}
        </p>
      )}
    </div>
  );
}

export const maxStarterPrompts = 8;

/** One input per conversation starter (StackAI, Zapier). */
export function StarterPromptsField({
  value,
  disabled,
  onChange,
}: {
  value: string[];
  disabled: boolean;
  onChange: (value: string[]) => void;
}) {
  const ui = useAppTranslation();
  const rows = value.length === 0 ? [""] : value;
  return (
    <Field
      label={ui("Câu hỏi gợi ý")}
      counter={`${value.filter((prompt) => prompt.trim()).length}/${maxStarterPrompts}`}
      hint={ui("Hiện dưới ô chat để người dùng bấm hỏi ngay.")}
    >
      <ol className="flex flex-col gap-2">
        {rows.map((prompt, index) => (
          <li key={index} className="flex items-center gap-2">
            <Input
              aria-label={ui("Câu hỏi gợi ý {{v1}}", { v1: index + 1 })}
              maxLength={1000}
              value={prompt}
              placeholder={
                index === 0 ? ui("Ví dụ: Xếp loại KPI tháng 8 của các đơn vị") : undefined
              }
              onChange={(event) =>
                onChange(rows.map((item, i) => (i === index ? event.target.value : item)))
              }
            />
            {!disabled && rows.length > 1 && (
              <IconButton
                type="button"
                prominence="tertiary"
                aria-label={ui("Xoá câu hỏi gợi ý {{v1}}", { v1: index + 1 })}
                onClick={() => onChange(rows.filter((_, i) => i !== index))}
              >
                <X />
              </IconButton>
            )}
          </li>
        ))}
      </ol>
      {!disabled && (
        <Button
          type="button"
          size="sm"
          prominence="tertiary"
          className="self-start"
          disabled={rows.length >= maxStarterPrompts}
          onClick={() => onChange([...rows, ""])}
        >
          <Plus aria-hidden="true" />
          {ui("Thêm câu gợi ý")}
        </Button>
      )}
    </Field>
  );
}

type SourceOption = { id: string; name: string; type?: string };

/** Chosen sources as rows with provider icons, added from a searchable menu (Chatbase, Lemni). */
export function AgentSourcePicker({
  options,
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
}) {
  const ui = useAppTranslation();
  const chosen = value.map(
    (id) =>
      options.find((source) => source.id === id) ??
      known.find((source) => source.id === id) ?? { id, name: "", type: "" },
  );
  const add = !disabled && (
    <Popover>
      <PopoverTrigger asChild>
        <Button type="button" size="sm" prominence="secondary" disabled={pending || failed}>
          <Plus aria-hidden="true" />
          {ui("Thêm nguồn")}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-80 p-0">
        <Command>
          <CommandInput placeholder={ui("Tìm nguồn…")} />
          <CommandList>
            <CommandEmpty>{ui("Không tìm thấy nguồn.")}</CommandEmpty>
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
                    <Icon aria-hidden="true" className="size-4" />
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
  return (
    <div className="flex flex-col gap-3">
      {failed && (
        <p role="alert" className="font-secondary-body text-status-danger-content">
          {ui("Không tải được nguồn.")}{" "}
          <Button type="button" size="sm" prominence="tertiary" onClick={onRetry}>
            {ui("Tải lại")}
          </Button>
        </p>
      )}
      {chosen.length === 0 ? (
        <div className="flex items-center gap-3 rounded-xl border border-dashed border-border-default px-4 py-3">
          <span
            aria-hidden="true"
            className="grid size-9 shrink-0 place-items-center rounded-lg bg-surface-sunken text-content-muted"
          >
            <Library className="size-4.5" />
          </span>
          <div className="min-w-0 flex-1">
            <p className="font-main-ui-action text-content-primary">
              {ui("Tìm trong mọi nguồn người dùng được phép đọc")}
            </p>
            <p className="font-secondary-body text-content-muted">
              {ui("Chọn nguồn để trợ lý chỉ trả lời từ những tài liệu đó.")}
            </p>
          </div>
          {add}
        </div>
      ) : (
        <>
          <SettingRows>
            {chosen.map((source) => {
              const provider = findSourceProvider(source.type);
              const Icon = provider?.icon ?? Library;
              return (
                <SettingRow
                  key={source.id}
                  icon={<Icon />}
                  title={source.name || ui("Nguồn không còn khả dụng (đang giữ lựa chọn)")}
                  description={provider?.name}
                  control={
                    !disabled && (
                      <IconButton
                        type="button"
                        size="sm"
                        prominence="tertiary"
                        aria-label={ui("Bỏ nguồn {{v1}}", { v1: source.name })}
                        onClick={() => onChange(value.filter((id) => id !== source.id))}
                      >
                        <X />
                      </IconButton>
                    )
                  }
                />
              );
            })}
          </SettingRows>
          <div>{add}</div>
        </>
      )}
    </div>
  );
}
