/**
 * Administration of upstream OIDC identity providers on the configured Keycloak realm and the
 * durable JIT admission allowlist. Keycloak remains the source of truth for provider configuration;
 * PostgreSQL owns only the allowlist and its audit fields.
 */
package io.memoryos.iam.identityprovider;
