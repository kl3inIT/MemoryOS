import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { StatusBadge } from "@/components/ui/status-badge";
import { CatalogDialog } from "@/components/composites/catalog-dialog";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  createMcpServerOAuthClient,
  deleteMcpServerOAuthClient,
  discoverMcpServerOAuth,
  disconnectMcpServerOAuth,
  listMcpServerOAuthClients,
  registerMcpServerOAuthClient,
  startMcpServerOAuthAuthorization,
} from "@/lib/hey-api/sdk.gen";
import type {
  McpOAuthAuthorizationServer,
  McpOAuthClientView,
  McpServerView,
} from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";

/**
 * The OAuth clients of one server. A Tenant may hold several: a Google `Internal` app authorizes only its own
 * Workspace, so each organization keeps its own client and the User picks one by label when connecting.
 */
export function McpOAuthClients({ server }: { server: McpServerView }) {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const message = (failure: unknown) => problem(presentProblem(failure, "mutation").message);
  const queries = useQueryClient();
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState<string>();

  const clientsKey = ["mcp", "oauth-clients", server.id] as const;
  const clients = useQuery({
    queryKey: clientsKey,
    queryFn: async () => (await listMcpServerOAuthClients({ path: { serverId: server.id } })).data,
  });
  const refresh = async () => {
    await Promise.all([
      queries.invalidateQueries({ queryKey: clientsKey }),
      queries.invalidateQueries({ queryKey: ["mcp", "servers"] }),
    ]);
  };

  const discovery = useMutation({
    mutationFn: async () =>
      (
        await discoverMcpServerOAuth({
          path: { serverId: server.id },
        })
      ).data,
    onError: (failure) => setError(message(failure)),
  });

  const register = useMutation({
    mutationFn: async ({
      issuer,
      label,
      source,
    }: {
      issuer: string;
      label: string;
      source: "REGISTERED" | "METADATA_DOCUMENT";
    }) =>
      await registerMcpServerOAuthClient({
        path: { serverId: server.id },
        body: { issuer, label, source },
      }),
    onSuccess: async () => {
      setError(undefined);
      discovery.reset();
      await refresh();
    },
    onError: (failure) => setError(message(failure)),
  });

  const remove = useMutation({
    mutationFn: async (client: McpOAuthClientView) =>
      await deleteMcpServerOAuthClient({
        path: { serverId: server.id, clientId: client.id },
        query: { revision: client.revision },
      }),
    onSuccess: refresh,
    onError: (failure) => setError(message(failure)),
  });

  const connect = useMutation({
    mutationFn: async (client: McpOAuthClientView) =>
      await startMcpServerOAuthAuthorization({
        path: { serverId: server.id },
        body: { oauthClientId: client.id },
      }),
    onSuccess: (response) => {
      const url = response.data?.authorizationUrl;
      if (url) window.location.assign(url);
    },
    onError: (failure) => setError(message(failure)),
  });

  const disconnect = useMutation({
    mutationFn: async () =>
      await disconnectMcpServerOAuth({
        path: { serverId: server.id },
      }),
    onSuccess: refresh,
    onError: (failure) => setError(message(failure)),
  });

  const shared = server.authPerformer === "ADMIN";
  return (
    <div className="mt-4 flex flex-col gap-3 border-t border-border-subtle pt-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h4 className="font-main-ui-body-strong text-content-primary">{ui("Ứng dụng OAuth")}</h4>
        <div className="flex flex-wrap gap-2">
          {server.oauthProviderMode === "AUTO_DISCOVERY" ? (
            <Button
              prominence="secondary"
              size="sm"
              pending={discovery.isPending}
              onClick={() => discovery.mutate()}
            >
              {ui("Dò máy chủ OAuth")}
            </Button>
          ) : null}
          <Button prominence="secondary" size="sm" onClick={() => setAdding(true)}>
            {ui("Nhập ứng dụng")}
          </Button>
        </div>
      </div>

      <p className="font-secondary-body text-content-muted">
        {ui(
          "Mỗi tổ chức dùng ứng dụng OAuth riêng. Khi kết nối, người dùng chọn theo nhãn bạn đặt ở đây.",
        )}
      </p>

      {error ? (
        <p role="alert" className="font-secondary-body text-status-danger-content">
          {error}
        </p>
      ) : null}
      {clients.isError ? (
        <p role="alert" className="font-secondary-body text-status-danger-content">
          {problem(presentProblem(clients.error, "initialLoad").message)}
        </p>
      ) : null}

      {discovery.data ? (
        <DiscoveryReview
          discovery={discovery.data}
          pending={register.isPending}
          onRegister={(issuer, label, source) => register.mutate({ issuer, label, source })}
          onDismiss={() => discovery.reset()}
        />
      ) : null}

      {clients.data && clients.data.length === 0 ? (
        <p className="font-secondary-body text-content-muted">
          {ui("Chưa có ứng dụng OAuth nào. Người dùng chưa kết nối được máy chủ này.")}
        </p>
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
                  onClick={() => connect.mutate(client)}
                >
                  {ui("Kết nối")}
                </Button>
              ) : null}
              <Button
                prominence="secondary"
                tone="danger"
                size="sm"
                onClick={() => remove.mutate(client)}
              >
                {ui("Xoá")}
              </Button>
            </div>
          </li>
        ))}
      </ul>

      {shared && server.sharedCredentialConfigured ? (
        <div>
          <Button
            prominence="secondary"
            tone="danger"
            size="sm"
            pending={disconnect.isPending}
            onClick={() => disconnect.mutate()}
          >
            {ui("Ngắt kết nối dùng chung")}
          </Button>
        </div>
      ) : null}

      {adding ? (
        <McpOAuthClientEditor
          serverId={server.id}
          onClose={() => setAdding(false)}
          onSaved={async () => {
            setAdding(false);
            await refresh();
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
  discovery: {
    resource: string;
    suggestedScopes: string[];
    authorizationServers: McpOAuthAuthorizationServer[];
  };
  pending: boolean;
  onRegister: (issuer: string, label: string, source: "REGISTERED" | "METADATA_DOCUMENT") => void;
  onDismiss: () => void;
}) {
  const ui = useAppTranslation();
  const [label, setLabel] = useState("");
  return (
    <div className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-surface-sunken p-3">
      <div className="flex items-start justify-between gap-2">
        <p className="font-main-ui-body-strong text-content-primary">{ui("Kết quả dò")}</p>
        <Button prominence="internal" size="sm" onClick={onDismiss}>
          {ui("Đóng")}
        </Button>
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor="mcp-discovered-label">{ui("Nhãn cho ứng dụng này")}</Label>
        <Input
          id="mcp-discovered-label"
          value={label}
          onChange={(event) => setLabel(event.target.value)}
          placeholder={ui("Ví dụ: Tasco Miền Bắc")}
        />
      </div>
      {discovery.authorizationServers.map((server) => (
        <div key={server.issuer} className="flex flex-col gap-2 border-t border-border-subtle pt-2">
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
            <p className="font-secondary-body text-content-muted">
              {ui("Máy chủ này không tự đăng ký được. Hãy nhập ứng dụng thủ công.")}
            </p>
          ) : null}
        </div>
      ))}
    </div>
  );
}

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
  const [label, setLabel] = useState("");
  const [issuer, setIssuer] = useState("");
  const [clientId, setClientId] = useState("");
  const [clientSecret, setClientSecret] = useState("");
  const [authorizationEndpoint, setAuthorizationEndpoint] = useState("");
  const [tokenEndpoint, setTokenEndpoint] = useState("");
  const [revocationEndpoint, setRevocationEndpoint] = useState("");
  const [error, setError] = useState<string>();

  const save = useMutation({
    mutationFn: async () =>
      await createMcpServerOAuthClient({
        path: { serverId },
        body: {
          label: label.trim(),
          issuer: issuer.trim(),
          clientId: clientId.trim(),
          clientSecret:
            clientSecret.trim() === ""
              ? { action: "KEEP" }
              : { action: "REPLACE", value: clientSecret },
          // A public client sends no secret; anything else posts it in the token request form.
          tokenEndpointAuthMethod: clientSecret.trim() === "" ? "NONE" : "CLIENT_SECRET_POST",
          authorizationEndpoint: authorizationEndpoint.trim(),
          tokenEndpoint: tokenEndpoint.trim(),
          revocationEndpoint: revocationEndpoint.trim() === "" ? null : revocationEndpoint.trim(),
          issParameterRequired: false,
        },
      }),
    onSuccess: onSaved,
    onError: (failure) => setError(problem(presentProblem(failure, "mutation").message)),
  });

  const complete =
    label.trim() !== "" &&
    issuer.trim() !== "" &&
    clientId.trim() !== "" &&
    authorizationEndpoint.trim() !== "" &&
    tokenEndpoint.trim() !== "";

  return (
    <CatalogDialog
      title={ui("Nhập ứng dụng OAuth")}
      description={ui("Dùng cho một tổ chức. Bí mật được mã hoá và không hiện lại.")}
      onClose={onClose}
    >
      <div className="flex flex-col gap-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field id="oauth-label" label={ui("Nhãn")} value={label} onChange={setLabel} />
          <Field id="oauth-issuer" label={ui("Issuer")} value={issuer} onChange={setIssuer} />
          <Field
            id="oauth-client"
            label={ui("Client ID")}
            value={clientId}
            onChange={setClientId}
          />
          <Field
            id="oauth-secret"
            label={ui("Client secret")}
            value={clientSecret}
            onChange={setClientSecret}
            type="password"
          />
          <Field
            id="oauth-authorize"
            label={ui("Điểm cuối cấp quyền")}
            value={authorizationEndpoint}
            onChange={setAuthorizationEndpoint}
          />
          <Field
            id="oauth-token"
            label={ui("Điểm cuối lấy token")}
            value={tokenEndpoint}
            onChange={setTokenEndpoint}
          />
          <Field
            id="oauth-revoke"
            label={ui("Điểm cuối thu hồi")}
            value={revocationEndpoint}
            onChange={setRevocationEndpoint}
          />
        </div>
        {error ? (
          <p role="alert" className="font-secondary-body text-status-danger-content">
            {error}
          </p>
        ) : null}
        <div className="flex justify-end gap-2">
          <Button prominence="secondary" onClick={onClose}>
            {ui("Huỷ")}
          </Button>
          <Button disabled={!complete} pending={save.isPending} onClick={() => save.mutate()}>
            {ui("Lưu")}
          </Button>
        </div>
      </div>
    </CatalogDialog>
  );
}

function Field({
  id,
  label,
  value,
  onChange,
  type,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: "password";
}) {
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        type={type}
        autoComplete={type === "password" ? "off" : undefined}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </div>
  );
}
