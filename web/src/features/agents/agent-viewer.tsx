import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState, type ReactNode } from "react";
import {
  ArrowUp,
  ChevronDown,
  FileSearch,
  Globe,
  ImagePlus,
  Library,
  Pencil,
  Plug,
  SquareTerminal,
  X,
} from "lucide-react";
import { PersonAvatar } from "@/components/composites/person-avatar";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from "@/components/ui/dialog";
import { IconButton } from "@/components/ui/icon-button";
import { can } from "@/lib/resource-permissions";
import { cn } from "@/lib/utils";
import { personLabel } from "@/features/identity/principals";
import type { Persona } from "@/features/chat/chat-personas-api";
import { AgentAvatar } from "./agent-avatar";

const toolIcons: Record<string, typeof Globe> = {
  search: FileSearch,
  web_search: Globe,
  image_generation: ImagePlus,
  code_interpreter: SquareTerminal,
};

/**
 * An agent's page as colleagues see it: who made it, what it answers from, starter prompts that send immediately
 * and a composer, with the full Onyx configuration snapshot one click away.
 */
export function AgentViewer({
  agent,
  pending,
  onClose,
  onStart,
  onEdit,
}: {
  agent: Persona;
  pending: boolean;
  onClose: () => void;
  onStart: (question?: string) => void;
  onEdit: () => void;
}) {
  const ui = useAppTranslation();
  const [question, setQuestion] = useState("");
  const [details, setDetails] = useState(false);
  const toolNames: Record<string, string> = {
    search: ui("Tìm tài liệu nội bộ"),
    web_search: ui("Tìm kiếm Web"),
    image_generation: ui("Tạo ảnh"),
    code_interpreter: ui("Chạy Python"),
  };
  const owner = agent.builtin
    ? ui("MemoryOS")
    : (agent.owner.group?.name ?? (personLabel(agent.owner.actor) || ui("Chưa có chủ sở hữu")));
  const send = () => {
    if (question.trim() && !pending) onStart(question.trim());
  };
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent showCloseButton={false} className="flex flex-col overflow-hidden sm:max-w-2xl">
        <div className="-mx-3 -mt-3 -mb-4 flex items-center justify-end gap-1">
          {can(agent, "edit") && (
            <Button size="sm" prominence="tertiary" onClick={onEdit}>
              <Pencil data-icon="inline-start" aria-hidden="true" />
              {ui("Sửa")}
            </Button>
          )}
          <DialogClose asChild>
            <IconButton size="sm" prominence="tertiary" aria-label={ui("Đóng")}>
              <X />
            </IconButton>
          </DialogClose>
        </div>

        <div className="-mx-6 min-h-0 flex-1 overflow-y-auto px-6">
          <div className="flex flex-col items-center gap-3 pt-2 text-center">
            <AgentAvatar agent={agent} size="xl" />
            <div>
              <DialogTitle>{agent.name}</DialogTitle>
              <p className="mt-1 flex items-center justify-center gap-1.5 font-secondary-body text-content-muted">
                <PersonAvatar
                  name={owner}
                  seed={agent.owner.actor?.actorId ?? agent.owner.group?.id}
                  kind={agent.owner.group ? "group" : "person"}
                  size="xs"
                />
                {ui("Tạo bởi {{v1}}", { v1: owner })}
              </p>
            </div>
            <DialogDescription className="max-w-lg whitespace-pre-wrap">
              {agent.description || ui("Chưa có mô tả.")}
            </DialogDescription>
            {agent.labels.length > 0 && (
              <div className="flex flex-wrap justify-center gap-1.5">
                {agent.labels.map((label) => (
                  <span
                    key={label.id}
                    className="inline-flex h-6 items-center rounded-full border border-border-subtle px-2.5 font-secondary-action text-content-secondary"
                  >
                    {label.name}
                  </span>
                ))}
              </div>
            )}
          </div>

          {agent.starterPrompts.length > 0 && (
            <div className="mt-6 grid gap-2 sm:grid-cols-2">
              {agent.starterPrompts.map((prompt, index) => (
                <button
                  key={index}
                  type="button"
                  disabled={pending}
                  onClick={() => onStart(prompt)}
                  className="rounded-xl border border-border-subtle bg-surface-raised px-3.5 py-3 text-left font-main-ui-body text-content-primary outline-none transition-colors hover:border-border-default hover:bg-surface-base focus-visible:ring-3 focus-visible:ring-focus-ring/40 disabled:opacity-60"
                >
                  {prompt}
                </button>
              ))}
            </div>
          )}

          <div className="mt-6 rounded-xl border border-border-subtle">
            <Collapsible open={details} onOpenChange={setDetails}>
              <CollapsibleTrigger asChild>
                <button
                  type="button"
                  className="flex w-full items-center gap-2 px-4 py-3 text-left font-main-ui-action text-content-primary outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/40"
                >
                  <span className="flex-1">{ui("Cấu hình trợ lý")}</span>
                  <span className="font-secondary-body text-content-muted">
                    {agent.sources.length === 0
                      ? ui("Mọi nguồn bạn được đọc")
                      : ui("{{v1}} nguồn", { v1: agent.sources.length })}
                    {" · "}
                    {ui("{{v1}} công cụ", { v1: agent.tools.length + agent.mcpServers.length })}
                  </span>
                  <ChevronDown
                    aria-hidden="true"
                    className={cn(
                      "size-4 text-content-muted transition-transform",
                      details && "rotate-180",
                    )}
                  />
                </button>
              </CollapsibleTrigger>
              <CollapsibleContent>
                <div className="flex flex-col gap-4 border-t border-border-subtle px-4 py-4">
                  <Detail label={ui("Nguồn tài liệu")}>
                    {agent.sources.length === 0 ? (
                      <p>{ui("Tất cả nguồn bạn được phép đọc")}</p>
                    ) : (
                      <ul className="flex flex-col gap-1.5">
                        {agent.sources.map((source) => (
                          <li key={source.id} className="flex items-center gap-2">
                            <Library aria-hidden="true" className="size-4 shrink-0" />
                            <span className="truncate">
                              {source.name || ui("Nguồn không còn khả dụng")}
                            </span>
                          </li>
                        ))}
                      </ul>
                    )}
                  </Detail>
                  <Detail label={ui("Công cụ")}>
                    {agent.tools.length + agent.mcpServers.length === 0 ? (
                      <p>{ui("Không dùng công cụ")}</p>
                    ) : (
                      <div className="flex flex-wrap gap-1.5">
                        {agent.tools.map((tool) => {
                          const Icon = toolIcons[tool] ?? Plug;
                          return (
                            <span
                              key={tool}
                              className="inline-flex h-7 items-center gap-1.5 rounded-full bg-surface-sunken px-2.5"
                            >
                              <Icon aria-hidden="true" className="size-3.5" />
                              {toolNames[tool] ?? tool}
                            </span>
                          );
                        })}
                        {agent.mcpServers.map((server) => (
                          <span
                            key={server.id}
                            className="inline-flex h-7 items-center gap-1.5 rounded-full bg-surface-sunken px-2.5"
                          >
                            <Plug aria-hidden="true" className="size-3.5" />
                            {server.name}
                          </span>
                        ))}
                      </div>
                    )}
                  </Detail>
                  {agent.instructions && (
                    <Detail label={ui("Hướng dẫn")}>
                      <p className="max-h-48 overflow-y-auto rounded-lg bg-surface-sunken p-3 whitespace-pre-wrap">
                        {agent.instructions}
                      </p>
                    </Detail>
                  )}
                  {agent.taskPrompt && (
                    <Detail label={ui("Nhắc việc mỗi lượt")}>
                      <p className="rounded-lg bg-surface-sunken p-3 whitespace-pre-wrap">
                        {agent.taskPrompt}
                      </p>
                    </Detail>
                  )}
                </div>
              </CollapsibleContent>
            </Collapsible>
          </div>
        </div>

        <form
          className="-mx-6 -mb-6 border-t border-border-subtle bg-surface-base px-4 py-3"
          onSubmit={(event) => {
            event.preventDefault();
            send();
          }}
        >
          <div className="flex items-end gap-2 rounded-xl border border-border-default bg-surface-raised py-1.5 pr-1.5 pl-3 focus-within:border-border-strong">
            <textarea
              aria-label={ui("Câu hỏi cho {{v1}}", { v1: agent.name })}
              rows={1}
              maxLength={32000}
              value={question}
              placeholder={ui("Hỏi {{v1}}…", { v1: agent.name })}
              onChange={(event) => setQuestion(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
                  event.preventDefault();
                  send();
                }
              }}
              className="block max-h-32 min-h-8 min-w-0 flex-1 resize-none bg-transparent py-1 font-main-ui-body outline-none placeholder:text-content-muted"
            />
            <IconButton
              type="submit"
              prominence="primary"
              aria-label={ui("Gửi câu hỏi")}
              disabled={!question.trim()}
              pending={pending}
            >
              <ArrowUp />
            </IconButton>
          </div>
          <p className="mt-2 text-center font-secondary-body text-content-muted">
            {ui("Trợ lý chỉ trả lời từ những tài liệu bạn được phép đọc.")}
          </p>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-1.5">
      <h3 className="font-secondary-action text-content-muted">{label}</h3>
      <div className="font-main-ui-body text-content-secondary">{children}</div>
    </section>
  );
}
