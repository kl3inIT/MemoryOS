import { useAppTranslation } from "@/i18n/use-app-translation";
import { ArrowUp } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import type { AgentTool } from "@/features/chat/chat-personas-api";
import { AgentAvatar } from "./agent-avatar";
import { toolIcons, useToolNames } from "./agent-tools";
import type { AgentValues } from "./agent-form";

/** What colleagues will see when they open the agent; sending needs a saved agent. */
export function AgentPreview({ values, agentId }: { values: AgentValues; agentId: string }) {
  const ui = useAppTranslation();
  const toolNames = useToolNames();
  const starters = values.starterPrompts
    .map((prompt) => prompt.trim())
    .filter(Boolean)
    .slice(0, 4);
  const name = values.name.trim() || ui("Trợ lý chưa đặt tên");
  return (
    <div className="sticky top-22 flex flex-col gap-2">
      <p className="px-1 font-secondary-action text-content-muted">{ui("Xem trước")}</p>
      <Card className="min-h-112">
        <CardContent className="flex flex-1 flex-col items-center justify-center">
          <div className="flex flex-col items-center gap-3 text-center">
            <AgentAvatar
              agent={{
                id: agentId,
                name,
                iconName: values.iconName,
                hasAvatar: values.keepAvatar && !values.avatarFileId,
              }}
              size="lg"
            />
            <div>
              <p className="font-heading-h3 text-content-primary">{name}</p>
              <p className="mt-1 line-clamp-3 font-secondary-body text-content-muted">
                {values.description || ui("Mô tả sẽ hiện ở đây.")}
              </p>
            </div>
            <div className="flex flex-wrap justify-center gap-1.5">
              {values.tools.map((tool) => {
                const Icon = toolIcons[tool as AgentTool];
                return Icon ? (
                  <span
                    key={tool}
                    className="inline-flex h-6 items-center gap-1 rounded-full bg-surface-sunken px-2 font-secondary-body text-content-secondary"
                  >
                    <Icon aria-hidden="true" className="size-3" />
                    {toolNames[tool as AgentTool].label}
                  </span>
                ) : null;
              })}
            </div>
          </div>
        </CardContent>
        <CardContent>
          <div className="flex flex-col gap-3">
            {starters.length > 0 && (
              <ul className="flex flex-col gap-1.5">
                {starters.map((prompt, index) => (
                  <li
                    key={index}
                    className="truncate rounded-xl border border-border-subtle bg-surface-raised px-3 py-2 text-left font-secondary-body text-content-secondary"
                  >
                    {prompt}
                  </li>
                ))}
              </ul>
            )}
            <div className="flex items-center gap-2 rounded-xl border border-border-default bg-surface-raised py-2 pr-2 pl-3">
              <span className="flex-1 truncate font-main-ui-body text-content-faint">
                {ui("Hỏi {{v1}}…", { v1: name })}
              </span>
              <span
                aria-hidden="true"
                className="grid size-7 place-items-center rounded-lg bg-surface-strong text-surface-raised"
              >
                <ArrowUp className="size-4" />
              </span>
            </div>
          </div>
        </CardContent>
      </Card>
      <p className="px-1 font-secondary-body text-content-muted">
        {ui("Lưu trợ lý rồi bấm Bắt đầu chat để thử.")}
      </p>
    </div>
  );
}
