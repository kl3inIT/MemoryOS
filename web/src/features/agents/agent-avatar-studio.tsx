import { useAppTranslation } from "@/i18n/use-app-translation";
import { useRef, useState, type ClipboardEvent, type DragEvent } from "react";
import { ImagePlus, Pencil, RefreshCw, Upload } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { FieldError } from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { cn } from "@/lib/utils";
import { ChatFilePicker } from "@/features/library/file-picker";
import { uploadChatFile, waitForChatFile } from "@/features/library/files";
import { AgentAvatar } from "./agent-avatar";
import { agentFace, randomAvatarSeed } from "./agent-face";

const avatarTypes = new Set(["image/png", "image/jpeg", "image/webp", "image/gif"]);
const avatarMaxBytes = 2 * 1024 * 1024;
const candidateCount = 8;

function candidates(first: string) {
  return [first, ...Array.from({ length: candidateCount - 1 }, randomAvatarSeed)];
}

/**
 * The agent's avatar as a button that opens a small studio, after Rakazo's avatar studio: pick one of eight faces,
 * draw another eight, return to the saved face, or use an image (chosen, dropped or pasted). The choice is kept in
 * the editor's values and saved with the rest of the form.
 */
export function AgentAvatarStudio({
  agentId,
  avatarSeed,
  savedSeed,
  hasAvatar,
  avatarFileId,
  allowImage,
  disabled,
  onSeed,
  onImage,
  onClearImage,
}: {
  agentId: string;
  avatarSeed: string;
  /** The seed the agent was saved with, which "Mặc định" returns to. */
  savedSeed: string;
  hasAvatar: boolean;
  avatarFileId: string | null;
  allowImage: boolean;
  disabled: boolean;
  onSeed: (seed: string) => void;
  onImage: (fileId: string) => void;
  onClearImage: () => void;
}) {
  const ui = useAppTranslation();
  const [open, setOpen] = useState(false);
  const [tab, setTab] = useState("faces");
  const [faces, setFaces] = useState(() => candidates(avatarSeed));
  const [error, setError] = useState<string>();
  const [uploading, setUploading] = useState(false);
  const [dragging, setDragging] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const usesImage = hasAvatar || !!avatarFileId;

  async function takeFile(file: File) {
    if (!avatarTypes.has(file.type) || file.size > avatarMaxBytes) {
      setError(ui("Dùng ảnh PNG, JPEG, WebP hoặc GIF tối đa 2 MiB."));
      return;
    }
    setError(undefined);
    setUploading(true);
    const abort = new AbortController();
    try {
      const saved = await uploadChatFile(file, crypto.randomUUID(), abort.signal, () => {});
      const ready =
        saved.status === "READY" ? saved : await waitForChatFile(saved.id, abort.signal);
      onImage(ready.id);
    } catch {
      setError(ui("Không tải được ảnh lên. Thử lại."));
    } finally {
      setUploading(false);
    }
  }

  function onPaste(event: ClipboardEvent) {
    const file = Array.from(event.clipboardData.files).find((item) =>
      item.type.startsWith("image/"),
    );
    if (file) {
      event.preventDefault();
      void takeFile(file);
    }
  }

  function onDrop(event: DragEvent) {
    event.preventDefault();
    setDragging(false);
    const file = event.dataTransfer.files[0];
    if (file) void takeFile(file);
  }

  const preview = (size: "xl") => (
    <span className="relative inline-grid">
      <AgentAvatar
        agent={{ id: agentId, avatarSeed, hasAvatar: hasAvatar && !avatarFileId }}
        size={size}
      />
      {avatarFileId && (
        <span className="absolute inset-0 grid place-items-center rounded-3xl bg-surface-sunken font-secondary-action text-content-secondary">
          {ui("Ảnh mới")}
        </span>
      )}
    </span>
  );

  return (
    <div className="flex flex-col items-start gap-1">
      <button
        type="button"
        disabled={disabled}
        aria-label={ui("Đổi ảnh đại diện")}
        onClick={() => {
          setFaces(candidates(avatarSeed));
          setTab(usesImage ? "image" : "faces");
          setError(undefined);
          setOpen(true);
        }}
        className="group relative rounded-3xl transition-transform outline-none hover:scale-102 focus-visible:ring-3 focus-visible:ring-focus-ring/40 disabled:cursor-default disabled:hover:scale-100 motion-reduce:transition-none"
      >
        {preview("xl")}
        {!disabled && (
          <span className="absolute -right-1 -bottom-1 grid size-7 place-items-center rounded-full border border-border-subtle bg-surface-raised text-content-secondary shadow-hover">
            <Pencil aria-hidden="true" className="size-3.5" />
          </span>
        )}
      </button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="sm:max-w-90" onPaste={allowImage ? onPaste : undefined}>
          <DialogHeader>
            <DialogTitle>{ui("Ảnh đại diện")}</DialogTitle>
            <DialogDescription className="sr-only">
              {ui("Chọn một khuôn mặt hoặc dùng ảnh của bạn.")}
            </DialogDescription>
          </DialogHeader>
          <div className="grid place-items-center py-1">{preview("xl")}</div>
          <Tabs value={tab} onValueChange={setTab}>
            {allowImage && (
              <TabsList variant="line" className="w-full justify-start">
                <TabsTrigger value="faces">{ui("Khuôn mặt")}</TabsTrigger>
                <TabsTrigger value="image">{ui("Ảnh")}</TabsTrigger>
              </TabsList>
            )}
            <TabsContent value="faces" className="mt-2">
              <div className="flex flex-col gap-3">
                <div
                  role="radiogroup"
                  aria-label={ui("Khuôn mặt")}
                  className="grid grid-cols-4 gap-2"
                >
                  {faces.map((seed, index) => {
                    const checked = !usesImage && seed === avatarSeed;
                    return (
                      <button
                        key={seed}
                        type="button"
                        role="radio"
                        aria-checked={checked}
                        aria-label={ui("Khuôn mặt {{v1}}", { v1: index + 1 })}
                        onClick={() => onSeed(seed)}
                        className={cn(
                          "rounded-2xl transition-transform outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/40 active:scale-95 motion-reduce:transition-none",
                          checked
                            ? "ring-2 ring-content-primary ring-offset-2 ring-offset-surface-overlay"
                            : "hover:scale-105",
                        )}
                      >
                        <img
                          src={agentFace(seed)}
                          alt=""
                          className="aspect-square w-full rounded-2xl"
                        />
                      </button>
                    );
                  })}
                </div>
                <div className="flex items-center justify-between gap-2">
                  <Button
                    type="button"
                    size="sm"
                    prominence="tertiary"
                    onClick={() => setFaces(candidates(avatarSeed))}
                  >
                    <RefreshCw data-icon="inline-start" aria-hidden="true" />
                    {ui("Đổi bộ khác")}
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    prominence="tertiary"
                    disabled={!usesImage && avatarSeed === savedSeed}
                    onClick={() => {
                      onSeed(savedSeed);
                      setFaces(candidates(savedSeed));
                    }}
                  >
                    {ui("Mặc định")}
                  </Button>
                </div>
              </div>
            </TabsContent>
            {allowImage && (
              <TabsContent value="image" className="mt-2">
                <div className="flex flex-col gap-3">
                  <button
                    type="button"
                    disabled={uploading}
                    onClick={() => input.current?.click()}
                    onDragOver={(event) => {
                      event.preventDefault();
                      setDragging(true);
                    }}
                    onDragLeave={() => setDragging(false)}
                    onDrop={onDrop}
                    className={cn(
                      "flex flex-col items-center gap-2 rounded-xl border border-dashed border-border-default px-4 py-6 text-center outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/40",
                      dragging ? "bg-surface-subtle" : "hover:bg-surface-subtle",
                    )}
                  >
                    <span className="grid size-10 place-items-center rounded-full bg-surface-sunken text-content-secondary">
                      {uploading ? <Spinner /> : <Upload aria-hidden="true" className="size-4.5" />}
                    </span>
                    <span className="font-main-ui-action">{ui("Kéo thả, dán hoặc chọn ảnh")}</span>
                    <span className="font-secondary-action text-content-muted">
                      {ui("PNG, JPEG, WebP hoặc GIF, tối đa 2 MiB")}
                    </span>
                  </button>
                  <input
                    ref={input}
                    type="file"
                    accept="image/png,image/jpeg,image/webp,image/gif"
                    className="sr-only"
                    tabIndex={-1}
                    onChange={(event) => {
                      const file = event.target.files?.[0];
                      event.target.value = "";
                      if (file) void takeFile(file);
                    }}
                  />
                  <div className="flex items-center justify-between gap-2">
                    <ChatFilePicker
                      selected={avatarFileId ? [avatarFileId] : []}
                      onSelect={(ids, files) => {
                        const image = files.filter((file) => ids.includes(file.id)).at(-1);
                        if (!image) return;
                        if (!avatarTypes.has(image.mediaType) || image.sizeBytes > avatarMaxBytes) {
                          setError(ui("Dùng ảnh PNG, JPEG, WebP hoặc GIF tối đa 2 MiB."));
                          return;
                        }
                        setError(undefined);
                        onImage(image.id);
                      }}
                      trigger={
                        <Button type="button" size="sm" prominence="tertiary">
                          <ImagePlus data-icon="inline-start" aria-hidden="true" />
                          {ui("Chọn từ thư viện")}
                        </Button>
                      }
                    />
                    {usesImage && (
                      <Button type="button" size="sm" prominence="tertiary" onClick={onClearImage}>
                        {ui("Bỏ ảnh")}
                      </Button>
                    )}
                  </div>
                </div>
              </TabsContent>
            )}
          </Tabs>
          {error && <FieldError>{error}</FieldError>}
          <DialogFooter>
            <Button type="button" onClick={() => setOpen(false)}>
              {ui("Xong")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
