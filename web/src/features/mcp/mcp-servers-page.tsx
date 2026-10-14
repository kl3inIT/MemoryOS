import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSearch } from "@tanstack/react-router";
import { Blocks, MoreHorizontal, Pencil, Plus, RefreshCw, Trash2 } from "lucide-react";
import { EmptyState } from "@/components/composites/empty-state";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { IconButton } from "@/components/ui/icon-button";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  createMcpServerMutation,
  deleteMcpServerMutation,
  listMcpServersOptions,
  refreshMcpServerToolsMutation,
  updateMcpServerMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { McpConnection, McpServerInput, McpServerView } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { McpAppGrid, McpAppRow } from "./mcp-app-row";
import { ConnectAction, McpApiKeyDialog } from "./mcp-connect-actions";
import { invalidateMcpConnections, useMcpConnections } from "./mcp-connections";
import { McpOAuthClients } from "./mcp-oauth-clients";
import { type OAuthSetup, useOAuthSetup } from "./mcp-oauth-setup";
import { McpServerEditor } from "./mcp-server-editor";
import { McpServerMark } from "./mcp-server-mark";
import { invalidateMcpServers } from "./mcp-servers";
import { serverBadge, serverNextStep } from "./mcp-status";
import { McpToolList } from "./mcp-tool-list";

type Step = ReturnType<typeof serverNextStep>;

/** Administration of the Tenant's MCP servers and which of their tools the model may see. */
export function McpServersPage() {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const message = (failure: unknown) => problem(presentProblem(failure, "mutation").message);
  const cache = useQueryClient();
  const outcome = useSearch({ from: "/_authenticated/admin/mcp" });
  const [editing, setEditing] = useState<McpServerView | "new" | null>(null);
  const [removing, setRemoving] = useState<McpServerView | null>(null);
  // The OAuth callback returns to the server it concerns, so its details open again.
  const [opened, setOpened] = useState<string | null>(outcome.serverId ?? null);
  const [apiKeyFor, setApiKeyFor] = useState<McpConnection | null>(null);

  const list = useQuery(listMcpServersOptions());
  // The administrator's own connections decide whether a per-person server's tools can be listed yet.
  const connections = useMcpConnections();
  const reread = (serverId?: string) =>
    Promise.all([invalidateMcpServers(cache, serverId), invalidateMcpConnections(cache)]);
  const saved = async () => {
    setEditing(null);
    await reread();
  };
  // A server input can carry a shared API key, so a finished save is not kept in the mutation cache.
  const create = useMutation({ ...createMcpServerMutation(), gcTime: 0, onSuccess: saved });
  const update = useMutation({ ...updateMcpServerMutation(), gcTime: 0, onSuccess: saved });
  const remove = useMutation({
    ...deleteMcpServerMutation(),
    onSuccess: async () => {
      setRemoving(null);
      setOpened(null);
      await reread();
    },
  });
  const refresh = useMutation({
    ...refreshMcpServerToolsMutation(),
    onSuccess: (_, { path }) => reread(path.serverId),
  });
  const saveError = create.error ?? update.error;

  const closeEditor = () => {
    setEditing(null);
    create.reset();
    update.reset();
  };
  const save = (input: McpServerInput, revision?: number) => {
    if (editing === "new" || revision === undefined) create.mutate({ body: input });
    else if (editing)
      update.mutate({ path: { serverId: editing.id }, query: { revision }, body: input });
  };

  return (
    <SettingsLayout wide>
      <PageHeader
        title={ui("Máy chủ MCP")}
        icon={<Blocks />}
        description={ui(
          "Máy chủ MCP cung cấp công cụ cho Chat. Chúng là bên thứ ba: chỉ thêm máy chủ mà tổ chức của bạn tin tưởng.",
        )}
        actions={
          <Button onClick={() => setEditing("new")}>
            <Plus data-icon="inline-start" />
            {ui("Thêm máy chủ")}
          </Button>
        }
      />

      {outcome.mcp ? <ConnectionOutcome outcome={outcome.mcp} /> : null}
      {refresh.error ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{message(refresh.error)}</AlertDescription>
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
            opened={opened === server.id}
            refreshing={refresh.isPending && refresh.variables?.path.serverId === server.id}
            onRefresh={() => refresh.mutate({ path: { serverId: server.id } })}
            onOpenChange={(open) => setOpened(open ? server.id : null)}
            onEdit={() => setEditing(server)}
            onRemove={() => setRemoving(server)}
            onApiKey={setApiKeyFor}
          />
        ))}
      </McpAppGrid>

      {editing ? (
        <McpServerEditor
          server={editing === "new" ? undefined : editing}
          saving={create.isPending || update.isPending}
          error={saveError ? message(saveError) : undefined}
          onSave={save}
          onClose={closeEditor}
        />
      ) : null}

      {apiKeyFor ? (
        <McpApiKeyDialog connection={apiKeyFor} onClose={() => setApiKeyFor(null)} />
      ) : null}

      {removing ? (
        <ConfirmDialog
          open
          title={ui("Xóa máy chủ MCP?")}
          description={ui(
            "Công cụ và mọi thông tin đăng nhập đã lưu của máy chủ này sẽ bị xóa. Không hoàn tác được.",
          )}
          confirmLabel={ui("Xóa")}
          pendingLabel={ui("Đang xóa")}
          confirmTone="danger"
          onConfirm={async () => {
            await remove.mutateAsync({
              path: { serverId: removing.id },
              query: { revision: removing.revision },
            });
          }}
          errorMessage={(failure) => presentProblem(failure, "mutation").message}
          onOpenChange={(isOpen) => {
            if (!isOpen) setRemoving(null);
          }}
        />
      ) : null}
    </SettingsLayout>
  );
}

