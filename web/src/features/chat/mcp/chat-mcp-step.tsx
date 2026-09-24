import { useState } from "react";
import { useParams } from "@tanstack/react-router";
import { Blocks } from "lucide-react";
import { ActivityStep } from "@/components/assistant-ui/elements/activity-group";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { McpConnection } from "@/lib/hey-api/types.gen";
import type { ToolProgress } from "@/features/chat/activity/chat-activity";
import { useMcpConnections } from "./chat-mcp-connections";
import { ConnectAction, McpApiKeyDialog } from "./chat-mcp-options";

/**
 * One MCP tool call in the answer timeline, named by tool and server. A call the server refused for authorization
 * offers Connect in place, so the person does not have to find the server again in the composer.
 */
export function ChatMcpToolStep({
  slug,
  tool,
  progress,
  state,
}: {
  slug: string;
  tool: string;
  progress: ToolProgress;
  state: "running" | "done" | "failed";
}) {
  const ui = useAppTranslation();
  const { sessionId } = useParams({ strict: false });
  const connections = useMcpConnections();
  const [apiKeyFor, setApiKeyFor] = useState<McpConnection | null>(null);
  const connection = connections.data?.find((candidate) => candidate.slug === slug);
  // A shared transcript can name a server the viewer may not use; the slug still identifies it.
  const server = connection?.name ?? slug;
  const title =
    state === "running"
      ? ui("Đang dùng {{tool}} trên {{server}}…", { tool, server })
      : ui("Đã dùng {{tool}} trên {{server}}", { tool, server });
  const failure = state === "failed" ? progress.failure : null;
  const reconnect =
    failure === "AUTHORIZATION_REQUIRED" &&
    connection?.authPerformer === "PER_USER" &&
    connection.authType !== "NONE";

  return (
    <ActivityStep icon={<Blocks />} status={state} title={title}>
      {failure ? (
        <div className="flex flex-wrap items-center gap-2 text-xs">
          <p>
            {failure === "AUTHORIZATION_REQUIRED"
              ? reconnect
                ? ui("{{server}} từ chối kết nối của bạn. Kết nối lại để tiếp tục.", { server })
                : ui("{{server}} từ chối kết nối dùng chung. Quản trị viên cần kết nối lại.", {
                    server,
                  })
              : failure === "TIMEOUT"
                ? ui("{{server}} không phản hồi kịp.", { server })
                : ui("Không liên lạc được với {{server}}.", { server })}
          </p>
          {reconnect ? (
            <ConnectAction connection={connection} sessionId={sessionId} onApiKey={setApiKeyFor} />
          ) : null}
        </div>
      ) : null}
      {apiKeyFor ? (
        <McpApiKeyDialog connection={apiKeyFor} onClose={() => setApiKeyFor(null)} />
      ) : null}
    </ActivityStep>
  );
}
