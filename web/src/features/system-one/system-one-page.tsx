import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plug, Settings2, Split, Trash2, WifiOff } from "lucide-react";
import { ConnectionStatusBadge } from "@/components/composites/connection-form";
import { EmptyState } from "@/components/composites/empty-state";
import { SectionHeader } from "@/components/composites/section-header";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { ProviderCard } from "@/components/provider-logos/provider-card";
import { ProviderLogo } from "@/components/provider-logos/provider-logo";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import { Skeleton } from "@/components/ui/skeleton";
import { StatusBadge } from "@/components/ui/status-badge";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { DataBoundaryTag } from "@/features/models/data-boundary";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  deleteSystemOneConnectionMutation,
  listChatModelFlowsOptions,
  listSystemOneConnectionsOptions,
  listSystemOneTypesOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SystemOneConnection, SystemOneType } from "@/lib/hey-api/types.gen";
import {
  CHECK_FLOW,
  connectionTarget,
  invalidateSystemOne,
  providerMark,
  providerNames,
  systemOneDeleteProblem,
  type SystemOneProviderId,
} from "./system-one";
import { SystemOneConnectionDialog } from "./system-one-connection-dialog";
import { SystemOneTask } from "./system-one-task";

type Translate = ReturnType<typeof useAppTranslation>;

function providerSummary(provider: SystemOneProviderId, ui: Translate) {
  switch (provider) {
    case "TYPESAFE":
      return ui("Jev, bản hosted của TypeSafe AI");
    case "CLOUDFLARE":
      return ui("Clef và Clef-flash trên Workers AI");
    case "NINEROUTER":
      return ui("Một khóa đi tới nhiều nhà cung cấp");
    case "LAYA":
      return ui("Model mã nguồn mở, tự vận hành");
    default:
      return ui("Máy chủ bất kỳ nói giao thức System One");
  }
}

function ProviderIcon({ provider }: { provider: SystemOneProviderId }) {
  const mark = providerMark[provider];
  return mark ? (
    <ProviderLogo mark={mark} className="size-7" />
  ) : (
    <Plug className="size-5 text-content-secondary" aria-hidden="true" />
  );
}

/** What the dialog is open on: a new connection of a type, or a saved one. */
type Editing = { type: SystemOneType; connection?: SystemOneConnection };

/** `/admin/system-one`: what the question check runs on, the connections, and the types to add. */
export function SystemOnePage() {
  const ui = useAppTranslation();
  const session = useApplicationSession();
  const manager = session.capabilities.includes("MODELS_MANAGE");
  const types = useQuery({ ...listSystemOneTypesOptions(), enabled: manager, retry: false });
  const connections = useQuery({
    ...listSystemOneConnectionsOptions(),
    enabled: manager,
    retry: false,
  });
  const flows = useQuery({ ...listChatModelFlowsOptions(), enabled: manager, retry: false });
  const [editing, setEditing] = useState<Editing | null>(null);
  if (!manager)
    return (
      <p role="alert" className="p-6">
        {ui("Bạn không có quyền quản lý mô hình.")}
      </p>
    );
  const flow = flows.data?.find((entry) => entry.flow === CHECK_FLOW);
  const failed = types.isError || connections.isError || flows.isError;
  return (
    <SettingsLayout>
      <PageHeader
        title={ui("Phân loại (System One)")}
        icon={<Split />}
        description={ui("Chạy bước kiểm tra câu hỏi bằng model phân loại nhanh thay cho LLM.")}
      />
      {failed ? (
        <EmptyState
          role="alert"
          icon={<WifiOff />}
          title={ui("Không tải được cấu hình System One.")}
          action={
            <Button
              size="sm"
              prominence="secondary"
              onClick={() => {
                void types.refetch();
                void connections.refetch();
                void flows.refetch();
              }}
            >
              {ui("Tải lại")}
            </Button>
          }
        />
      ) : !types.data || !connections.data || !flow ? (
        <div role="status" className="flex flex-col gap-8">
          <span className="sr-only">{ui("Đang tải…")}</span>
          {[0, 1].map((index) => (
            <div key={index} className="flex flex-col gap-3">
              <Skeleton className="h-6 w-48" />
              <Skeleton className="h-20 w-full" />
            </div>
          ))}
        </div>
      ) : (
        <div className="flex flex-col gap-8">
          <section aria-labelledby="system-one-task" className="flex flex-col gap-3">
            <SectionHeader id="system-one-task" title={ui("Models by task")} />
            <SystemOneTask flow={flow} connections={connections.data} types={types.data} />
          </section>
          {connections.data.length > 0 && (
            <section aria-labelledby="system-one-connections" className="flex flex-col gap-3">
              <SectionHeader id="system-one-connections" title={ui("Available connections")} />
              <ul className="flex flex-col gap-2">
                {connections.data.map((connection) => {
                  const type = types.data.find((entry) => entry.provider === connection.provider);
                  return type ? (
                    <ConnectionCard
                      key={connection.id}
                      type={type}
                      connection={connection}
                      inUse={flow.systemOneConnectionId === connection.id}
                      onEdit={() => setEditing({ type, connection })}
                    />
                  ) : null;
                })}
              </ul>
            </section>
          )}
          <section aria-labelledby="system-one-add" className="flex flex-col gap-3">
            <SectionHeader id="system-one-add" title={ui("Thêm kết nối")} />
            <ul className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              {types.data.map((type) => (
                <ProviderCard
                  as="li"
                  key={type.provider}
                  aria-label={providerNames[type.provider]}
                  logo={<ProviderIcon provider={type.provider} />}
                  name={providerNames[type.provider]}
                  description={providerSummary(type.provider, ui)}
                  actions={
                    <Button
                      size="sm"
                      prominence="secondary"
                      aria-label={ui("Kết nối {{name}}", { name: providerNames[type.provider] })}
                      onClick={() => setEditing({ type })}
                    >
                      {ui("Kết nối")}
                    </Button>
                  }
                />
              ))}
            </ul>
          </section>
        </div>
      )}
      {editing && connections.data && (
        <SystemOneConnectionDialog
          type={editing.type}
          connection={editing.connection}
          takenNames={connections.data
            .filter((connection) => connection.id !== editing.connection?.id)
            .map((connection) => connection.name.toLowerCase())}
          onClose={() => setEditing(null)}
        />
      )}
    </SettingsLayout>
  );
}

