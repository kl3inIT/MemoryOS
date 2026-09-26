import { useState } from "react";
import { DropdownMenu } from "radix-ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSearch } from "@tanstack/react-router";
import { Blocks, MoreHorizontal, Pencil, Plus, RefreshCw, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { StatusBadge } from "@/components/ui/status-badge";
import { Switch } from "@/components/ui/switch";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  createMcpServer,
  deleteMcpServer,
  listMcpServers,
  listMcpServerTools,
  refreshMcpServerTools,
  setAllMcpServerToolsEnabled,
  setMcpServerToolEnabled,
  updateMcpServer,
} from "@/lib/hey-api/sdk.gen";
import type { McpServerInput, McpServerView } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { McpOAuthClients } from "./mcp-oauth-clients";
import { McpServerEditor } from "./mcp-server-editor";
import { McpServerMark } from "./mcp-server-mark";
import { serverStatus } from "./mcp-status";

const servers = { queryKey: ["mcp", "servers"] as const };

/** Administration of the Tenant's MCP servers and which of their tools the model may see. */
export function McpServersPage() {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const message = (failure: unknown) => problem(presentProblem(failure, "mutation").message);
  const client = useQueryClient();
  const outcome = useSearch({ strict: false }) as { mcp?: string; serverId?: string };
  const [editing, setEditing] = useState<McpServerView | "new" | null>(null);
  const [removing, setRemoving] = useState<McpServerView | null>(null);
  const [opened, setOpened] = useState<string | null>(outcome.serverId ?? null);
  const [error, setError] = useState<string>();

  const list = useQuery({
    queryKey: servers.queryKey,
    queryFn: async () => (await listMcpServers()).data,
  });
  const invalidate = () => client.invalidateQueries({ queryKey: servers.queryKey });

  const save = useMutation({
    mutationFn: async ({ input, revision }: { input: McpServerInput; revision?: number }) =>
      revision === undefined
        ? await createMcpServer({
            body: input,
          })
        : await updateMcpServer({
            path: { serverId: (editing as McpServerView).id },
            query: { revision },
            body: input,
          }),
    onSuccess: async () => {
      setEditing(null);
      setError(undefined);
      await invalidate();
    },
    onError: (failure) => setError(message(failure)),
  });

  const remove = useMutation({
    mutationFn: async (server: McpServerView) =>
      await deleteMcpServer({
        path: { serverId: server.id },
        query: { revision: server.revision },
      }),
    onSuccess: async () => {
      setRemoving(null);
      await invalidate();
    },
  });

  const refresh = useMutation({
    mutationFn: async (server: McpServerView) =>
      await refreshMcpServerTools({
        path: { serverId: server.id },
      }),
    onSuccess: async (_, server) => {
      setError(undefined);
      await Promise.all([
        invalidate(),
        client.invalidateQueries({ queryKey: ["mcp", "tools", server.id] }),
      ]);
    },
    onError: (failure) => setError(message(failure)),
  });

  return (
    <SettingsLayout wide>
      <PageHeader
        title={ui("Máy chủ MCP")}
        icon={<Blocks className="size-5" />}
        description={ui(
          "Máy chủ MCP cung cấp công cụ cho Chat. Chúng là bên thứ ba: chỉ thêm máy chủ mà tổ chức của bạn tin tưởng.",
        )}
        actions={
          <Button onClick={() => setEditing("new")}>
            <Plus />
            {ui("Thêm máy chủ")}
          </Button>
        }
      />

      {outcome.mcp ? <ConnectionOutcome outcome={outcome.mcp} /> : null}
      {error ? (
        <p role="alert" className="font-secondary-body text-status-danger-content">
          {error}
        </p>
      ) : null}
      {list.isError ? (
        <p role="alert" className="font-secondary-body text-status-danger-content">
          {problem(presentProblem(list.error, "initialLoad").message)}
        </p>
      ) : null}

      {list.data && list.data.length === 0 ? (
        <p className="font-main-ui-body text-content-muted">
          {ui("Chưa có máy chủ MCP nào. Thêm một máy chủ để Chat dùng công cụ của nó.")}
        </p>
      ) : null}

      <div className="flex flex-col gap-3">
        {(list.data ?? []).map((server) => {
          const status = serverStatus(server.status);
          return (
            <section
              key={server.id}
              className="rounded-xl border border-border-subtle bg-surface-raised p-4"
            >
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="flex min-w-0 gap-3">
                  <McpServerMark slug={server.slug} />
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <h3 className="font-main-ui-action text-content-primary">{server.name}</h3>
                      <StatusBadge tone={status.tone}>{ui(status.label)}</StatusBadge>
                    </div>
                    {server.description ? (
                      <p className="mt-0.5 font-secondary-body text-content-secondary">
                        {server.description}
                      </p>
                    ) : null}
                    <p className="mt-1 font-secondary-body break-all text-content-muted">
                      {server.url}
                    </p>
                    <p className="mt-1 font-secondary-body text-content-muted">
                      {ui("{{enabled}}/{{total}} công cụ đang bật", {
                        enabled: server.enabledToolCount,
                        total: server.toolCount,
                      })}
                      {" · "}
                      {ui(
                        server.authPerformer === "ADMIN"
                          ? "Một kết nối dùng chung"
                          : "Mỗi người tự kết nối",
                      )}
                      {" · "}
                      {ui(server.tenantWide ? "Cả tổ chức" : "Chọn nhóm")}
                    </p>
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <Button
                    prominence="secondary"
                    size="sm"
                    pending={refresh.isPending && refresh.variables?.id === server.id}
                    onClick={() => refresh.mutate(server)}
                  >
                    <RefreshCw />
                    {ui("Lấy công cụ")}
                  </Button>
                  <Button
                    prominence="secondary"
                    size="sm"
                    onClick={() => setOpened(opened === server.id ? null : server.id)}
                  >
                    {ui(opened === server.id ? "Ẩn công cụ" : "Xem công cụ")}
                  </Button>
                  {/* Rare actions share one menu, so the row stays on one line at phone width. */}
                  <DropdownMenu.Root>
                    <DropdownMenu.Trigger asChild>
                      <IconButton
                        size="sm"
                        prominence="internal"
                        aria-label={ui("Thao tác với {{name}}", { name: server.name })}
                      >
                        <MoreHorizontal />
                      </IconButton>
                    </DropdownMenu.Trigger>
                    <DropdownMenu.Portal>
                      <DropdownMenu.Content
                        align="end"
                        sideOffset={5}
                        className="z-50 min-w-44 rounded-xl border border-border-subtle bg-surface-overlay p-1.5 shadow-md"
                      >
                        <DropdownMenu.Item
                          className="flex cursor-default items-center gap-2 rounded-lg px-3 py-2 text-sm outline-none data-[highlighted]:bg-surface-sunken"
                          onSelect={() => setEditing(server)}
                        >
                          <Pencil className="size-4" /> {ui("Sửa")}
                        </DropdownMenu.Item>
                        <DropdownMenu.Separator className="my-1 border-t border-border-subtle" />
                        <DropdownMenu.Item
                          className="flex cursor-default items-center gap-2 rounded-lg px-3 py-2 text-sm text-status-danger-content outline-none data-[highlighted]:bg-surface-sunken"
                          onSelect={() => setRemoving(server)}
                        >
                          <Trash2 className="size-4" /> {ui("Xoá máy chủ")}
                        </DropdownMenu.Item>
                      </DropdownMenu.Content>
                    </DropdownMenu.Portal>
                  </DropdownMenu.Root>
                </div>
              </div>
              {opened === server.id ? (
                <>
                  {server.authType === "OAUTH" ? <McpOAuthClients server={server} /> : null}
                  <McpToolList serverId={server.id} />
                </>
              ) : null}
            </section>
          );
        })}
      </div>

      {editing ? (
        <McpServerEditor
          server={editing === "new" ? undefined : editing}
          saving={save.isPending}
          error={error}
          onSave={(input, revision) => save.mutate({ input, revision })}
          onClose={() => {
            setEditing(null);
            setError(undefined);
          }}
        />
      ) : null}

      {removing ? (
        <ConfirmDialog
          open
          title={ui("Xoá máy chủ MCP?")}
          description={ui(
            "Công cụ và mọi thông tin đăng nhập đã lưu của máy chủ này sẽ bị xoá. Không hoàn tác được.",
          )}
          confirmLabel={ui("Xoá")}
          pendingLabel={ui("Đang xoá")}
          confirmTone="danger"
          onConfirm={async () => {
            await remove.mutateAsync(removing);
          }}
          errorMessage={(failure) => presentProblem(failure, "mutation").message}
          onOpenChange={(open) => {
            if (!open) setRemoving(null);
          }}
        />
      ) : null}
    </SettingsLayout>
  );
}

