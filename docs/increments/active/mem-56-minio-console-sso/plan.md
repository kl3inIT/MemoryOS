# MEM-56 implementation plan: owner-only MinIO Console with Keycloak SSO

## Increment reconciliation

- [x] Confirm MEM-52 is accepted, merged, deployed, and Done in Linear.
- [x] Move the MEM-52 increment record from active to completed and reconcile roadmap links.
- [x] Create Linear MEM-56 as an active follow-up owned by the existing project.

## Identity and authorization

- [x] Add the exact `memoryos-minio-console` confidential Keycloak client.
- [x] Add a client-scoped realm-role mapper that emits only `memoryos-inspector` as the MinIO `policy` claim.
- [x] Reconcile the client secret, exact callback, native non-PKCE Console contract, owner-only role assignment, and single-role client scope.
- [x] Provision a bucket-scoped read-only MinIO policy named `memoryos-inspector` with explicit service-account denial and no write, delete, IAM, or admin access.

## Runtime

- [x] Replace the inline MinIO launcher with a secret-file-aware entrypoint.
- [x] Enable MinIO native OIDC only in the staging overlay.
- [x] Mount the Console client secret only into staging MinIO.
- [x] Document the dedicated HTTPS Console origin and Nginx Proxy Manager route to container port `9001` without a host binding.
- [x] Preserve the existing port `9000` object origin, presigned upload contract, bucket privacy, and API/worker identities.

## Verification

- [x] Validate shell and JSON syntax and render base+staging and base+production Compose combinations.
- [x] Exercise secret and MinIO bootstrap replay against the pinned images.
- [x] Reconcile a real Keycloak realm and inspect client, mapper, scoped role, owner claim, and ordinary-user claim.
- [x] Prove owner read-only and ordinary-user denied behavior through MinIO STS/S3.
- [x] Inspect every changed IDE-supported file with JetBrains warnings enabled, then run the repository gate.
- [ ] Deploy the reviewed PR head and exercise native Console SSO plus the unchanged FILE Source path in a real browser.

## Delivery

- [ ] Publish the PR and attach it to MEM-56.
- [ ] Obtain explicit user visual approval before merge.
- [ ] Merge only the reviewed exact head, verify exact-SHA CI, deploy exact merge SHA, and complete the increment records.
