import { useState } from "react";
import { ComposerPrimitive } from "@assistant-ui/react";
import {
  ArrowLeft,
  ChevronRight,
  FileText,
  Globe,
  ImagePlus,
  Plus,
  Telescope,
  Upload,
  X,
} from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ChatFilePickerContent, ChatRecentFilesDialog } from "./chat-file-picker";
import { composerMenuRow } from "./chat-composer-menu-row";
import { ChatWebModes, ChatWebToggle } from "./chat-web-options";
import type { WebSearchMode } from "./chat-web-preference";
import { ChatImageToggle } from "./chat-image-options";
import { ChatMcpServers, ChatMcpToggle } from "./chat-mcp-options";
import { useMcpConnections } from "./chat-mcp-connections";
import type { ImageMode } from "./chat-image";
import { useComposerFileSelection } from "./use-composer-file-selection";

/**
 * The composer's single `+` entry for files and tools, with the active Web tool as a chip.
 * Only implemented capabilities are listed.
 */
export function ChatComposerMenu({
  web,
  image,
  mcp,
  research,
  disabled,
  allowed,
}: {
  web: {
    value: WebSearchMode;
    onChange: (mode: WebSearchMode) => void;
    sessionId?: string;
    modelId?: string;
  };
  image: {
    value: ImageMode;
    onChange: (mode: ImageMode) => void;
  };
  mcp: {
    selected: string[];
    onChange: (ids: string[]) => void;
    sessionId?: string;
  };
  /** Present only where Deep research is available: enabled for the organization and outside Projects. */
  research?: {
    value: boolean;
    onChange: (enabled: boolean) => void;
  };
  disabled: boolean;
  /** Tools the conversation's agent allows (Onyx per-agent tools); absent means all. */
  allowed?: { web: boolean; image: boolean; mcpServerIds: string[] | null };
}) {
  const ui = useAppTranslation();
  const files = useComposerFileSelection();
  const mcpConnections = useMcpConnections();
  const [open, setOpen] = useState(false);
  const [view, setView] = useState<"root" | "files" | "web" | "mcp">("root");
  const [allFiles, setAllFiles] = useState(false);
  const close = () => {
    setOpen(false);
    setView("root");
  };
  return (
    <div className="flex min-w-0 items-center gap-1">
      <Popover
        open={open}
        onOpenChange={(next) => {
          setOpen(next);
          if (!next) setView("root");
        }}
      >
        <PopoverTrigger asChild>
          <IconButton
            size="sm"
            prominence="internal"
            aria-label={ui("Thêm vào câu hỏi")}
            title={ui("Thêm vào câu hỏi")}
            disabled={disabled}
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
          {view === "root" && (
            <div className="flex flex-col">
              <ComposerPrimitive.AddAttachment asChild>
                <button
                  type="button"
                  className={composerMenuRow}
                  disabled={files.full}
                  onClick={close}
                >
                  <Upload aria-hidden="true" />
                  {ui("Tải tệp lên")}
                </button>
              </ComposerPrimitive.AddAttachment>
              <button type="button" className={composerMenuRow} onClick={() => setView("files")}>
                <FileText aria-hidden="true" />
                <span className="flex-1">{ui("Chọn tệp đã có")}</span>
                <ChevronRight aria-hidden="true" />
              </button>
              <div role="separator" className="my-1 border-t border-border-subtle" />
              {allowed?.web !== false && (
                <ChatWebToggle {...web} onDone={close} onConfigure={() => setView("web")} />
              )}
              {allowed?.image !== false && <ChatImageToggle {...image} onDone={close} />}
              <ChatMcpToggle
                selected={mcp.selected}
                available={
                  mcpConnections.data?.filter(
                    (connection) =>
                      !allowed?.mcpServerIds || allowed.mcpServerIds.includes(connection.id),
                  ).length ?? 0
                }
                onOpen={() => setView("mcp")}
              />
            </div>
          )}
          {view === "files" && (
            <div className="flex flex-col gap-2">
              <Button
                size="sm"
                prominence="internal"
                aria-label={ui("Quay lại")}
                className="self-start"
                onClick={() => setView("root")}
              >
                <ArrowLeft className="size-4" aria-hidden="true" />
                {ui("Chọn tệp đã có")}
              </Button>
              <ChatFilePickerContent
                compact
                selected={files.selected}
                disabled={disabled}
                uploadAction={null}
                onMore={() => {
                  close();
                  setAllFiles(true);
                }}
                onSelect={(ids, list) => {
                  files.select(ids, list);
                  close();
                }}
              />
            </div>
          )}
          {view === "web" && (
            <ChatWebModes {...web} onDone={close} onBack={() => setView("root")} />
          )}
          {view === "mcp" && (
            <ChatMcpServers
              selected={mcp.selected}
              onChange={mcp.onChange}
              sessionId={mcp.sessionId}
              allowedIds={allowed?.mcpServerIds}
              onBack={() => setView("root")}
            />
          )}
        </PopoverContent>
      </Popover>
      {research && (
        <Button
          type="button"
          size="sm"
          prominence={research.value ? "secondary" : "internal"}
          aria-pressed={research.value}
          title={ui("Deep research")}
          disabled={disabled}
          onClick={() => research.onChange(!research.value)}
        >
          <Telescope className="size-4" aria-hidden="true" />
          <span className="hidden sm:inline">{ui("Deep research")}</span>
        </Button>
      )}
      {web.value !== "off" && (
        <span className="inline-flex items-center gap-1 rounded-full bg-surface-sunken py-0.5 pr-0.5 pl-2 text-sm text-content-secondary">
          <Globe className="size-3.5" aria-hidden="true" />
          {ui("Web")}
          <IconButton
            size="sm"
            prominence="internal"
            aria-label={ui("Tắt Web")}
            title={ui("Tắt Web")}
            disabled={disabled}
            onClick={() => web.onChange("off")}
          >
            <X />
          </IconButton>
        </span>
      )}
      {image.value !== "off" && (
        <span className="inline-flex items-center gap-1 rounded-full bg-surface-sunken py-0.5 pr-0.5 pl-2 text-sm text-content-secondary">
          <ImagePlus className="size-3.5" aria-hidden="true" />
          {ui("Tạo ảnh")}
          <IconButton
            size="sm"
            prominence="internal"
            aria-label={ui("Tắt tạo ảnh")}
            title={ui("Tắt tạo ảnh")}
            disabled={disabled}
            onClick={() => image.onChange("off")}
          >
            <X />
          </IconButton>
        </span>
      )}
      <ChatRecentFilesDialog
        open={allFiles}
        onOpenChange={setAllFiles}
        selected={files.selected}
        onSelect={files.select}
        disabled={disabled}
        uploadAction={null}
      />
    </div>
  );
}
