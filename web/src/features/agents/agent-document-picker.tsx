import { useAppTranslation } from "@/i18n/use-app-translation";
import { useEffect, useRef, useState } from "react";
import { useQueries, useQueryClient } from "@tanstack/react-query";
import {
  ArrowLeft,
  Check,
  ChevronRight,
  Ellipsis,
  FileText,
  FolderOpen,
  Library,
  Paperclip,
  Plus,
  Upload,
  X,
} from "lucide-react";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { Button } from "@/components/ui/button";
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandSeparator,
} from "@/components/ui/command";
import { IconButton } from "@/components/ui/icon-button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { ChatFilePickerContent } from "@/features/library/file-picker";
import {
  chatAttachmentProblem,
  chatFileOf,
  uploadChatFile,
  waitForChatFile,
} from "@/features/library/files";
import { invalidateLibrary } from "@/features/library/library";
import type { ErrorMessage } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { findSourceProvider } from "@/features/sources/shared/source-provider-catalog";
import { getChatFileOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { LoadFailure } from "./agent-editor-fields";

type Option = { id: string; name: string; type?: string };
type Kind = "source" | "documentSet" | "file";
export type AgentDocuments = { sourceIds: string[]; documentSetIds: string[]; fileIds: string[] };
type Choice = { kind: Kind; id: string; name: string; type?: string };
/** A file being uploaded from the editor; it joins the list once the library has it READY. */
type PendingUpload = {
  key: string;
  name: string;
  progress: number;
  phase: "uploading" | "processing" | "failed";
  error?: ErrorMessage;
};

/** Chosen entries kept in view before the rest move behind "Xem thêm". */
const visibleRows = 5;

const keyOf: Record<Kind, keyof AgentDocuments> = {
  source: "sourceIds",
  documentSet: "documentSetIds",
  file: "fileIds",
};

/**
 * One list of what the assistant answers from, Sources, Document Sets and files together with each row tagged by
 * kind, added from one menu (Onyx `AgentKnowledgePane`). Files come from the library or are uploaded from here (Onyx
 * "Your Files: Recent or new uploads"): an upload is an ordinary library file, attached once it is READY because Save
 * admits only READY files. Attached files are always read while the switch is on; Sources and Document Sets narrow
 * what is searched, so with neither attached the state line says the assistant searches every readable document.
 */
export function AgentDocumentPicker({
  sources,
  documentSets,
  known,
  value,
  withFiles,
  pending,
  failed,
  unavailable = failed,
  disabled,
  searchesEverything,
  onRetry,
  onChange,
}: {
  sources: Option[];
  documentSets: Option[];
  /** Names the saved assistant carries, for a choice the reader can no longer list. */
  known: { sources: Option[]; documentSets: Option[] };
  value: AgentDocuments;
  withFiles: boolean;
  pending: boolean;
  failed: boolean;
  /** Nothing can be added: every list failed. */
  unavailable?: boolean;
  disabled: boolean;
  /** The switch is on and no Source or Document Set narrows the search. */
  searchesEverything: boolean;
  onRetry: () => void;
  onChange: (value: AgentDocuments) => void;
}) {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const cache = useQueryClient();
  const [uploads, setUploads] = useState<PendingUpload[]>([]);
  // An upload finishes after later edits, so it adds to the value as it is then, not as it was when it started.
  const latest = useRef({ value, onChange });
  useEffect(() => {
    latest.current = { value, onChange };
  });
  const controllers = useRef(new Map<string, AbortController>());
  useEffect(() => {
    const running = controllers.current;
    return () => running.forEach((controller) => controller.abort());
  }, []);
  const changeUpload = (key: string, next: Partial<PendingUpload>) =>
    setUploads((current) =>
      current.map((item) => (item.key === key ? { ...item, ...next } : item)),
    );
  const dropUpload = (key: string) => {
    controllers.current.get(key)?.abort();
    controllers.current.delete(key);
    setUploads((current) => current.filter((item) => item.key !== key));
  };
  const upload = (picked: readonly File[]) => {
    for (const file of picked) {
      const key = crypto.randomUUID();
      const controller = new AbortController();
      controllers.current.set(key, controller);
      setUploads((current) => [
        ...current,
        { key, name: file.name, progress: 0, phase: "uploading" },
      ]);
      void uploadChatFile(file, key, controller.signal, (progress) =>
        changeUpload(key, { progress }),
      )
        .then(async (saved) => {
          changeUpload(key, { phase: "processing" });
          const ready = await waitForChatFile(saved.id, controller.signal);
          const { value: now, onChange: change } = latest.current;
          if (!now.fileIds.includes(ready.id))
            change({ ...now, fileIds: [...now.fileIds, ready.id] });
          controllers.current.delete(key);
          setUploads((current) => current.filter((item) => item.key !== key));
          await invalidateLibrary(cache);
        })
        .catch((cause: unknown) => {
          if (controller.signal.aborted) return;
          controllers.current.delete(key);
          changeUpload(key, { phase: "failed", error: chatAttachmentProblem(cause) });
        });
    }
  };
  const files = useQueries({
    queries: (withFiles ? value.fileIds : []).map((fileId) => ({
      ...getChatFileOptions({ path: { fileId } }),
      select: chatFileOf,
      retry: false,
    })),
  });
  const named = (options: Option[], saved: Option[], id: string) =>
    options.find((option) => option.id === id) ?? saved.find((option) => option.id === id);
  const chosen: Choice[] = [
    ...value.sourceIds.map((id) => ({
      kind: "source" as const,
      id,
      name: named(sources, known.sources, id)?.name ?? "",
      type: named(sources, known.sources, id)?.type,
    })),
    ...value.documentSetIds.map((id) => ({
      kind: "documentSet" as const,
      id,
      name: named(documentSets, known.documentSets, id)?.name ?? "",
    })),
    ...(withFiles ? value.fileIds : []).map((id, index) => ({
      kind: "file" as const,
      id,
      name: files[index]?.data?.filename ?? "",
    })),
  ];
  const remove = (choice: Choice) =>
    onChange({
      ...value,
      [keyOf[choice.kind]]: value[keyOf[choice.kind]].filter((id) => id !== choice.id),
    });
  const kindName: Record<Kind, string> = {
    source: ui("Nguồn"),
    documentSet: ui("Bộ tài liệu"),
    file: ui("Tệp"),
  };
  const row = (choice: Choice) => {
    const provider = choice.kind === "source" ? findSourceProvider(choice.type) : undefined;
    const Icon =
      provider?.icon ??
      (choice.kind === "documentSet" ? FolderOpen : choice.kind === "file" ? FileText : Library);
    return (
      <SettingRow
        key={`${choice.kind}-${choice.id}`}
        icon={<Icon />}
        title={choice.name || ui("Không còn khả dụng (đang giữ lựa chọn)")}
        description={provider ? ui("Nguồn · {{v1}}", { v1: provider.name }) : kindName[choice.kind]}
        control={
          !disabled && (
            <IconButton
              type="button"
              size="sm"
              prominence="tertiary"
              aria-label={ui("Bỏ {{v1}}", { v1: choice.name || kindName[choice.kind] })}
              onClick={() => remove(choice)}
            >
              <X />
            </IconButton>
          )
        }
      />
    );
  };
  const uploadRow = (item: PendingUpload) => (
    <SettingRow
      key={item.key}
      icon={<FileText />}
      title={item.name}
      description={
        item.phase === "uploading"
          ? ui("Đang tải lên {{n}}%", { n: Math.round(item.progress) })
          : item.phase === "processing"
            ? ui("Đang xử lý")
            : item.error
              ? problemMessage(item.error)
              : ui("Không tải lên được")
      }
      control={
        <IconButton
          type="button"
          size="sm"
          prominence="tertiary"
          aria-label={
            item.phase === "failed"
              ? ui("Bỏ {{v1}}", { v1: item.name })
              : ui("Hủy tải lên {{v1}}", { v1: item.name })
          }
          onClick={() => dropUpload(item.key)}
        >
          <X />
        </IconButton>
      }
    />
  );
  const menu = !disabled && (
    <AddMenu
      sources={sources}
      documentSets={documentSets}
      value={value}
      withFiles={withFiles}
      disabled={pending || unavailable}
      onChange={onChange}
      onUpload={upload}
    />
  );
  const state = searchesEverything && (
    <span className="font-secondary-body text-content-muted">
      {value.fileIds.length > 0
        ? ui("Tìm thêm trong mọi tài liệu được phép đọc")
        : ui("Tìm trong mọi tài liệu được phép đọc")}
    </span>
  );
  const add = (menu || state) && (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
      {menu}
      {state}
    </div>
  );
  return (
    <div className="flex flex-col gap-3">
      {failed && <LoadFailure onRetry={onRetry}>{ui("Không tải được tài liệu.")}</LoadFailure>}
      {chosen.length === 0 && uploads.length === 0 ? (
        add
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
                      {ui("Xem thêm {{n}} tài liệu", { n: chosen.length - visibleRows })}
                    </span>
                    <ChevronRight
                      aria-hidden="true"
                      className="size-4 shrink-0 text-content-muted"
                    />
                  </button>
                </PopoverTrigger>
                <PopoverContent className="max-h-80 w-96 max-w-[calc(100vw-2rem)] overflow-y-auto p-0">
                  <SettingRows className="border-0">
                    {chosen.slice(visibleRows).map(row)}
                  </SettingRows>
                </PopoverContent>
              </Popover>
            )}
            {uploads.map(uploadRow)}
          </SettingRows>
          {add}
        </>
      )}
    </div>
  );
}

