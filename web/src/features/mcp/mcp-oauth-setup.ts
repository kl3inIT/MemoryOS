import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  discoverMcpServerOAuthMutation,
  disconnectMcpServerOAuthMutation,
  deleteMcpServerOAuthClientMutation,
  listMcpServerOAuthClientsOptions,
  registerMcpServerOAuthClientMutation,
  startMcpServerOAuthAuthorizationMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { McpOAuthAuthorizationServer, McpServerView } from "@/lib/hey-api/types.gen";
import { invalidateMcpServers } from "./mcp-servers";

/** What the manual form starts from: empty, or an authorization server discovery found. */
export type ClientDraft = {
  issuer?: string;
  authorizationEndpoint?: string;
  tokenEndpoint?: string;
  revocationEndpoint?: string;
};

/**
 * The OAuth applications of one server and the actions on them, shared by the server card's next step and its
 * details. Adding one discovers the authorization server first when the server allows it: a server that accepts
 * MemoryOS's client metadata document or registration needs no typing, and one that does not opens the manual
 * form with the discovered endpoints filled in, so only the client ID and secret remain.
 */
export function useOAuthSetup(server: McpServerView) {
  const cache = useQueryClient();
  const path = { serverId: server.id };
  const oauth = server.authType === "OAUTH";
  const clients = useQuery({ ...listMcpServerOAuthClientsOptions({ path }), enabled: oauth });
  const [manual, setManual] = useState<ClientDraft | null>(null);
  const onSuccess = () => invalidateMcpServers(cache, server.id);
  const discovery = useMutation({
    ...discoverMcpServerOAuthMutation(),
    onSuccess: (found) => {
      const first = found.authorizationServers[0];
      if (!found.authorizationServers.some(registrable)) {
        setManual(first ? fromDiscovery(first) : {});
      }
    },
  });
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

  return {
    path,
    clients,
    discovery,
    register,
    remove,
    connect,
    disconnect,
    manual,
    /** Starts adding an application: discovery where the server allows it, otherwise the manual form. */
    begin: () =>
      server.oauthProviderMode === "AUTO_DISCOVERY" ? discovery.mutate({ path }) : setManual({}),
    openManual: (draft: ClientDraft) => setManual(draft),
    closeManual: () => setManual(null),
    saved: async () => {
      setManual(null);
      discovery.reset();
      await onSuccess();
    },
  };
}

export type OAuthSetup = ReturnType<typeof useOAuthSetup>;

export function registrable(server: McpOAuthAuthorizationServer) {
  return server.metadataDocumentAvailable || server.registrationAvailable;
}

export function fromDiscovery(server: McpOAuthAuthorizationServer): ClientDraft {
  return {
    issuer: server.issuer,
    authorizationEndpoint: server.authorizationEndpoint,
    tokenEndpoint: server.tokenEndpoint,
    revocationEndpoint: server.revocationEndpoint ?? "",
  };
}
