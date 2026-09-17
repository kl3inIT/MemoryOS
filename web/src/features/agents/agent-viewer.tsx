import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ReactNode } from "react";
import { Dialog } from "radix-ui";
import { X } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { personLabel, type Persona } from "@/features/chat/chat-workspace-api";
import { AgentAvatar } from "./agent-avatar";

/** Read-only Onyx agent snapshot for users who can use but not edit an agent. */
export function AgentViewer({
  agent,
  onClose,
  onStart,
}: {
  agent: Persona;
  onClose: () => void;
  onStart: () => void;
}) {
  const ui = useAppTranslation();
  const toolNames: Record<string, string> = {
    search: ui("Tìm tài liệu nội bộ"),
    web_search: ui("Tìm kiếm Web"),
    image_generation: ui("Tạo ảnh"),
  };
  const owner = agent.builtin
    ? ui("MemoryOS")
    : (agent.owner.group?.name ?? (personLabel(agent.owner.actor) || ui("Chưa có chủ sở hữu")));
  return (
    <Dialog.Root open onOpenChange={(open) => !open && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-content-primary/20 backdrop-blur-[2px]" />
        <Dialog.Content className="fixed top-1/2 left-1/2 z-50 max-h-[calc(100dvh-2rem)] w-[min(40rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 overflow-y-auto rounded-2xl border border-border-default bg-surface-overlay p-6 shadow-md outline-none">
          <div className="flex items-start gap-4">
            <AgentAvatar agent={agent} size="lg" />
            <div className="min-w-0 flex-1">
              <Dialog.Title className="text-xl font-semibold">{agent.name}</Dialog.Title>
              <p className="mt-1 text-sm text-content-muted">
                {ui("Chủ sở hữu: {{v1}}", { v1: owner })}
              </p>
            </div>
            <Dialog.Close asChild>
              <IconButton size="sm" prominence="internal" aria-label={ui("Đóng")}>
                <X />
              </IconButton>
            </Dialog.Close>
          </div>
          <Dialog.Description className="mt-4 whitespace-pre-wrap text-sm text-content-secondary">
            {agent.description || ui("Chưa có mô tả.")}
          </Dialog.Description>
          {agent.labels.length > 0 && (
            <div className="mt-3 flex flex-wrap gap-1.5">
              {agent.labels.map((label) => (
                <Badge key={label.id} variant="secondary">
                  {label.name}
                </Badge>
              ))}
            </div>
          )}
          <div className="mt-5 space-y-4 text-sm">
            {agent.starterPrompts.length > 0 && (
              <Field label={ui("Câu hỏi gợi ý")}>
                <ul className="list-disc space-y-1 pl-5">
                  {agent.starterPrompts.map((prompt, index) => (
                    <li key={index}>{prompt}</li>
                  ))}
                </ul>
              </Field>
            )}
            <Field label={ui("Nguồn tài liệu")}>
              {agent.sources.length === 0
                ? ui("Tất cả nguồn bạn được phép đọc")
                : agent.sources
                    .map((source) => source.name || ui("Nguồn không còn khả dụng"))
                    .join(", ")}
            </Field>
            <Field label={ui("Công cụ")}>
              {agent.tools.length === 0
                ? ui("Không dùng công cụ")
                : agent.tools.map((tool) => toolNames[tool] ?? tool).join(", ")}
              {agent.mcpServers.length > 0 && (
                <span>
                  {" · "}
                  {ui("MCP: {{v1}}", {
                    v1: agent.mcpServers.map((server) => server.name).join(", "),
                  })}
                </span>
              )}
            </Field>
            {agent.instructions && (
              <Field label={ui("Hướng dẫn")}>
                <p className="max-h-48 overflow-y-auto whitespace-pre-wrap rounded-lg bg-surface-sunken p-3">
                  {agent.instructions}
                </p>
              </Field>
            )}
            {agent.taskPrompt && (
              <Field label={ui("Nhắc việc mỗi lượt")}>
                <p className="whitespace-pre-wrap rounded-lg bg-surface-sunken p-3">
                  {agent.taskPrompt}
                </p>
              </Field>
            )}
            <p className="text-xs text-content-muted">
              {ui("Trợ lý chỉ trả lời từ những tài liệu bạn được phép đọc.")}
            </p>
          </div>
          <div className="mt-6 flex justify-end">
            <Button onClick={onStart}>{ui("Bắt đầu hội thoại")}</Button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <section>
      <h3 className="mb-1 font-medium">{label}</h3>
      <div className="text-content-secondary">{children}</div>
    </section>
  );
}
