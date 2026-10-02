import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Trash2 } from "lucide-react";
import { FormDialog } from "@/components/composites/form-dialog";
import { SectionHeader } from "@/components/composites/section-header";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Field, FieldError, FieldLabel } from "@/components/ui/field";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  addMcpTrustedAppMutation,
  getMcpEndpointConnectionQueryKey,
  listMcpTrustedAppsOptions,
  listMcpTrustedAppsQueryKey,
  removeMcpTrustedAppMutation,
  setMcpTrustedAppEnabledMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { McpTrustedAppResponse } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { McpClientMark } from "./mcp-client-mark";

// The server's rule: a domain, optionally behind `*.` for its subdomains; loopback only where a document lists it.
const DOMAIN = /^(\*\.)?([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/;
const LOOPBACK = new Set(["localhost", "127.0.0.1"]);

function domains(text: string) {
  return [
    ...new Set(
      text
        .split(/[\s,]+/)
        .map((value) => value.trim().toLowerCase())
        .filter(Boolean),
    ),
  ];
}

/**
 * MEM-207: the apps whose client ID metadata documents Keycloak accepts. Claude and ChatGPT are built in and can only
 * be switched off; an app of the organization's own is added by its domains. Switching an app off or removing it
 * revokes every connection made through it, so both ask first.
 */
export function McpTrustedApps() {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const apps = useQuery({ ...listMcpTrustedAppsOptions(), retry: false });
  const [adding, setAdding] = useState(false);
  const manageable = apps.data?.manageable ?? false;

  return (
    <section aria-labelledby="mcp-trusted-apps" className="flex flex-col gap-4">
      <SectionHeader
        id="mcp-trusted-apps"
        title={ui("Ứng dụng được tin cậy")}
        actions={
          manageable ? (
            <Button prominence="secondary" size="sm" onClick={() => setAdding(true)}>
              <Plus data-icon="inline-start" aria-hidden="true" />
              {ui("Thêm ứng dụng")}
            </Button>
          ) : null
        }
      />
      {apps.isPending ? (
        <p role="status">{ui("Đang tải…")}</p>
      ) : apps.isError ? (
        <Alert variant="destructive">
          <AlertTitle>
            {problemMessage(presentProblem(apps.error, "initialLoad").message)}
          </AlertTitle>
          <div>
            <Button onClick={() => void apps.refetch()}>{ui("Tải lại")}</Button>
          </div>
        </Alert>
      ) : (
        <>
          {!manageable ? (
            <Alert role="note">
              <AlertDescription>
                {ui("Máy chủ này chưa cấu hình tài khoản quản lý ứng dụng tin cậy.")}
              </AlertDescription>
            </Alert>
          ) : null}
          <SettingRows>
            {apps.data.apps.map((app) => (
              <TrustedAppRow key={app.id} app={app} manageable={manageable} />
            ))}
          </SettingRows>
        </>
      )}
      {adding ? <AddTrustedAppDialog onClose={() => setAdding(false)} /> : null}
    </section>
  );
}

function useInvalidateTrustedApps() {
  const cache = useQueryClient();
  return () =>
    Promise.all([
      cache.invalidateQueries({ queryKey: listMcpTrustedAppsQueryKey() }),
      cache.invalidateQueries({ queryKey: getMcpEndpointConnectionQueryKey() }),
    ]);
}

function TrustedAppRow({ app, manageable }: { app: McpTrustedAppResponse; manageable: boolean }) {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const invalidate = useInvalidateTrustedApps();
  const [stopping, setStopping] = useState(false);
  const toggle = useMutation({ ...setMcpTrustedAppEnabledMutation(), onSettled: invalidate });
  const remove = useMutation({ ...removeMcpTrustedAppMutation(), onSettled: invalidate });
  const path = { appId: app.id };
  const switchId = `mcp-trusted-app-${app.id}`;

  return (
    <SettingRow
      icon={<McpClientMark client={app.preset} />}
      title={app.name}
      htmlFor={switchId}
      description={
        <>
          <span className="block break-all">{app.clientIdHosts.join(", ")}</span>
          {toggle.isError ? (
            <span role="alert" className="block text-status-danger-content">
              {problemMessage(presentProblem(toggle.error, "mutation").message)}
            </span>
          ) : null}
        </>
      }
      control={
        <div className="flex items-center gap-2">
          {!app.builtIn && manageable ? (
            <ConfirmDialog
              trigger={
                <IconButton
                  size="sm"
                  tone="danger"
                  aria-label={ui("Gỡ {{name}}", { name: app.name })}
                  title={ui("Gỡ")}
                >
                  <Trash2 />
                </IconButton>
              }
              title={ui("Gỡ {{name}}?", { name: app.name })}
              description={ui("Mọi kết nối qua {{name}} sẽ bị thu hồi.", { name: app.name })}
              confirmLabel={ui("Gỡ")}
              pendingLabel={ui("Đang gỡ…")}
              confirmTone="danger"
              onConfirm={async () => {
                await remove.mutateAsync({ path, query: { revision: app.revision } });
              }}
            />
          ) : null}
          <Switch
            id={switchId}
            checked={app.enabled}
            disabled={!manageable || toggle.isPending}
            onCheckedChange={(enabled) => {
              if (enabled) toggle.mutate({ path, body: { enabled, revision: app.revision } });
              else setStopping(true);
            }}
          />
          <ConfirmDialog
            open={stopping}
            onOpenChange={setStopping}
            title={ui("Ngừng tin cậy {{name}}?", { name: app.name })}
            description={ui("Mọi kết nối qua {{name}} sẽ bị thu hồi.", { name: app.name })}
            confirmLabel={ui("Ngừng tin cậy")}
            pendingLabel={ui("Đang lưu…")}
            confirmTone="danger"
            onConfirm={async () => {
              await toggle.mutateAsync({ path, body: { enabled: false, revision: app.revision } });
            }}
          />
        </div>
      }
    />
  );
}

function AddTrustedAppDialog({ onClose }: { onClose: () => void }) {
  const ui = useAppTranslation();
  const invalidate = useInvalidateTrustedApps();
  const add = useMutation({ ...addMcpTrustedAppMutation(), onSuccess: invalidate });
  const [name, setName] = useState("");
  const [clientIdText, setClientIdText] = useState("");
  const [documentText, setDocumentText] = useState("");
  const clientIdHosts = domains(clientIdText);
  const documentHosts = domains(documentText);
  const invalidClientId = clientIdHosts.find((host) => !DOMAIN.test(host));
  const invalidDocument = documentHosts.find((host) => !DOMAIN.test(host) && !LOOPBACK.has(host));
  const complete =
    name.trim() !== "" &&
    clientIdHosts.length > 0 &&
    clientIdHosts.length <= 10 &&
    new Set([...clientIdHosts, ...documentHosts]).size <= 20 &&
    !invalidClientId &&
    !invalidDocument;

  return (
    <FormDialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={ui("Thêm ứng dụng")}
      description={ui("Người dùng kết nối ứng dụng này bằng địa chỉ MCP và quyền của chính họ.")}
      submitLabel={ui("Thêm")}
      submitDisabled={!complete}
      onSubmit={async () => {
        await add.mutateAsync({ body: { name: name.trim(), clientIdHosts, documentHosts } });
      }}
    >
      <Field>
        <FieldLabel htmlFor="mcp-trusted-app-name">{ui("Tên")}</FieldLabel>
        <Input
          id="mcp-trusted-app-name"
          maxLength={80}
          value={name}
          onChange={(event) => setName(event.target.value)}
        />
      </Field>
      <Field data-invalid={invalidClientId ? true : undefined}>
        <FieldLabel htmlFor="mcp-trusted-app-client-id-hosts">
          {ui("Tên miền của client ID")}
        </FieldLabel>
        <Input
          id="mcp-trusted-app-client-id-hosts"
          value={clientIdText}
          placeholder={ui("agent.example.com")}
          aria-invalid={invalidClientId ? true : undefined}
          onChange={(event) => setClientIdText(event.target.value)}
        />
        {invalidClientId ? (
          <FieldError>
            {ui("“{{value}}” không phải tên miền.", { value: invalidClientId })}
          </FieldError>
        ) : null}
      </Field>
      <Field data-invalid={invalidDocument ? true : undefined}>
        <FieldLabel htmlFor="mcp-trusted-app-document-hosts">
          {ui("Tên miền khác")}
          <span className="text-content-muted">{ui("(không bắt buộc)")}</span>
        </FieldLabel>
        <Input
          id="mcp-trusted-app-document-hosts"
          value={documentText}
          placeholder={ui("auth.example.com")}
          aria-invalid={invalidDocument ? true : undefined}
          onChange={(event) => setDocumentText(event.target.value)}
        />
        {invalidDocument ? (
          <FieldError>
            {ui("“{{value}}” không phải tên miền.", { value: invalidDocument })}
          </FieldError>
        ) : null}
      </Field>
    </FormDialog>
  );
}
