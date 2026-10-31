import { useQuery } from "@tanstack/react-query";
import { useNavigate, useParams, useSearch } from "@tanstack/react-router";
import { Blocks, ChevronDown } from "lucide-react";
import { type ReactNode, useState } from "react";
import { BrandLoader } from "@/components/brand-loader";
import { DetailHeader } from "@/components/composites/detail-header";
import { EmptyState } from "@/components/composites/empty-state";
import { SectionHeader } from "@/components/composites/section-header";
import { SettingsLayout } from "@/components/composites/settings-layout";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { listMcpServersOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { McpConnection, McpServerView } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { useMcpConnections } from "./mcp-connections";
import { McpOAuthClients } from "./mcp-oauth-clients";
import { useOAuthSetup } from "./mcp-oauth-setup";
import { ConnectionOutcome, NextStepAction, ServerMenu } from "./mcp-server-actions";
import { type ServerAdmin, useServerAdmin } from "./mcp-server-admin";
import { McpServerMark } from "./mcp-server-mark";
import { serverBadge, serverLine, serverNextStep } from "./mcp-status";
import { McpToolList } from "./mcp-tool-list";

/**
 * One MCP server's page, as ChatGPT gives each app its own: the mark, name and next step at the top, then what it
 * is and who uses it, its tools, and its OAuth applications under Advanced. The OAuth callback lands here with its
 * outcome.
 */
export function McpServerDetailPage() {
  const ui = useAppTranslation();
  const navigate = useNavigate();
  const { serverId } = useParams({ from: "/_authenticated/admin/mcp/$serverId" });
  const outcome = useSearch({ from: "/_authenticated/admin/mcp/$serverId" });
  const admin = useServerAdmin({ onRemoved: () => void navigate({ to: "/admin/mcp" }) });
  const list = useQuery(listMcpServersOptions());
  const connections = useMcpConnections();
  const server = list.data?.find((candidate) => candidate.id === serverId);

  return (
    <SettingsLayout>
      {list.isPending ? (
        <div role="status" className="flex justify-center px-6 py-20">
          <BrandLoader label={ui("Đang tải máy chủ MCP")} />
        </div>
      ) : !server ? (
        <EmptyState
          role="alert"
          icon={<Blocks />}
          title={ui("Không tìm thấy máy chủ MCP này.")}
          action={
            <Button size="sm" prominence="secondary" onClick={() => void list.refetch()}>
              {ui("Thử lại")}
            </Button>
          }
        />
      ) : (
        <ServerDetail
          key={server.id}
          server={server}
          own={connections.data?.find((connection) => connection.id === server.id)}
          admin={admin}
          outcome={outcome.mcp}
        />
      )}
      {admin.dialogs}
    </SettingsLayout>
  );
}

function ServerDetail({
  server,
  own,
  admin,
  outcome,
}: {
  server: McpServerView;
  own?: McpConnection;
  admin: ServerAdmin;
  outcome?: string;
}) {
  const ui = useAppTranslation();
  const setup = useOAuthSetup(server);
  const step = serverNextStep(server, setup.clients.data?.length, own);
  const { line, tone } = serverLine(server, step);
  const counts = { enabled: server.enabledToolCount, total: server.toolCount };
  const facts: [string, string][] = [
    [ui("Địa chỉ máy chủ"), server.url],
    [ui("Trạng thái"), ui(serverBadge(server, step).label)],
    ...(server.authType === "NONE"
      ? []
      : [
          [
            ui("Tài khoản"),
            ui(
              server.authPerformer === "ADMIN"
                ? "Dùng chung một tài khoản"
                : "Mỗi người dùng tài khoản của mình",
            ),
          ] as [string, string],
        ]),
    [
      ui("Ai dùng được"),
      ui(
        server.personaIds.length > 0
          ? "Chỉ qua trợ lý được chọn"
          : server.tenantWide
            ? "Cả tổ chức"
            : "Chọn nhóm",
      ),
    ],
    // MEM-299: only a shared connection or none can serve a public bot.
    ...(server.authType === "NONE" || server.authPerformer === "ADMIN"
      ? ([[ui("Bot công khai"), ui(server.publicBots ? "Được dùng" : "Không dùng")]] as [
          string,
          string,
        ][])
      : []),
  ];

  return (
    <>
      <DetailHeader
        parent={{ label: ui("Ứng dụng MCP"), to: "/admin/mcp" }}
        icon={<McpServerMark slug={server.slug} url={server.url} name={server.name} />}
        iconSize="lg"
        title={server.name}
        description={
          <span
            className={cn(
              tone === "warning" && "text-status-warning-content",
              tone === "danger" && "text-status-danger-content",
            )}
          >
            {server.description && tone === "muted" ? server.description : ui(line, counts)}
          </span>
        }
        actions={
          <div className="flex items-center gap-2">
            <NextStepAction
              step={step}
              server={server}
              own={own}
              admin={admin}
              setup={setup}
              primary
            />
            <ServerMenu server={server} step={step} admin={admin} />
          </div>
        }
      />

      {outcome ? <ConnectionOutcome outcome={outcome} /> : null}
      {admin.refreshError ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{admin.refreshError}</AlertDescription>
        </Alert>
      ) : null}

      <section className="flex flex-col gap-3">
        <SectionHeader level="group" title={ui("Thông tin")} />
        <dl className="divide-y divide-border-subtle rounded-xl border border-border-subtle font-secondary-body">
          {facts.map(([label, value]) => (
            <div key={label} className="grid grid-cols-3 gap-4 px-4 py-3">
              <dt className="text-content-muted">{label}</dt>
              <dd className="col-span-2 break-all text-content-primary">{value}</dd>
            </div>
          ))}
        </dl>
      </section>

      <McpToolList serverId={server.id} />
      {server.authType === "OAUTH" ? (
        <Advanced open={setup.clients.data?.length === 0}>
          <McpOAuthClients server={server} setup={setup} />
        </Advanced>
      ) : null}
    </>
  );
}

/** The server's OAuth applications, which few administrators need once it connects; open while there is none. */
function Advanced({ open, children }: { open: boolean; children: ReactNode }) {
  const ui = useAppTranslation();
  const [expanded, setExpanded] = useState(false);
  const shown = expanded || open;
  return (
    <Collapsible open={shown} onOpenChange={setExpanded}>
      <CollapsibleTrigger asChild>
        <Button prominence="internal" size="sm" className="-ml-2 self-start">
          {ui("Nâng cao")}
          <ChevronDown data-icon="inline-end" className={shown ? "rotate-180" : ""} />
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent className="mt-3">{children}</CollapsibleContent>
    </Collapsible>
  );
}
