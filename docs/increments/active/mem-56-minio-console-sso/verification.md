# MEM-56 verification

## Local configuration and provisioning

- `sh -n` passed for the changed MinIO launcher/bootstrap, staging inspection-secret provisioner, and Keycloak reconciliation script.
- `jq empty` passed for the MinIO Console client and policy-claim mapper JSON.
- Base+staging and base+production Compose combinations rendered successfully with validation-only values. Staging rendered native OIDC, the file-backed client-secret mount, the fixed browser redirect, the internal Console-to-S3 URL, and only the existing loopback port `9000` binding. Production rendered no OIDC variables or Console secret mount and no port `9001` binding.
- The staging secret provisioner ran twice against isolated directories. The first run created `minio-console-oidc-client-secret.txt`; the second replay preserved a complete set and succeeded.
- The pinned MinIO/`mc` images ran the bootstrap twice. Both runs kept the bucket private, reconciled API/worker users and policies, created the `memoryos-inspector` policy, and authenticated the existing service identities against the readiness sentinel.
- `mc admin policy info` returned exactly three allow boundaries: account-level `s3:ListAllMyBuckets`, bucket-level `s3:GetBucketLocation`/`s3:ListBucket`, and object-level `s3:GetObject`. The final policy also explicitly denies `admin:CreateServiceAccount`, `admin:ListServiceAccounts`, `admin:RemoveServiceAccount`, and `admin:UpdateServiceAccount`; it grants no write, delete, bucket mutation, IAM, policy, or server-admin action.

## Keycloak and native Console behavior

- The repository-pinned Keycloak image and PostgreSQL image created an isolated `memoryos` realm. The reconciliation script ran once from empty state and replayed successfully. Replay reported the MinIO Console mapper unchanged and retained exactly one owner-only `memoryos-inspector` realm assignment and one client-scoped realm role.
- The reconciled `memoryos-minio-console` client is confidential, exact-callback, standard-flow-only, and has direct grants, implicit flow, and service accounts disabled. Its realm-role mapper emits client-scoped roles as the multivalued `policy` claim in ID and access tokens.
- Runtime inspection found a real pinned-Console incompatibility with the initial design: its authorization request contains no `code_challenge`. The client and durable contract were corrected to confidential Authorization Code without a Keycloak PKCE requirement; exact callback, client secret, OIDC state, and claim-based authorization remain mandatory.
- An isolated Chromium flow selected `MemoryOS SSO`, authenticated the owner through Keycloak, returned to `/oauth_callback`, and landed on MinIO `/browser` with the `memoryos` bucket visible.
- An owner ID token contained `policy: ["memoryos-inspector"]`; an ordinary verified realm user token contained no `policy` claim. Both tokens had the exact `memoryos-minio-console` audience.
- MinIO STS issued bounded temporary credentials to the owner. The ordinary user received `InvalidParameterValue: policy claim missing from the JWT token, credentials will not be generated`.
- With the owner's OIDC-derived STS credentials, `mc ls` listed the `memoryos` bucket and `mc cat` read `system/readiness`. `PutObject`, bucket creation, object deletion, and service-account creation each returned Access Denied.

## Staging PR-head deployment

- PR #72 head `559c543e24b4c83644e9ef6f663af702ed96ad83` passed all four GitHub checks. That head was prepared in an isolated release worktree, its staging secret provisioner created the file-backed Console client secret, and its Keycloak reconciler produced the exact client, secret, callback, single-role scope, policy mapper, and owner-only realm-role assignment.
- Staging MinIO was recreated from the PR-head Compose pair without recreating unrelated services. It became healthy with internal `MINIO_SERVER_URL`, exact public redirect/discovery/client/claim settings, a file-only client secret, no host binding for port `9001`, and no OpenID initialization error. The bootstrap replayed successfully and the existing API and worker identities retained sentinel access.
- Nginx Proxy Manager now routes `https://memoryos-minio.72-62-193-33.nip.io` to `memoryos-minio:9001` with WebSockets, HTTP/2, exploit blocking, forced HTTPS, HSTS, and exact-domain Let's Encrypt certificate `npm-30`. HTTP returns `301`, HTTPS validates and returns MinIO Console, and the database backup before this change is `/apps/nginx-proxy-manager/npm-pre-mem56-559c543e.sql`.
- Public Chromium completed owner SSO and displayed the `memoryos` bucket. It listed and downloaded an indexed `text/plain` FILE object with HTTP `200`; upload, path creation, object deletion, and policy/configuration controls were disabled.
- The first live access-key attempt exposed a pinned-MinIO behavior missed by the isolated CLI probe: an authenticated identity could create a service account for itself even though no service-account allow existed. The generated credential was removed immediately. `memoryos-inspector` was corrected with explicit service-account denies; the same direct Console mutation then returned `403`, and the access-key create control became disabled.
- A temporary verified realm user with no inspector role completed Keycloak authentication but received no Console bucket access; the user was deleted after the negative check. The ordinary-user claim and STS denial remain as recorded above.
- The unchanged MemoryOS browser flow uploaded a 35-byte FILE directly, reached `INDEXED`, and exposed the resulting object to the read-only Console. Removal completed asynchronously, the Source returned to one indexed document, and the temporary object disappeared. Restarting the staging worker recovered its Redis consumers; no post-restart transport-unavailable warning was observed.

The reviewed explicit-deny amendment still requires final PR-head deployment. User visual acceptance and merge remain pending.
