import { useQuery } from "@tanstack/react-query";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { Blocks, Plus } from "lucide-react";
import { EmptyState } from "@/components/composites/empty-state";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { listMcpServersOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { McpConnection, McpServerView } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { appRowLinkClass, McpAppGrid, McpAppRow } from "./mcp-app-row";
import { useMcpConnections } from "./mcp-connections";
import { useOAuthSetup } from "./mcp-oauth-setup";
import { ConnectionOutcome, NextStepAction, ServerMenu } from "./mcp-server-actions";
import { type ServerAdmin, useServerAdmin } from "./mcp-server-admin";
import { McpServerMark } from "./mcp-server-mark";
import { serverLine, serverNextStep } from "./mcp-status";

/**
 * The Tenant's MCP servers, listed as ChatGPT lists its plugins: a mark, a name, one line and one action. Each
 * server's settings, OAuth applications and tools are on its own page.
 */
export function McpServersPage() {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const outcome = useSearch({ from: "/_authenticated/admin/mcp/" });
  const admin = useServerAdmin();
  const list = useQuery(listMcpServersOptions());
  // The administrator's own connections decide whether a per-person server's tools can be listed yet.
  const connections = useMcpConnections();

  return (
    <SettingsLayout wide>
      <PageHeader
        title={ui("Máy chủ MCP")}
        icon={<Blocks />}
        description={ui(
          "Máy chủ MCP cung cấp công cụ cho Chat. Chúng là bên thứ ba: chỉ thêm máy chủ mà tổ chức của bạn tin tưởng.",
        )}
        actions={
          <Button onClick={admin.add}>
            <Plus data-icon="inline-start" />
            {ui("Thêm máy chủ")}
          </Button>
        }
      />

      {outcome.mcp ? <ConnectionOutcome outcome={outcome.mcp} /> : null}
      {admin.refreshError ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{admin.refreshError}</AlertDescription>
        </Alert>
      ) : null}
      {list.isError ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>
            {problem(presentProblem(list.error, "initialLoad").message)}
          </AlertDescription>
        </Alert>
      ) : null}

      {list.data && list.data.length === 0 ? (
        <EmptyState
          icon={<Blocks />}
          title={ui("Chưa có máy chủ MCP nào. Thêm một máy chủ để Chat dùng công cụ của nó.")}
        />
      ) : null}

      <McpAppGrid>
        {(list.data ?? []).map((server) => (
          <McpServerRow
            key={server.id}
            server={server}
            own={connections.data?.find((connection) => connection.id === server.id)}
            admin={admin}
          />
        ))}
      </McpAppGrid>

      {admin.dialogs}
    </SettingsLayout>
  );
}

function McpServerRow({
  server,
  own,
  admin,
}: {
  server: McpServerView;
  own?: McpConnection;
  admin: ServerAdmin;
}) {
  const ui = useAppTranslation();
  const navigate = useNavigate();
  const setup = useOAuthSetup(server);
  const step = serverNextStep(server, setup.clients.data?.length, own);
  const { line, tone } = serverLine(server, step);
  return (
    <McpAppRow
      mark={<McpServerMark slug={server.slug} url={server.url} name={server.name} />}
      title={server.name}
      line={ui(line, { enabled: server.enabledToolCount, total: server.toolCount })}
      tone={tone}
      link={(body) => (
        <Link
          to="/admin/mcp/$serverId"
          params={{ serverId: server.id }}
          className={appRowLinkClass}
        >
          {body}
        </Link>
      )}
      action={
        step === null ? (
          <ServerMenu server={server} step={step} admin={admin} />
        ) : (
          <NextStepAction
            step={step}
            server={server}
            own={own}
            admin={admin}
            setup={setup}
            onOpenPage={() =>
              void navigate({ to: "/admin/mcp/$serverId", params: { serverId: server.id } })
            }
          />
        )
      }
    />
  );
}
