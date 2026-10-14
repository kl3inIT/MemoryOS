import { useState } from "react";
import { revalidateLogic, useStore } from "@tanstack/react-form";
import { useMutation } from "@tanstack/react-query";
import { z } from "zod";
import { useAppForm } from "@/components/form/app-form";
import { CatalogDialog } from "@/components/composites/catalog-dialog";
import { SectionHeader } from "@/components/composites/section-header";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { ExternalLink, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { StatusBadge } from "@/components/ui/status-badge";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { createMcpServerOAuthClientMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import type {
  McpOAuthAuthorizationServer,
  McpOAuthClientView,
  McpServerView,
} from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { catalogEntryOf } from "./mcp-catalog";
import { type ClientDraft, fromDiscovery, type OAuthSetup, registrable } from "./mcp-oauth-setup";
import { RedirectUri } from "./mcp-server-editor";

/**
 * The OAuth applications of one server. A Tenant may hold several: a Google `Internal` app authorizes only its own
 * Workspace, so each organization keeps its own application and people pick one by its label when connecting.
 */
export function McpOAuthClients({ server, setup }: { server: McpServerView; setup: OAuthSetup }) {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const [removing, setRemoving] = useState<McpOAuthClientView | null>(null);
  const [endingShared, setEndingShared] = useState(false);
  const { path, clients, discovery, register, connect } = setup;
  // One feedback line: the most recent failed action.
  const failure = [discovery, register, connect]
    .filter((mutation) => mutation.isError)
    .sort((a, b) => b.submittedAt - a.submittedAt)[0]?.error;
  const shared = server.authPerformer === "ADMIN";

  return (
    <div className="flex flex-col gap-3">
      <SectionHeader
        level="group"
        title={ui("Ứng dụng OAuth")}
        actions={
          // With none yet, adding the first is the card's own next step.
          clients.data?.length && !discovery.data ? (
            <Button
              prominence="secondary"
              size="sm"
              pending={discovery.isPending}
              onClick={setup.begin}
            >
              {ui("Thêm ứng dụng OAuth")}
            </Button>
          ) : null
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

      {discovery.data?.authorizationServers.some(registrable) ? (
        <AutomaticRegistration
          servers={discovery.data.authorizationServers.filter(registrable)}
          defaultLabel={server.name}
          pending={register.isPending}
          onRegister={(issuer, label, source) =>
            register.mutate({ path, body: { issuer, label, source } })
          }
          onManual={() => {
            const first = discovery.data?.authorizationServers[0];
            setup.openManual(first ? fromDiscovery(first) : {});
            discovery.reset();
          }}
          onDismiss={() => discovery.reset()}
        />
      ) : null}

      {clients.data && clients.data.length === 0 && !discovery.data ? (
        <FieldDescription>{ui("Chưa có ứng dụng OAuth nào.")}</FieldDescription>
      ) : null}

      <ul className="flex flex-col gap-2">
        {(clients.data ?? []).map((client) => (
          <li key={client.id} className="flex flex-wrap items-start justify-between gap-2">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-main-ui-body text-content-primary">{client.label}</span>
                <StatusBadge tone="neutral">
                  {ui(client.source === "ADMIN" ? "Nhập tay" : "Tự động")}
                </StatusBadge>
              </div>
              <p className="font-secondary-body break-all text-content-muted">{client.issuer}</p>
            </div>
            <div className="flex gap-2">
              {shared ? (
                <Button
                  prominence="secondary"
                  size="sm"
                  pending={connect.isPending && connect.variables?.body.oauthClientId === client.id}
                  onClick={() => connect.mutate({ path, body: { oauthClientId: client.id } })}
                >
                  {ui(server.sharedCredentialConfigured ? "Kết nối lại" : "Kết nối")}
                </Button>
              ) : null}
              <IconButton
                prominence="internal"
                size="sm"
                aria-label={ui("Xóa {{name}}", { name: client.label })}
                onClick={() => setRemoving(client)}
              >
                <Trash2 />
              </IconButton>
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
          onClick={() => setEndingShared(true)}
        >
          {ui("Ngắt kết nối dùng chung")}
        </Button>
      ) : null}

      {setup.manual ? (
        <McpOAuthClientEditor
          serverId={server.id}
          setupUrl={catalogEntryOf(server.url)?.setupUrl}
          defaultLabel={server.name}
          draft={setup.manual}
          onClose={setup.closeManual}
          onSaved={setup.saved}
        />
      ) : null}

      {removing ? (
        <ConfirmDialog
          open
          title={ui("Xóa ứng dụng {{label}}?", { label: removing.label })}
          description={ui(
            "Mọi kết nối tạo bằng ứng dụng này sẽ bị xóa; người đã kết nối phải kết nối lại.",
          )}
          confirmLabel={ui("Xóa")}
          pendingLabel={ui("Đang xóa")}
          confirmTone="danger"
          onConfirm={async () => {
            await setup.remove.mutateAsync({
              path: { ...path, clientId: removing.id },
              query: { revision: removing.revision },
            });
          }}
          errorMessage={(error) => presentProblem(error, "mutation").message}
          onOpenChange={(open) => {
            if (!open) setRemoving(null);
          }}
        />
      ) : null}

      {endingShared ? (
        <ConfirmDialog
          open
          title={ui("Ngắt kết nối dùng chung?")}
          description={ui(
            "Công cụ của {{name}} sẽ ngừng chạy cho mọi người cho đến khi kết nối lại.",
            {
              name: server.name,
            },
          )}
          confirmLabel={ui("Ngắt kết nối")}
          pendingLabel={ui("Đang ngắt")}
          confirmTone="danger"
          onConfirm={async () => {
            await setup.disconnect.mutateAsync({ path });
          }}
          errorMessage={(error) => presentProblem(error, "mutation").message}
          onOpenChange={(open) => {
            if (!open) setEndingShared(false);
          }}
        />
      ) : null}
    </div>
  );
}

/**
 * The authorization server accepts MemoryOS without a pasted client: the client metadata document is preferred,
 * then dynamic registration, as Onyx's default discovery does.
 */
function AutomaticRegistration({
  servers,
  defaultLabel,
  pending,
  onRegister,
  onManual,
  onDismiss,
}: {
  servers: McpOAuthAuthorizationServer[];
  defaultLabel: string;
  pending: boolean;
  onRegister: (issuer: string, label: string, source: "REGISTERED" | "METADATA_DOCUMENT") => void;
  onManual: () => void;
  onDismiss: () => void;
}) {
  const ui = useAppTranslation();
  const [label, setLabel] = useState(defaultLabel);
  const [issuer, setIssuer] = useState(servers[0]?.issuer ?? "");
  const chosen = servers.find((server) => server.issuer === issuer) ?? servers[0];
  return (
    <Card size="sm">
      <CardContent>
        <div className="flex flex-col gap-3">
          <Field>
            <FieldLabel htmlFor="mcp-discovered-label">{ui("Nhãn")}</FieldLabel>
            <Input
              id="mcp-discovered-label"
              value={label}
              onChange={(event) => setLabel(event.target.value)}
            />
          </Field>
          {servers.length > 1 ? (
            <div className="flex flex-col gap-1">
              {servers.map((server) => (
                <label key={server.issuer} className="flex items-center gap-2 font-secondary-body">
                  <input
                    type="radio"
                    name="mcp-issuer"
                    checked={server.issuer === chosen?.issuer}
                    onChange={() => setIssuer(server.issuer)}
                  />
                  <span className="break-all">{server.issuer}</span>
                </label>
              ))}
            </div>
          ) : (
            <p className="font-secondary-body break-all text-content-muted">{chosen?.issuer}</p>
          )}
          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              disabled={!chosen || label.trim() === "" || pending}
              pending={pending}
              onClick={() =>
                chosen &&
                onRegister(
                  chosen.issuer,
                  label.trim(),
                  chosen.metadataDocumentAvailable ? "METADATA_DOCUMENT" : "REGISTERED",
                )
              }
            >
              {ui("Tạo tự động")}
            </Button>
            <Button size="sm" prominence="secondary" onClick={onManual}>
              {ui("Nhập Client ID")}
            </Button>
            <Button size="sm" prominence="internal" onClick={onDismiss}>
              {ui("Hủy")}
            </Button>
          </div>
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

type ClientValues = z.input<typeof clientSchema>;

/**
 * An application the administrator pasted, for example a Google `Internal` Web client. Endpoints found by
 * discovery are filled in and folded away, so the form asks only what the provider's console shows.
 */
function McpOAuthClientEditor({
  serverId,
  setupUrl,
  defaultLabel,
  draft,
  onClose,
  onSaved,
}: {
  serverId: string;
  /** The provider's console where the application is created, for a catalog server. */
  setupUrl?: string;
  defaultLabel: string;
  draft: ClientDraft;
  onClose: () => void;
  onSaved: () => void;
}) {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const discovered = draft.issuer !== undefined;
  // The request carries the client secret, so the finished mutation is not kept in the cache.
  const save = useMutation({
    ...createMcpServerOAuthClientMutation(),
    gcTime: 0,
    onSuccess: onSaved,
  });
  const form = useAppForm({
    defaultValues: {
      label: defaultLabel,
      issuer: draft.issuer ?? "",
      clientId: "",
      clientSecret: "",
      authorizationEndpoint: draft.authorizationEndpoint ?? "",
      tokenEndpoint: draft.tokenEndpoint ?? "",
      revocationEndpoint: draft.revocationEndpoint ?? "",
    } satisfies ClientValues,
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
  const [showEndpoints, setShowEndpoints] = useState(!discovered);

  return (
    <CatalogDialog
      title={ui("Nhập ứng dụng OAuth")}
      description={ui("Bí mật được mã hóa và không hiện lại.")}
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
          {setupUrl ? (
            <p className="font-secondary-body text-content-secondary">
              <a
                href={setupUrl}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-1 text-content-primary underline underline-offset-4"
              >
                {ui("Tạo ứng dụng OAuth ở trang của nhà cung cấp")}
                <ExternalLink className="size-3.5" aria-hidden="true" />
              </a>
            </p>
          ) : null}
          <RedirectUri />
          <form.AppField name="label">
            {(field) => <field.TextField label={ui("Nhãn")} />}
          </form.AppField>
          <form.AppField name="clientId">
            {(field) => <field.TextField label={ui("Client ID")} autoComplete="off" />}
          </form.AppField>
          <form.AppField name="clientSecret">
            {(field) => (
              <field.TextField
                label={ui("Client secret")}
                type="password"
                autoComplete="off"
                optional
              />
            )}
          </form.AppField>
          {showEndpoints ? (
            <>
              <form.AppField name="issuer">
                {(field) => <field.TextField label={ui("Issuer")} />}
              </form.AppField>
              <form.AppField name="authorizationEndpoint">
                {(field) => <field.TextField label={ui("Điểm cuối cấp quyền")} />}
              </form.AppField>
              <form.AppField name="tokenEndpoint">
                {(field) => <field.TextField label={ui("Điểm cuối lấy token")} />}
              </form.AppField>
              <form.AppField name="revocationEndpoint">
                {(field) => <field.TextField label={ui("Điểm cuối thu hồi")} optional />}
              </form.AppField>
            </>
          ) : (
            <Button
              size="sm"
              prominence="internal"
              className="self-start"
              onClick={() => setShowEndpoints(true)}
            >
              {ui("Xem điểm cuối đã dò")}
            </Button>
          )}
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
            {ui("Hủy")}
          </Button>
          <Button type="submit" disabled={!complete} pending={save.isPending}>
            {ui("Lưu")}
          </Button>
        </div>
      </form>
    </CatalogDialog>
  );
}