function ConnectionCard({
  type,
  connection,
  inUse,
  onEdit,
}: {
  type: SystemOneType;
  connection: SystemOneConnection;
  inUse: boolean;
  onEdit: () => void;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const remove = useMutation({
    ...deleteSystemOneConnectionMutation(),
    onSuccess: () => invalidateSystemOne(cache),
  });
  const name = connection.name;
  const ready = connection.credentialConfigured || !type.requiresKey;
  return (
    <ProviderCard
      as="li"
      aria-label={name}
      logo={<ProviderIcon provider={connection.provider} />}
      name={
        <>
          <span>{name}</span>
          {inUse ? (
            <ConnectionStatusBadge>{ui("Đang dùng")}</ConnectionStatusBadge>
          ) : !ready ? (
            <StatusBadge tone="warning">{ui("Cần cấu hình thêm")}</StatusBadge>
          ) : null}
        </>
      }
      description={
        // The boundary sits with the address it describes, and wraps under it on a narrow screen.
        <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
          <span className="min-w-0 break-all">
            {[connectionTarget(connection), connection.model].join(" · ")}
          </span>
          <DataBoundaryTag boundary={connection.dataBoundary} />
        </span>
      }
      selected={inUse}
      actions={
        <>
          <IconButton
            size="sm"
            prominence="tertiary"
            aria-label={ui("Cấu hình {{name}}", { name })}
            title={ui("Cấu hình {{name}}", { name })}
            onClick={onEdit}
          >
            <Settings2 />
          </IconButton>
          <ConfirmDialog
            trigger={
              <IconButton
                size="sm"
                prominence="tertiary"
                tone="danger"
                aria-label={ui("Xóa kết nối {{name}}", { name })}
                title={ui("Xóa kết nối {{name}}", { name })}
              >
                <Trash2 />
              </IconButton>
            }
            title={ui("Xóa kết nối {{name}}?", { name })}
            description={ui("Khóa và cấu hình của {{name}} sẽ bị xóa.", { name })}
            confirmLabel={ui("Xóa kết nối")}
            pendingLabel={ui("Đang xóa…")}
            errorMessage={systemOneDeleteProblem}
            onConfirm={async () => {
              await remove.mutateAsync({ path: { id: connection.id } });
            }}
          />
        </>
      }
    />
  );
}
