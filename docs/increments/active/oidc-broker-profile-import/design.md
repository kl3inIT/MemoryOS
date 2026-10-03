# OIDC broker profile import

## Requirement

An upstream standards-compliant OIDC provider, including Keycloak and Google, supplies its available email, first name, last name and email-verification state to the MemoryOS Keycloak broker without asking an administrator to enter provider-specific scopes. Missing claims remain absent; MemoryOS does not fabricate profile values or mark an unverified email as verified.

## Design

Every generic OIDC provider MemoryOS creates or updates requests the fixed standard scope set `openid email profile`. Keycloak's generic OIDC broker maps `email`, `given_name` and `family_name` from the validated upstream token/UserInfo response. Existing `trustEmail=true`, signature validation, JWKS validation and `IMPORT` sync remain in force; Keycloak therefore persists the upstream `email_verified` Boolean when it is supplied and does not infer it from the email string.

The scope set is intentional shared behavior for all supported generic OIDC providers, not a provider-specific UI field. A provider that does not implement standard OIDC profile claims may leave these fields absent. First-broker-login form policy and realm-wide local-account email verification are separate work and do not change here.

## Application profile presentation

After a successful brokered sign-in, MemoryOS records the Keycloak `name` claim when present. If it is absent, it forms the display name from the nonblank standard `given_name` and `family_name` claims, preserving their order and leaving the value absent when neither exists. `GET /api/identity/me` returns that latest profile display name, and the account menu displays it in place of the Tenant name. The Tenant name remains organization context only; it is not a substitute for a person’s identity.

## Existing providers

A provider already stored in Keycloak receives the standard scope set on its next authorized MemoryOS provider update. No unaudited startup mutation changes Keycloak's administrator-managed provider state.