type RowProps = {
  server: McpServerView;
  /** The administrator's own connection, absent when they may not use the server. */
  own?: McpConnection;
  opened: boolean;
  refreshing: boolean;
  onRefresh: () => void;
  onOpenChange: (open: boolean) => void;
  onEdit: () => void;
  onRemove: () => void;
  onApiKey: (connection: McpConnection) => void;
};

/** One server: its mark, name and one line, the next step as its only action, and its details in a sheet. */
function McpServerRow(props: RowProps) {
  const { server, own, opened, onOpenChange } = props;
  const ui = useAppTranslation();
  const setup = useOAuthSetup(server);
  const step = serverNextStep(server, setup.clients.data?.length, own);
  const badge = serverBadge(server, step);
  const missing = step !== null && step !== "FETCH_TOOLS";
  const failed = server.status === "DISCONNECTED";
  const tools = ui("{{enabled}}/{{total}} công cụ đang bật", {
    enabled: server.enabledToolCount,
    total: server.toolCount,
  });
  const open = () => onOpenChange(true);

  return (
    <>
      <McpAppRow
        mark={<McpServerMark slug={server.slug} url={server.url} name={server.name} />}
        title={server.name}
        line={
          missing || failed || server.toolCount === 0
            ? ui(badge.label)
            : (server.description ?? tools)
        }
        tone={missing ? "warning" : failed ? "danger" : "muted"}
        onOpen={open}
        action={
          step === null ? (
            <ServerMenu {...props} step={step} />
          ) : (
            <NextStepAction {...props} step={step} setup={setup} onOpen={open} />
          )
        }
      />
      <Sheet open={opened} onOpenChange={onOpenChange}>
        <SheetContent className="w-full overflow-y-auto sm:max-w-xl">
          <SheetHeader>
            <div className="flex items-center gap-3 pr-8">
              <McpServerMark slug={server.slug} url={server.url} name={server.name} />
              <div className="min-w-0">
                <SheetTitle>{server.name}</SheetTitle>
                <SheetDescription className="break-all">{server.url}</SheetDescription>
              </div>
            </div>
          </SheetHeader>
          <div className="flex flex-col gap-6 px-4 pb-6">
            <div className="flex flex-wrap items-center gap-2">
              {step === null ? null : (
                <NextStepAction {...props} step={step} setup={setup} onOpen={open} primary />
              )}
              <Button size="sm" prominence="secondary" onClick={props.onEdit}>
                <Pencil data-icon="inline-start" />
                {ui("Sửa")}
              </Button>
              {step !== null ? null : (
                <Button
                  size="sm"
                  prominence="secondary"
                  pending={props.refreshing}
                  onClick={props.onRefresh}
                >
                  <RefreshCw data-icon="inline-start" />
                  {ui("Lấy lại công cụ")}
                </Button>
              )}
            </div>
            <dl className="grid grid-cols-3 gap-x-6 gap-y-2 font-secondary-body">
              <dt className="text-content-muted">{ui("Trạng thái")}</dt>
              <dd className="col-span-2 text-content-primary">{ui(badge.label)}</dd>
              {server.authType === "NONE" ? null : (
                <>
                  <dt className="text-content-muted">{ui("Tài khoản nào")}</dt>
                  <dd className="col-span-2 text-content-primary">
                    {ui(
                      server.authPerformer === "ADMIN"
                        ? "Dùng chung một tài khoản"
                        : "Mỗi người dùng tài khoản của mình",
                    )}
                  </dd>
                </>
              )}
              <dt className="text-content-muted">{ui("Ai dùng được")}</dt>
              <dd className="col-span-2 text-content-primary">
                {ui(server.tenantWide ? "Cả tổ chức" : "Chọn nhóm")}
              </dd>
              <dt className="text-content-muted">{ui("Công cụ")}</dt>
              <dd className="col-span-2 text-content-primary">{tools}</dd>
            </dl>
            {server.authType === "OAUTH" ? <McpOAuthClients server={server} setup={setup} /> : null}
            <McpToolList serverId={server.id} />
            <Button
              size="sm"
              prominence="internal"
              tone="danger"
              className="self-start"
              onClick={props.onRemove}
            >
              <Trash2 data-icon="inline-start" />
              {ui("Xóa máy chủ")}
            </Button>
          </div>
        </SheetContent>
      </Sheet>
    </>
  );
}

