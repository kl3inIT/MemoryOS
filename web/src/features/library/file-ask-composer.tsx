import { useRef, useState, type ComponentType } from "react";
import { ArrowUp, FileText, Mic, Paperclip, Plus, Upload, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { useDictationInput } from "@/features/voice/use-dictation-input";
import { useVoiceAvailability } from "@/features/voice/use-voice-availability";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import { menuRow } from "@/components/composites/menu-row";
import { ChatLibraryPicker } from "./library-picker";
import type { LibraryFile } from "./library";

/** Files put on the question besides the file being read. */
export type AskExtras = {
  /** Library files chosen from the `+` menu; each is copied into an upload, as Chat copies them. */
  library: readonly LibraryFile[];
  /** Files chosen from the device; each is uploaded before the conversation opens. */
  uploads: readonly File[];
};

/** As Chat: a message carries at most twenty files, the file being read included. */
const MAX_FILES = 20;

/**
 * A question about the file that is open, asked where the file is read. It is the Chat composer's shape —
 * files, model, dictation, send — but not a chat surface: submitting hands the question to Chat, which owns
 * the conversation and the answer.
 */
export function ChatFileAskComposer({
  name,
  onAsk,
  ModelPicker,
}: {
  name: string;
  onAsk: (question: string, extras: AskExtras) => Promise<void>;
  /** Chat's model choice, which the question is asked with. */
  ModelPicker: ComponentType<{ disabled: boolean }>;
}) {
  const ui = useAppTranslation();
  const [question, setQuestion] = useState("");
  const [pending, setPending] = useState(false);
  const [failed, setFailed] = useState(false);
  const [menu, setMenu] = useState(false);
  const [picking, setPicking] = useState(false);
  const [library, setLibrary] = useState<readonly LibraryFile[]>([]);
  const [uploads, setUploads] = useState<readonly File[]>([]);
  const input = useRef<HTMLTextAreaElement>(null);
  const device = useRef<HTMLInputElement>(null);
  const dictation = useDictationInput({
    text: question,
    onText: setQuestion,
    onFinished: () => input.current?.focus(),
  });
  const canDictate = useVoiceAvailability().data?.sttAvailable === true;
  // The file being read is attached too, so it takes one of the twenty places.
  const room = MAX_FILES - 1 - library.length - uploads.length;

  const submit = () => {
    if (pending || dictation.listening) return;
    setPending(true);
    setFailed(false);
    void onAsk(question.trim(), { library, uploads })
      .catch(() => setFailed(true))
      .finally(() => setPending(false));
  };

  return (
    <div className="pointer-events-none absolute inset-x-0 bottom-0 flex justify-center px-3 pb-3">
      <form
        className="pointer-events-auto flex w-full max-w-3xl flex-col gap-2 rounded-2xl border border-border-default bg-surface-raised p-2.5 shadow-lg transition-colors focus-within:border-border-strong"
        onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}
      >
        {(library.length > 0 || uploads.length > 0) && (
          <ul aria-label={ui("Tệp đính kèm thêm")} className="flex flex-wrap gap-1.5">
            {library.map((file) => (
              <AttachedChip
                key={`library:${file.id}`}
                label={file.filename}
                onRemove={() => setLibrary(library.filter((item) => item.id !== file.id))}
              />
            ))}
            {uploads.map((file, index) => (
              <AttachedChip
                key={`upload:${index}:${file.name}`}
                label={file.name}
                onRemove={() => setUploads(uploads.filter((_, at) => at !== index))}
              />
            ))}
          </ul>
        )}
        <textarea
          ref={input}
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          onKeyDown={(event) => {
            if (event.key !== "Enter" || event.shiftKey || event.nativeEvent.isComposing) return;
            event.preventDefault();
            submit();
          }}
          disabled={pending}
          rows={1}
          maxLength={32000}
          aria-label={ui("Hỏi về {{name}}", { name })}
          placeholder={dictation.listening ? ui("Đang nghe…") : ui("Hỏi về tệp này…")}
          className="max-h-40 min-h-11 w-full resize-none bg-transparent px-2.5 py-1.5 text-base leading-6 outline-none placeholder:text-content-muted"
        />
        {dictation.failure && (
          <p role="alert" className="px-2 font-secondary-body text-status-danger-content">
            {ui(dictation.failure)}
          </p>
        )}
        {failed && (
          <p role="alert" className="px-2 font-secondary-body text-status-danger-content">
            {ui("Không mở được hội thoại.")}
          </p>
        )}
        <div className="flex flex-nowrap items-center justify-between gap-2">
          <Popover open={menu} onOpenChange={setMenu}>
            <PopoverTrigger asChild>
              <IconButton
                size="sm"
                prominence="internal"
                aria-label={ui("Thêm vào câu hỏi")}
                title={ui("Thêm vào câu hỏi")}
                disabled={pending}
              >
                <Plus />
              </IconButton>
            </PopoverTrigger>
            <PopoverContent
              side="top"
              align="start"
              collisionPadding={16}
              className="w-72 max-w-[calc(100vw-2rem)] p-1.5"
            >
              <div className="flex flex-col">
                <button
                  type="button"
                  className={menuRow}
                  disabled={room <= 0}
                  onClick={() => {
                    setMenu(false);
                    device.current?.click();
                  }}
                >
                  <Upload aria-hidden="true" />
                  {ui("Tải tệp lên")}
                </button>
                <button
                  type="button"
                  className={menuRow}
                  disabled={room <= 0}
                  onClick={() => {
                    setMenu(false);
                    setPicking(true);
                  }}
                >
                  <FileText aria-hidden="true" />
                  {ui("Chọn tệp đã có")}
                </button>
              </div>
            </PopoverContent>
          </Popover>
          <input
            ref={device}
            type="file"
            multiple
            className="hidden"
            onChange={(event) => {
              const chosen = [...(event.target.files ?? [])].slice(0, Math.max(room, 0));
              if (chosen.length > 0) setUploads([...uploads, ...chosen]);
              event.target.value = "";
            }}
          />
          <div className="flex min-w-0 items-center gap-1">
            <ModelPicker disabled={pending} />
            {canDictate && (
              <IconButton
                type="button"
                size="sm"
                prominence="internal"
                aria-pressed={dictation.listening}
                aria-label={dictation.listening ? ui("Dừng nghe") : ui("Đọc câu hỏi")}
                title={dictation.listening ? ui("Dừng nghe") : ui("Đọc câu hỏi")}
                pending={dictation.status === "starting" || dictation.status === "finishing"}
                onClick={() => void dictation.toggle()}
              >
                <Mic
                  aria-hidden="true"
                  className={cn(dictation.listening && "animate-pulse motion-reduce:animate-none")}
                />
              </IconButton>
            )}
            <IconButton
              type="submit"
              prominence="primary"
              aria-label={ui("Hỏi trong Chat")}
              title={ui("Hỏi trong Chat")}
              pending={pending}
              disabled={dictation.listening}
            >
              <ArrowUp />
            </IconButton>
          </div>
        </div>
      </form>
      <ChatLibraryPicker
        open={picking}
        onOpenChange={setPicking}
        selected={library.map((file) => file.id)}
        onAttach={(chosen) =>
          setLibrary([
            ...library,
            ...chosen.filter((file) => !library.some((held) => held.id === file.id)),
          ])
        }
      />
    </div>
  );
}

function AttachedChip({ label, onRemove }: { label: string; onRemove: () => void }) {
  const ui = useAppTranslation();
  return (
    <li className="flex max-w-56 items-center gap-1.5 rounded-lg border border-border-subtle bg-surface-sunken py-1 pl-2 pr-1 font-secondary-body">
      <Paperclip className="size-3.5 shrink-0 text-content-muted" aria-hidden="true" />
      <span className="min-w-0 truncate" title={label}>
        {label}
      </span>
      <Button
        size="sm"
        prominence="internal"
        aria-label={ui("Gỡ {{name}}", { name: label })}
        className="size-6 shrink-0 p-0"
        onClick={onRemove}
      >
        <X className="size-3.5" aria-hidden="true" />
      </Button>
    </li>
  );
}
