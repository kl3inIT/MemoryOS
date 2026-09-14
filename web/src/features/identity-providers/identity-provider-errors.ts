import type { ErrorMessage } from "@/lib/problem-presentation";

export const identityProviderMessages = {
  IDP_ALIAS_CONFLICT: { key: "idpAliasConflict" },
  IDP_DISCOVERY_FAILED: { key: "idpDiscoveryFailed" },
} satisfies Record<string, ErrorMessage>;
