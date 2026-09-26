import { useState } from "react";
import { revalidateLogic, useStore } from "@tanstack/react-form";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { z } from "zod";
import { useAppForm } from "@/components/form/app-form";
import { CatalogDialog } from "@/components/composites/catalog-dialog";
import { SectionHeader } from "@/components/composites/section-header";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Separator } from "@/components/ui/separator";
import { StatusBadge } from "@/components/ui/status-badge";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  createMcpServerOAuthClientMutation,
  deleteMcpServerOAuthClientMutation,
  discoverMcpServerOAuthMutation,
  disconnectMcpServerOAuthMutation,
  listMcpServerOAuthClientsOptions,
  registerMcpServerOAuthClientMutation,
  startMcpServerOAuthAuthorizationMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { McpOAuthDiscovery, McpServerView } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { invalidateMcpServers } from "./mcp-servers";

type RegistrationSource = "REGISTERED" | "METADATA_DOCUMENT";

/** The server's OAuth client actions; each change rereads the clients and the server list. */
function useOAuthClientMutations(serverId: string) {
  const cache = useQueryClient();
  const onSuccess = () => invalidateMcpServers(cache, serverId);
  const discovery = useMutation(discoverMcpServerOAuthMutation());
  const register = useMutation({
    ...registerMcpServerOAuthClientMutation(),
    onSuccess: async () => {
      discovery.reset();
      await onSuccess();
    },
  });
  const remove = useMutation({ ...deleteMcpServerOAuthClientMutation(), onSuccess });
  const connect = useMutation({
    ...startMcpServerOAuthAuthorizationMutation(),
    onSuccess: ({ authorizationUrl }) => {
      if (authorizationUrl) window.location.assign(authorizationUrl);
    },
  });
  const disconnect = useMutation({ ...disconnectMcpServerOAuthMutation(), onSuccess });
  return { discovery, register, remove, connect, disconnect };
}

/**
 * The OAuth clients of one server. A Tenant may hold several: a Google `Internal` app authorizes only its own
 * Workspace, so each organization keeps its own client and the User picks one by label when connecting.
 */
export function McpOAuthClients({ server }: { server: McpServerView }) {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const cache = useQueryClient();
  const [adding, setAdding] = useState(false);
  const path = { serverId: server.id };
  const clients = useQuery(listMcpServerOAuthClientsOptions({ path }));
  const { discovery, register, remove, connect, disconnect } = useOAuthClientMutations(server.id);
  // One feedback line: the most recent failed action.
  const failure = [discovery, register, remove, connect, disconnect]
    .filter((mutation) => mutation.isError)
    .sort((a, b) => b.submittedAt - a.submittedAt)[0]?.error;

  const shared = server.authPerformer === "ADMIN";
  return (
    <div className="flex flex-col gap-3">
      <SectionHeader
        level="group"
        title={ui("Ứng dụng OAuth")}
        description={ui(
          "Mỗi tổ chức dùng ứng dụng OAuth riêng. Khi kết nối, người dùng chọn theo nhãn bạn đặt ở đây.",
        )}
        actions={
          <>
            {server.oauthProviderMode === "AUTO_DISCOVERY" ? (
              <Button
                prominence="secondary"
                size="sm"
                pending={discovery.isPending}
                onClick={() => discovery.mutate({ path })}
              >
                {ui("Dò máy chủ OAuth")}
              </Button>
            ) : null}
            <Button prominence="secondary" size="sm" onClick={() => setAdding(true)}>
              {ui("Nhập ứng dụng")}
            </Button>
          </>
        }
      />

      {failure ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>
            {problem(presentProblem(failure, "mutation").message)}
          </AlertDescription>
        </Alert>
      ) : null}
      {clients.isError ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>
            {problem(presentProblem(clients.error, "initialLoad").message)}
          </AlertDescription>
        </Alert>
      ) : null}

      {discovery.data ? (
        <DiscoveryReview
          discovery={discovery.data}
          pending={register.isPending}
          onRegister={(issuer, label, source) =>
            register.mutate({ path, body: { issuer, label, source } })
          }
          onDismiss={() => discovery.reset()}
        />
      ) : null}

      {clients.data && clients.data.length === 0 ? (
        <FieldDescription>
          {ui("Chưa có ứng dụng OAuth nào. Người dùng chưa kết nối được máy chủ này.")}
        </FieldDescription>
      ) : null}

      <ul className="flex flex-col gap-2">
        {(clients.data ?? []).map((client) => (
          <li key={client.id} className="flex flex-wrap items-start justify-between gap-2">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-main-ui-body text-content-primary">{client.label}</span>
                <StatusBadge tone="neutral">
                  {ui(
                    client.source === "ADMIN"
                      ? "Nhập tay"
                      : client.source === "REGISTERED"
                        ? "Tự đăng ký"
                        : "Tài liệu ứng dụng",
                  )}
                </StatusBadge>
                {client.clientSecretConfigured ? (
                  <StatusBadge tone="success">{ui("Đã lưu bí mật")}</StatusBadge>
                ) : null}
              </div>
              <p className="font-secondary-body break-all text-content-muted">{client.issuer}</p>
            </div>
            <div className="flex gap-2">
              {shared ? (
                <Button
                  prominence="secondary"
                  size="sm"
                  pending={connect.isPending}
                  onClick={() => connect.mutate({ path, body: { oauthClientId: client.id } })}
                >
                  {ui("Kết nối")}
                </Button>
              ) : null}
              <Button
                prominence="secondary"
                tone="danger"
                size="sm"
                onClick={() =>
                  remove.mutate({
                    path: { ...path, clientId: client.id },
                    query: { revision: client.revision },
                  })
                }
              >
                {ui("Xoá")}
              </Button>
            </div>
          </li>
        ))}
      </ul>

      {shared && server.sharedCredentialConfigured ? (
        <Button
          prominence="secondary"
          tone="danger"
          size="sm"
          className="self-start"
          pending={disconnect.isPending}
          onClick={() => disconnect.mutate({ path })}
        >
          {ui("Ngắt kết nối dùng chung")}
        </Button>
      ) : null}

      {adding ? (
        <McpOAuthClientEditor
          serverId={server.id}
          onClose={() => setAdding(false)}
          onSaved={async () => {
            setAdding(false);
            await invalidateMcpServers(cache, server.id);
          }}
        />
      ) : null}
    </div>
  );
}

