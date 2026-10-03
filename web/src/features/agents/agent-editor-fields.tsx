import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState, type ReactNode } from "react";
import { Check, ImagePlus, Lock, Pencil, Plus, Tag, X } from "lucide-react";
import { SectionHeader } from "@/components/composites/section-header";
import { useFieldValidity } from "@/components/form/form-context";
import { Alert, AlertAction, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { HelpPopover } from "@/components/ui/help-popover";
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from "@/components/ui/command";
import { Field, FieldDescription, FieldError, FieldLabel, FieldTitle } from "@/components/ui/field";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { formField } from "@/lib/action-errors";
import { cn } from "@/lib/utils";
import { ChatFilePicker } from "@/features/library/file-picker";
import type { NamedRef } from "@/features/identity/principals";
import { AgentAvatar } from "./agent-avatar";
import { formatTokens, maxStarterPrompts } from "./agent-form";
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
      <SectionHeader id={`${id}-title`} title={title} description={description} />
      {children}
    </section>
  );
}

/**
 * A labelled editor control with an optional counter beside the label and a hint under it. A control that is not
 * one labelable element (a picker, a list) has a title instead of a label.
 */
export function EditorField({
  label,
  htmlFor,
  hint,
  counter,
  invalid = false,
  errors,
  children,
}: {
  label: string;
  htmlFor?: string;
  hint?: ReactNode;
  counter?: ReactNode;
  invalid?: boolean;
  errors?: ({ message?: string } | undefined)[];
  children: ReactNode;
}) {
  return (
    <Field data-invalid={invalid || undefined} className="min-w-0">
      <div className="flex items-baseline justify-between gap-3">
        {htmlFor ? (
          <FieldLabel htmlFor={htmlFor}>{label}</FieldLabel>
        ) : (
          <FieldTitle>{label}</FieldTitle>
        )}
        {counter !== undefined && (
          <span className="font-secondary-body text-content-muted tabular-nums">{counter}</span>
        )}
      </div>
      {children}
      {hint && <FieldDescription>{hint}</FieldDescription>}
      {invalid && <FieldError errors={errors} />}
    </Field>
  );
}

/** A multi-line text control bound to the `form.AppField` around it. */
export function TextareaField({
  label,
  hint,
  counter,
  maxLength,
  rows,
  placeholder,
  className,
}: {
  label: string;
  hint?: ReactNode;
  counter?: (value: string) => ReactNode;
  maxLength: number;
  rows: number;
  placeholder?: string;
  className?: string;
}) {
  const { field, invalid, errors } = useFieldValidity<string>();
  return (
    <EditorField
      label={label}
      htmlFor={field.name}
      hint={hint}
      counter={counter?.(field.state.value)}
      invalid={invalid}
      errors={errors}
    >
      <textarea
        id={field.name}
        name={field.name}
        className={cn(formField, className)}
        maxLength={maxLength}
        rows={rows}
        value={field.state.value}
        placeholder={placeholder}
        aria-invalid={invalid || undefined}
        onBlur={field.handleBlur}
        onChange={(event) => field.handleChange(event.target.value)}
      />
    </EditorField>
  );
}

