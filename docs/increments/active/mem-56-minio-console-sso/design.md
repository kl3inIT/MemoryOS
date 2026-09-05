# MEM-56 design: owner-only MinIO Console with Keycloak SSO

## Outcome

Staging exposes the existing MinIO Console at `https://memoryos-minio.72-62-193-33.nip.io` so the deployment owner can inspect FILE objects without receiving MinIO root, API, or worker credentials. The Console uses MinIO's native OpenID Connect Authorization Code flow against the existing `memoryos` Keycloak realm.

This is an operator inspection surface, not a MemoryOS product route. Production remains unchanged and contains no public Console contract.

## Authorization boundary

The existing realm-local `memoryos-inspector` role remains assigned to exactly one identity: the reconciled initial owner. A dedicated confidential Keycloak client, `memoryos-minio-console`, has `fullScopeAllowed=false` and only that realm role in its client scope. A realm-role protocol mapper emits the client-scoped role names into the MinIO `policy` claim in ID and access tokens.

MinIO uses claim-based authorization with `MINIO_IDENTITY_OPENID_CLAIM_NAME=policy`. It does not use `role_policy`: MinIO's STS endpoint is part of the public S3 origin, and a provider-wide role policy would give every authenticated realm user the same storage policy even when they bypass the Console URL.

The MinIO bootstrap reconciles a policy named `memoryos-inspector`, matching the only emitted claim value. It permits listing buckets, reading the configured bucket location, listing the configured bucket, and reading objects in that bucket. It grants no write, delete, bucket-management, IAM, policy, or server-administration permission. Because the pinned MinIO release otherwise lets an authenticated identity create service accounts for itself without an explicit allow, the policy explicitly denies create/list/update/remove service-account actions so an expiring SSO session cannot mint a persistent credential. An authenticated user without the matching claim receives no usable MinIO policy.

## Runtime and secret boundary

MinIO already runs its Console on container port `9001`. Staging routes the dedicated HTTPS origin through Nginx Proxy Manager directly to `memoryos-minio:9001` on the shared proxy network. Port `9001` receives no host binding. The existing object origin continues to route to port `9000`; presigned upload hosts, CORS, CSP, API/worker credentials, and bucket privacy do not change.

The base MinIO entrypoint always reads the existing root password from its mounted file. It reads and exports the OIDC client secret only when a staging overlay supplies an OIDC discovery URL and a mounted secret-file path. The staging overlay owns all OIDC settings and secret mounting, so the production overlay neither requires the secret nor enables OIDC or exposes the Console.

The public Console URL is the fixed MinIO browser redirect URL, producing the exact Keycloak callback `https://memoryos-minio.72-62-193-33.nip.io/oauth_callback`. The client permits only that callback, uses a confidential client secret, disables direct grants and service accounts, and retains no wildcard redirect or web origin. The pinned MinIO Console's authorization request does not emit a PKCE `code_challenge`, so this client deliberately does not require PKCE; the confidential secret, exact callback, OIDC state validation, and claim-based authorization remain mandatory.

## Provisioning and convergence

The staging inspection-secret provisioner generates the Console OIDC client secret idempotently with mode `0600`. Controlled Keycloak reconciliation loads that value without printing it, reconciles the exact client and mapper, grants only `memoryos-inspector` into the client's realm-role scope, and verifies that no other scoped realm role remains.

The MinIO bootstrap creates or updates the bucket-scoped `memoryos-inspector` policy, including the explicit service-account deny, on every run before authenticating the existing API and worker identities. No long-lived MinIO user is created for the human owner; MinIO exchanges the Keycloak token for expiring STS credentials.

## Verification

Verification must prove:

- base+staging Compose renders OIDC, the Console secret mount, and no host binding for port `9001`;
- base+production renders no OIDC configuration, Console secret mount, or Console host binding;
- secret provisioning and MinIO policy/bootstrap replay idempotently;
- Keycloak reconciliation produces the exact confidential client and callback without a PKCE requirement, plus the single realm-role scope and `policy` mapper;
- the owner token includes only the `memoryos-inspector` policy value and can list/read but cannot put/delete/administer or create service accounts;
- an ordinary verified realm user has no policy claim and cannot obtain usable STS storage access;
- a real browser completes MinIO Console SSO and can inspect an uploaded FILE object;
- the same browser cannot upload or delete through the Console;
- the existing browser-direct FILE upload/finalize/index/remove flow remains healthy.

The Console is not accepted until the user visually verifies the deployed PR head. Merge follows explicit approval only.

## Non-goals

- Exposing MinIO root credentials or the raw Console port.
- Giving Console users write, delete, policy, IAM, service-account, or server-admin capabilities.
- Replacing the MemoryOS Sources UI with MinIO Console.
- Publishing MinIO Console as a production requirement.
- Adding a second OAuth2 Proxy in front of MinIO's native OIDC flow.