/** Rare actions share one menu. */
function ServerMenu({ server, step, onEdit, onRefresh, onRemove }: RowProps & { step: Step }) {
  const ui = useAppTranslation();
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <IconButton
          size="sm"
          prominence="internal"
          aria-label={ui("Thao tác với {{name}}", { name: server.name })}
        >
          <MoreHorizontal />
        </IconButton>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-44">
        <DropdownMenuGroup>
          <DropdownMenuItem onSelect={onEdit}>
            <Pencil /> {ui("Sửa")}
          </DropdownMenuItem>
          {step === "FETCH_TOOLS" ? null : (
            <DropdownMenuItem onSelect={onRefresh}>
              <RefreshCw /> {ui("Lấy lại công cụ")}
            </DropdownMenuItem>
          )}
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <DropdownMenuGroup>
          <DropdownMenuItem variant="destructive" onSelect={onRemove}>
            <Trash2 /> {ui("Xóa máy chủ")}
          </DropdownMenuItem>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** Whatever the server still lacks, as one button. */
function NextStepAction({
  step,
  server,
  own,
  refreshing,
  setup,
  onRefresh,
  onOpen,
  onEdit,
  onApiKey,
  primary = false,
}: RowProps & { step: Step; setup: OAuthSetup; onOpen: () => void; primary?: boolean }) {
  const ui = useAppTranslation();
  const apps = setup.clients.data ?? [];
  const prominence = primary ? "primary" : "secondary";
  switch (step) {
    case "ADD_OAUTH_APP":
      // While the result of discovery or the manual form is open, that is where the step continues.
      if (setup.discovery.data || setup.manual) return null;
      return (
        <Button
          size="sm"
          prominence={prominence}
          pending={setup.discovery.isPending}
          onClick={() => {
            onOpen();
            setup.begin();
          }}
        >
          {ui("Thêm ứng dụng OAuth")}
        </Button>
      );
    case "CONNECT_SHARED": {
      // A shared API key is entered with the server; a shared OAuth account signs in through an application.
      if (server.authType === "API_TOKEN")
        return (
          <Button size="sm" prominence={prominence} onClick={onEdit}>
            {ui("Nhập khóa API dùng chung")}
          </Button>
        );
      const only = apps.length === 1 ? apps[0] : undefined;
      return (
        <Button
          size="sm"
          prominence={prominence}
          pending={setup.connect.isPending}
          onClick={() =>
            only
              ? setup.connect.mutate({ path: setup.path, body: { oauthClientId: only.id } })
              : onOpen()
          }
        >
          {ui("Kết nối tài khoản dùng chung")}
        </Button>
      );
    }
    case "CONNECT_OWN":
      return own ? (
        <ConnectAction
          connection={own}
          returnPath="/admin/mcp"
          onApiKey={onApiKey}
          label={ui("Kết nối tài khoản của bạn")}
          primary={primary}
        />
      ) : null;
    case "FETCH_TOOLS":
      return (
        <Button size="sm" prominence={prominence} pending={refreshing} onClick={onRefresh}>
          <RefreshCw data-icon="inline-start" />
          {ui("Lấy công cụ")}
        </Button>
      );
    default:
      return null;
  }
}

/** The outcome code the OAuth callback appended; it never carries provider text. */
function ConnectionOutcome({ outcome }: { outcome: string }) {
  const ui = useAppTranslation();
  const copy =
    outcome === "connected"
      ? { variant: "default" as const, text: "Đã kết nối máy chủ MCP." }
      : outcome === "authorization-cancelled"
        ? { variant: "default" as const, text: "Bạn đã hủy việc cấp quyền." }
        : outcome === "issuer-mismatch"
          ? {
              variant: "destructive" as const,
              text: "Máy chủ cấp quyền không khớp cấu hình đã lưu.",
            }
          : outcome === "configuration-changed"
            ? {
                variant: "default" as const,
                text: "Cấu hình đã đổi khi đang cấp quyền. Hãy thử lại.",
              }
            : { variant: "destructive" as const, text: "Cấp quyền không thành công." };
  return (
    <Alert variant={copy.variant} role="status">
      <AlertDescription>{ui(copy.text)}</AlertDescription>
    </Alert>
  );
}