/** Discovery is explicit and saves nothing; the administrator picks an issuer and names the client. */
function DiscoveryReview({
  discovery,
  pending,
  onRegister,
  onDismiss,
}: {
  discovery: McpOAuthDiscovery;
  pending: boolean;
  onRegister: (issuer: string, label: string, source: RegistrationSource) => void;
  onDismiss: () => void;
}) {
  const ui = useAppTranslation();
  const [label, setLabel] = useState("");
  return (
    <Card size="sm">
      <CardContent>
        <div className="flex flex-col gap-3">
          <SectionHeader
            level="group"
            title={ui("Kết quả dò")}
            actions={
              <Button prominence="internal" size="sm" onClick={onDismiss}>
                {ui("Đóng")}
              </Button>
            }
          />
          <Field>
            <FieldLabel htmlFor="mcp-discovered-label">{ui("Nhãn cho ứng dụng này")}</FieldLabel>
            <Input
              id="mcp-discovered-label"
              value={label}
              onChange={(event) => setLabel(event.target.value)}
              placeholder={ui("Ví dụ: Tasco Miền Bắc")}
            />
          </Field>
          {discovery.authorizationServers.map((server) => (
            <div key={server.issuer} className="flex flex-col gap-2">
              <Separator />
              <p className="font-secondary-body break-all text-content-primary">{server.issuer}</p>
              <div className="flex flex-wrap gap-2">
                <Button
                  size="sm"
                  prominence="secondary"
                  disabled={!server.registrationAvailable || label.trim() === "" || pending}
                  onClick={() => onRegister(server.issuer, label.trim(), "REGISTERED")}
                >
                  {ui("Tự đăng ký (DCR)")}
                </Button>
                <Button
                  size="sm"
                  prominence="secondary"
                  disabled={!server.metadataDocumentAvailable || label.trim() === "" || pending}
                  onClick={() => onRegister(server.issuer, label.trim(), "METADATA_DOCUMENT")}
                >
                  {ui("Dùng tài liệu ứng dụng")}
                </Button>
              </div>
              {!server.registrationAvailable && !server.metadataDocumentAvailable ? (
                <FieldDescription>
                  {ui("Máy chủ này không tự đăng ký được. Hãy nhập ứng dụng thủ công.")}
                </FieldDescription>
              ) : null}
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}

const clientSchema = z.object({
  label: z.string().trim().min(1),
  issuer: z.string().trim().min(1),
  clientId: z.string().trim().min(1),
  clientSecret: z.string(),
  authorizationEndpoint: z.string().trim().min(1),
  tokenEndpoint: z.string().trim().min(1),
  revocationEndpoint: z.string(),
});

/** A client the administrator pasted, for example a Google `Internal` Web client. */
function McpOAuthClientEditor({
  serverId,
  onClose,
  onSaved,
}: {
  serverId: string;
  onClose: () => void;
  onSaved: () => void;
}) {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  // The request carries the client secret, so the finished mutation is not kept in the cache.
  const save = useMutation({
    ...createMcpServerOAuthClientMutation(),
    gcTime: 0,
    onSuccess: onSaved,
  });
  const form = useAppForm({
    defaultValues: {
      label: "",
      issuer: "",
      clientId: "",
      clientSecret: "",
      authorizationEndpoint: "",
      tokenEndpoint: "",
      revocationEndpoint: "",
    },
    validationLogic: revalidateLogic(),
    validators: { onDynamic: clientSchema },
    onSubmit: async ({ value }) => {
      const secret = value.clientSecret.trim() !== "";
      await save
        .mutateAsync({
          path: { serverId },
          body: {
            label: value.label.trim(),
            issuer: value.issuer.trim(),
            clientId: value.clientId.trim(),
            clientSecret: secret
              ? { action: "REPLACE", value: value.clientSecret }
              : { action: "KEEP" },
            // A public client sends no secret; anything else posts it in the token request form.
            tokenEndpointAuthMethod: secret ? "CLIENT_SECRET_POST" : "NONE",
            authorizationEndpoint: value.authorizationEndpoint.trim(),
            tokenEndpoint: value.tokenEndpoint.trim(),
            revocationEndpoint:
              value.revocationEndpoint.trim() === "" ? null : value.revocationEndpoint.trim(),
            issParameterRequired: false,
          },
        })
        .catch(() => undefined);
    },
  });
  const complete = useStore(form.store, (state) => clientSchema.safeParse(state.values).success);

  return (
    <CatalogDialog
      title={ui("Nhập ứng dụng OAuth")}
      description={ui("Dùng cho một tổ chức. Bí mật được mã hoá và không hiện lại.")}
      onClose={onClose}
    >
      <form
        noValidate
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          void form.handleSubmit();
        }}
      >
        <FieldGroup>
          <div className="grid gap-4 sm:grid-cols-2">
            <form.AppField name="label">
              {(field) => <field.TextField label={ui("Nhãn")} />}
            </form.AppField>
            <form.AppField name="issuer">
              {(field) => <field.TextField label={ui("Issuer")} />}
            </form.AppField>
            <form.AppField name="clientId">
              {(field) => <field.TextField label={ui("Client ID")} />}
            </form.AppField>
            <form.AppField name="clientSecret">
              {(field) => (
                <field.TextField label={ui("Client secret")} type="password" autoComplete="off" />
              )}
            </form.AppField>
            <form.AppField name="authorizationEndpoint">
              {(field) => <field.TextField label={ui("Điểm cuối cấp quyền")} />}
            </form.AppField>
            <form.AppField name="tokenEndpoint">
              {(field) => <field.TextField label={ui("Điểm cuối lấy token")} />}
            </form.AppField>
            <form.AppField name="revocationEndpoint">
              {(field) => <field.TextField label={ui("Điểm cuối thu hồi")} />}
            </form.AppField>
          </div>
        </FieldGroup>
        {save.error ? (
          <Alert variant="destructive" role="alert">
            <AlertDescription>
              {problem(presentProblem(save.error, "mutation").message)}
            </AlertDescription>
          </Alert>
        ) : null}
        <div className="flex justify-end gap-2">
          <Button prominence="secondary" onClick={onClose}>
            {ui("Huỷ")}
          </Button>
          <Button type="submit" disabled={!complete} pending={save.isPending}>
            {ui("Lưu")}
          </Button>
        </div>
      </form>
    </CatalogDialog>
  );
}