/** The outcome code the OAuth callback appended; it never carries provider text. */
function ConnectionOutcome({ outcome }: { outcome: string }) {
  const ui = useAppTranslation();
  const copy =
    outcome === "connected"
      ? { tone: "success" as const, text: "Đã kết nối máy chủ MCP." }
      : outcome === "authorization-cancelled"
        ? { tone: "neutral" as const, text: "Bạn đã huỷ việc cấp quyền." }
        : outcome === "issuer-mismatch"
          ? { tone: "danger" as const, text: "Máy chủ cấp quyền không khớp cấu hình đã lưu." }
          : outcome === "configuration-changed"
            ? { tone: "warning" as const, text: "Cấu hình đã đổi khi đang cấp quyền. Hãy thử lại." }
            : { tone: "danger" as const, text: "Cấp quyền không thành công." };
  return <StatusBadge tone={copy.tone}>{ui(copy.text)}</StatusBadge>;
}

function McpToolList({ serverId }: { serverId: string }) {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const client = useQueryClient();
  const [error, setError] = useState<string>();
  const failed = (failure: unknown) =>
    setError(problem(presentProblem(failure, "mutation").message));
  const tools = useQuery({
    queryKey: ["mcp", "tools", serverId],
    queryFn: async () => (await listMcpServerTools({ path: { serverId } })).data,
  });
  const invalidate = () =>
    Promise.all([
      client.invalidateQueries({ queryKey: ["mcp", "tools", serverId] }),
      client.invalidateQueries({ queryKey: servers.queryKey }),
    ]);

  const toggle = useMutation({
    mutationFn: async ({
      toolId,
      revision,
      enabled,
    }: {
      toolId: string;
      revision: number;
      enabled: boolean;
    }) =>
      await setMcpServerToolEnabled({
        path: { serverId, toolId },
        query: { revision },
        body: { enabled },
      }),
    onSuccess: async () => {
      setError(undefined);
      await invalidate();
    },
    onError: failed,
  });
  const toggleAll = useMutation({
    mutationFn: async (enabled: boolean) =>
      await setAllMcpServerToolsEnabled({
        path: { serverId },
        body: { enabled },
      }),
    onSuccess: async () => {
      setError(undefined);
      await invalidate();
    },
    onError: failed,
  });

  if (tools.isError && !tools.data) {
    return (
      <p role="alert" className="mt-4 font-secondary-body text-status-danger-content">
        {problem(presentProblem(tools.error, "initialLoad").message)}
      </p>
    );
  }
  if (tools.data && tools.data.length === 0) {
    return (
      <p className="mt-4 font-secondary-body text-content-muted">
        {ui("Chưa lấy được công cụ nào. Bấm “Lấy công cụ” sau khi máy chủ đã kết nối.")}
      </p>
    );
  }
  return (
    <div className="mt-4 flex flex-col gap-3 border-t border-border-subtle pt-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h4 className="font-main-ui-action text-content-primary">{ui("Công cụ")}</h4>
        <div className="flex gap-2">
          <Button prominence="secondary" size="sm" onClick={() => toggleAll.mutate(true)}>
            {ui("Bật tất cả")}
          </Button>
          <Button prominence="secondary" size="sm" onClick={() => toggleAll.mutate(false)}>
            {ui("Tắt tất cả")}
          </Button>
        </div>
      </div>
      {tools.isError ? (
        <p role="alert" className="font-secondary-body text-status-danger-content">
          {problem(presentProblem(tools.error, "backgroundRead").message)}
        </p>
      ) : null}
      {error ? (
        <p role="alert" className="font-secondary-body text-status-danger-content">
          {error}
        </p>
      ) : null}
      <ul className="flex flex-col gap-2">
        {(tools.data ?? []).map((tool) => (
          <li key={tool.id} className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-main-ui-body text-content-primary">
                  {tool.title ?? tool.name}
                </span>
                {tool.readOnlyHint === false || tool.destructiveHint === true ? (
                  <StatusBadge tone="warning">{ui("Có thể thay đổi dữ liệu")}</StatusBadge>
                ) : null}
                {!tool.exposable ? (
                  <StatusBadge tone="neutral">{ui("Tên quá dài")}</StatusBadge>
                ) : null}
              </div>
              <p className="font-secondary-body text-content-muted">{tool.description}</p>
            </div>
            <Switch
              checked={tool.enabled}
              disabled={!tool.exposable || toggle.isPending}
              aria-label={tool.name}
              onCheckedChange={(enabled) =>
                toggle.mutate({ toolId: tool.id, revision: tool.revision, enabled })
              }
            />
          </li>
        ))}
      </ul>
    </div>
  );
}
