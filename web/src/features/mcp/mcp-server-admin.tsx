import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  createMcpServerMutation,
  deleteMcpServerMutation,
  refreshMcpServerToolsMutation,
  updateMcpServerMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { McpConnection, McpServerInput, McpServerView } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { mcpMessages } from "./mcp-errors";
import { useProblemMessage } from "@/lib/use-problem-message";
import { type McpCatalogEntry, type McpServerPreset, presetOf } from "./mcp-catalog";
import { McpApiKeyDialog } from "./mcp-connect-actions";
import { invalidateMcpConnections } from "./mcp-connections";
import { McpServerEditor } from "./mcp-server-editor";
import { invalidateMcpServers } from "./mcp-servers";

/**
 * The administrator's actions on servers, shared by the list and a server's page: add, edit, fetch tools, delete,
 * and the personal API key the next step may ask for. `dialogs` renders whichever of them is open.
 */
export function useServerAdmin({
  onRemoved,
  onCreated,
}: {
  onRemoved?: () => void;
  /** A new server's page continues its setup, so the list goes there once it is added. */
  onCreated?: (server: McpServerView) => void;
} = {}) {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const cache = useQueryClient();
  const [editing, setEditing] = useState<McpServerView | "new" | null>(null);
  const [preset, setPreset] = useState<McpServerPreset | undefined>(undefined);
  const [removing, setRemoving] = useState<McpServerView | null>(null);
  const [apiKeyFor, setApiKeyFor] = useState<McpConnection | null>(null);
  const reread = (serverId?: string) =>
    Promise.all([invalidateMcpServers(cache, serverId), invalidateMcpConnections(cache)]);
  const saved = async () => {
    setEditing(null);
    await reread();
  };
  // A server input can carry a shared API key, so a finished save is not kept in the mutation cache.
  const create = useMutation({
    ...createMcpServerMutation(),
    gcTime: 0,
    onSuccess: async (server) => {
      await saved();
      onCreated?.(server);
    },
  });
  const update = useMutation({ ...updateMcpServerMutation(), gcTime: 0, onSuccess: saved });
  const remove = useMutation({
    ...deleteMcpServerMutation(),
    onSuccess: async () => {
      setRemoving(null);
      onRemoved?.();
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

  const dialogs = (
    <>
      {editing ? (
        <McpServerEditor
          server={editing === "new" ? undefined : editing}
          preset={editing === "new" ? preset : undefined}
          saving={create.isPending || update.isPending}
          error={
            saveError
              ? problem(presentProblem(saveError, "mutation", mcpMessages).message)
              : undefined
          }
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
          onOpenChange={(open) => {
            if (!open) setRemoving(null);
          }}
        />
      ) : null}
    </>
  );

  return {
    add: () => {
      setPreset(undefined);
      setEditing("new");
    },
    addFrom: (entry: McpCatalogEntry) => {
      setPreset(presetOf(entry));
      setEditing("new");
    },
    edit: setEditing,
    remove: setRemoving,
    askApiKey: setApiKeyFor,
    refresh: (server: McpServerView) => refresh.mutate({ path: { serverId: server.id } }),
    refreshing: (server: McpServerView) =>
      refresh.isPending && refresh.variables?.path.serverId === server.id,
    refreshError: refresh.error
      ? problem(presentProblem(refresh.error, "mutation").message)
      : undefined,
    dialogs,
  };
}

export type ServerAdmin = ReturnType<typeof useServerAdmin>;
