# MEM-56 verification

## Local configuration and provisioning

- `sh -n` passed for the changed MinIO launcher/bootstrap, staging inspection-secret provisioner, and Keycloak reconciliation script.
- `jq empty` passed for the MinIO Console client and policy-claim mapper JSON.
- Base+staging and base+production Compose combinations rendered successfully with validation-only values. Staging rendered native OIDC, the file-backed client-secret mount, the fixed browser redirect, the internal Console-to-S3 URL, and only the existing loopback port `9000` binding. Production rendered no OIDC variables or Console secret mount and no port `9001` binding.
- The staging secret provisioner ran twice against isolated directories. The first run created `minio-console-oidc-client-secret.txt`; the second replay preserved a complete set and succeeded.
- The pinned MinIO/`mc` images ran the bootstrap twice. Both runs kept the bucket private, reconciled API/worker users and policies, created the `memoryos-inspector` policy, and authenticated the existing service identities against the readiness sentinel.
- `mc admin policy info` returned exactly: account-level `s3:ListAllMyBuckets`, bucket-level `s3:GetBucketLocation`/`s3:ListBucket`, and object-level `s3:GetObject`. No write, delete, bucket mutation, IAM, policy, service-account, or server-admin action was present.

## Keycloak and native Console behavior

- The repository-pinned Keycloak image and PostgreSQL image created an isolated `memoryos` realm. The reconciliation script ran once from empty state and replayed successfully. Replay reported the MinIO Console mapper unchanged and retained exactly one owner-only `memoryos-inspector` realm assignment and one client-scoped realm role.
- The reconciled `memoryos-minio-console` client is confidential, exact-callback, standard-flow-only, and has direct grants, implicit flow, and service accounts disabled. Its realm-role mapper emits client-scoped roles as the multivalued `policy` claim in ID and access tokens.
- Runtime inspection found a real pinned-Console incompatibility with the initial design: its authorization request contains no `code_challenge`. The client and durable contract were corrected to confidential Authorization Code without a Keycloak PKCE requirement; exact callback, client secret, OIDC state, and claim-based authorization remain mandatory.
- An isolated Chromium flow selected `MemoryOS SSO`, authenticated the owner through Keycloak, returned to `/oauth_callback`, and landed on MinIO `/browser` with the `memoryos` bucket visible.
- An owner ID token contained `policy: ["memoryos-inspector"]`; an ordinary verified realm user token contained no `policy` claim. Both tokens had the exact `memoryos-minio-console` audience.
- MinIO STS issued bounded temporary credentials to the owner. The ordinary user received `InvalidParameterValue: policy claim missing from the JWT token, credentials will not be generated`.
- With the owner's OIDC-derived STS credentials, `mc ls` listed the `memoryos` bucket and `mc cat` read `system/readiness`. `PutObject`, bucket creation, object deletion, and service-account creation each returned Access Denied.

## Remaining staging gate

JetBrains inspected all nine changed deployment/configuration files with warnings enabled and reported no findings; the final staging Compose scope correction was re-inspected clean. `./gradlew.bat clean check --no-daemon` passed all 23 actionable repository tasks, and `git diff --check` passed.

Publication, live Keycloak reconciliation, Nginx Proxy Manager host creation, deployed owner/non-owner authorization, unchanged FILE upload, and user visual acceptance remain pending.