/** A failed read of the editor's choices, with a retry. */
export function LoadFailure({ children, onRetry }: { children: ReactNode; onRetry: () => void }) {
  const ui = useAppTranslation();
  return (
    <Alert variant="destructive">
      <AlertDescription>{children}</AlertDescription>
      <AlertAction>
        <Button type="button" size="sm" prominence="tertiary" onClick={onRetry}>
          {ui("Tải lại")}
        </Button>
      </AlertAction>
    </Alert>
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
  const iconNames: Record<string, string> = {
    bot: ui("Trợ lý"),
    chart: ui("Biểu đồ"),
    finance: ui("Tài chính"),
    calculator: ui("Máy tính"),
    people: ui("Nhân sự"),
    legal: ui("Pháp chế"),
    document: ui("Tài liệu"),
    book: ui("Sổ tay"),
    briefcase: ui("Công việc"),
    search: ui("Tìm kiếm"),
    idea: ui("Ý tưởng"),
    shield: ui("An toàn"),
    chat: ui("Hỏi đáp"),
  };
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
        <PopoverContent align="start" className="w-80 p-3">
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
                  aria-label={iconNames[key] ?? ui("Biểu tượng")}
                  onClick={() => onIcon(key)}
                  className={cn(
                    "grid size-9 place-items-center rounded-lg transition-shadow outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/40",
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
                    <ImagePlus data-icon="inline-start" aria-hidden="true" />
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
      {error && <FieldError>{error}</FieldError>}
    </div>
  );
}

/**
 * Selected labels as removable chips plus a searchable menu of the Tenant's labels (Langdock). Labels are created
 * on the agent administration page, so the editor only assigns them.
 */
export function AgentLabelPicker({
  labels,
  value,
  disabled,
  onChange,
}: {
  labels: NamedRef[];
  value: string[];
  disabled: boolean;
  onChange: (ids: string[]) => void;
}) {
  const ui = useAppTranslation();
  const [open, setOpen] = useState(false);
  const selected = value.flatMap((id) => labels.filter((label) => label.id === id));
  return (
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
              {labels.length > 0 && <CommandInput placeholder={ui("Tìm nhãn…")} />}
              <CommandList>
                <CommandEmpty>
                  {labels.length === 0
                    ? ui("Chưa có nhãn. Quản trị viên tạo nhãn trong Quản trị › Trợ lý.")
                    : ui("Không tìm thấy nhãn.")}
                </CommandEmpty>
                <CommandGroup>
                  {labels.map((label) => {
                    const checked = value.includes(label.id);
                    return (
                      <CommandItem
                        key={label.id}
                        value={label.name}
                        onSelect={() =>
                          onChange(
                            checked ? value.filter((id) => id !== label.id) : [...value, label.id],
                          )
                        }
                      >
                        <Tag aria-hidden="true" className="text-content-muted" />
                        <span className="flex-1 truncate">{label.name}</span>
                        {checked && <Check aria-hidden="true" />}
                      </CommandItem>
                    );
                  })}
                </CommandGroup>
              </CommandList>
            </Command>
          </PopoverContent>
        </Popover>
      )}
    </div>
  );
}

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
    <EditorField
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
                aria-label={ui("Xóa câu hỏi gợi ý {{v1}}", { v1: index + 1 })}
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
          <Plus data-icon="inline-start" aria-hidden="true" />
          {ui("Thêm câu gợi ý")}
        </Button>
      )}
    </EditorField>
  );
}

/**
 * A token limit bound to the `form.AppField` around it. Following the model it shows the model's own limit, read
 * only; a custom value is typed and checked against `cap`, the highest the server accepts.
 */
export function TokenLimitField({
  id,
  label,
  help,
  custom,
  min,
  cap,
  modelValue,
}: {
  id: string;
  label: string;
  help: string;
  custom: boolean;
  min: number;
  cap: number;
  modelValue: string;
}) {
  const ui = useAppTranslation();
  const { field, invalid, errors } = useFieldValidity<string>();
  const shown = custom && invalid;
  const errorId = `${id}-error`;
  return (
    <Field data-invalid={shown || undefined} className="min-w-0">
      <div className="flex items-center gap-1">
        <FieldLabel htmlFor={id}>{label}</FieldLabel>
        <HelpPopover label={label}>
          <p className="font-secondary-body text-content-secondary">{help}</p>
        </HelpPopover>
      </div>
      <div className="relative">
        <Input
          id={id}
          type={custom ? "number" : "text"}
          inputMode="numeric"
          min={custom ? min : undefined}
          max={custom ? cap : undefined}
          readOnly={!custom}
          aria-invalid={shown || undefined}
          aria-describedby={shown ? errorId : undefined}
          className={cn(!custom && "cursor-default pr-28")}
          value={custom ? field.state.value : modelValue}
          placeholder={custom ? formatTokens(cap) : undefined}
          onBlur={field.handleBlur}
          onChange={(event) => field.handleChange(event.target.value)}
        />
        {!custom && (
          <span className="pointer-events-none absolute inset-y-0 right-3 flex items-center gap-1.5 font-secondary-body text-content-muted">
            <Lock aria-hidden="true" className="size-3.5" />
            {ui("Theo model")}
          </span>
        )}
      </div>
      {shown ? (
        <FieldError id={errorId} errors={errors} />
      ) : (
        <FieldDescription>
          {custom
            ? ui("Tối đa {{v1}} token.", { v1: formatTokens(cap) })
            : ui("Giới hạn tối đa của model hiện tại.")}
        </FieldDescription>
      )}
    </Field>
  );
}