function AddMenu({
  sources,
  documentSets,
  value,
  withFiles,
  disabled,
  onChange,
  onUpload,
}: {
  sources: Option[];
  documentSets: Option[];
  value: AgentDocuments;
  withFiles: boolean;
  disabled: boolean;
  onChange: (value: AgentDocuments) => void;
  onUpload: (files: readonly File[]) => void;
}) {
  const ui = useAppTranslation();
  const [open, setOpen] = useState(false);
  // Outside the popover, which unmounts its content when it closes for the system file dialog.
  const input = useRef<HTMLInputElement>(null);
  const [view, setView] = useState<"list" | "files">("list");
  const toggle = (kind: Kind, id: string) => {
    const ids = value[keyOf[kind]];
    onChange({
      ...value,
      [keyOf[kind]]: ids.includes(id) ? ids.filter((item) => item !== id) : [...ids, id],
    });
  };
  const group = (kind: Kind, heading: string, options: Option[]) =>
    options.length > 0 && (
      <CommandGroup heading={heading}>
        {options.map((option) => {
          const Icon =
            kind === "source" ? (findSourceProvider(option.type)?.icon ?? Library) : FolderOpen;
          const checked = value[keyOf[kind]].includes(option.id);
          return (
            <CommandItem
              key={option.id}
              value={`${option.name} ${option.id}`}
              onSelect={() => toggle(kind, option.id)}
            >
              <Icon aria-hidden="true" className="size-4 shrink-0" />
              <span className="flex-1 truncate">{option.name}</span>
              {checked && <Check aria-hidden="true" />}
            </CommandItem>
          );
        })}
      </CommandGroup>
    );
  return (
    <>
      {withFiles && (
        <input
          ref={input}
          type="file"
          multiple
          hidden
          aria-label={ui("Tải tệp lên")}
          onChange={(event) => {
            const picked = Array.from(event.target.files ?? []);
            event.target.value = "";
            if (picked.length > 0) onUpload(picked);
          }}
        />
      )}
      <Popover
        open={open}
        onOpenChange={(next) => {
          setOpen(next);
          if (!next) setView("list");
        }}
      >
        <PopoverTrigger asChild>
          <Button type="button" size="sm" prominence="secondary" disabled={disabled}>
            <Plus data-icon="inline-start" aria-hidden="true" />
            {ui("Thêm tài liệu")}
          </Button>
        </PopoverTrigger>
        <PopoverContent align="start" className="w-80 max-w-[calc(100vw-2rem)] p-0">
          {view === "files" ? (
            <div className="flex flex-col gap-2 p-3">
              <Button
                type="button"
                size="sm"
                prominence="tertiary"
                className="self-start"
                onClick={() => setView("list")}
              >
                <ArrowLeft data-icon="inline-start" aria-hidden="true" />
                {ui("Tệp trong Thư viện")}
              </Button>
              <ChatFilePickerContent
                selected={value.fileIds}
                onSelect={(fileIds) => onChange({ ...value, fileIds })}
              />
            </div>
          ) : (
            <Command>
              <CommandInput placeholder={ui("Tìm nguồn hoặc bộ tài liệu…")} />
              <CommandList>
                <CommandEmpty>{ui("Không tìm thấy tài liệu.")}</CommandEmpty>
                {group("source", ui("Nguồn"), sources)}
                {group("documentSet", ui("Bộ tài liệu"), documentSets)}
                {withFiles && (
                  <>
                    <CommandSeparator />
                    <CommandGroup>
                      <CommandItem value="__files__" onSelect={() => setView("files")}>
                        <Paperclip aria-hidden="true" className="size-4 shrink-0" />
                        <span className="flex-1">{ui("Tệp trong Thư viện")}</span>
                        {value.fileIds.length > 0 && (
                          <span className="font-secondary-body text-content-muted tabular-nums">
                            {value.fileIds.length}
                          </span>
                        )}
                        <ChevronRight aria-hidden="true" />
                      </CommandItem>
                      <CommandItem
                        value="__upload__"
                        onSelect={() => {
                          setOpen(false);
                          input.current?.click();
                        }}
                      >
                        <Upload aria-hidden="true" className="size-4 shrink-0" />
                        <span className="flex-1">{ui("Tải tệp lên")}</span>
                      </CommandItem>
                    </CommandGroup>
                  </>
                )}
              </CommandList>
            </Command>
          )}
        </PopoverContent>
      </Popover>
    </>
  );
}
