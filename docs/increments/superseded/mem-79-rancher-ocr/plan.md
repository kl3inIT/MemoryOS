# Deployment plan

- [x] Read MEM-79, inspect repository and identify the existing Jmix team project.
- [x] Inventory namespace quotas and admitted workloads; obtain authorization to reclaim unused quota.
- [x] Save rollback evidence and reallocate quotas through Rancher's namespace annotations.
- [x] Create namespace `jmix-ocr` in `local:p-vs2td`, with requests/limits 8 CPU / 16 GiB.
- [x] Build and publish the pinned Vietnamese OCR image.
- [x] Deploy the private authenticated service and verify scheduling/readiness.
- [x] Exercise Vietnamese/English OCR and denial through the private endpoint.
- [x] Add and verify the requested nip.io ingress hostname.
- [x] Verify existing workloads and document rollback and remaining MEM-79 acceptance.
- [ ] Provision the external server's VPN and verify the caller's network path.

The service is deployed and verified from Windows over VPN. External-server VPN
provisioning and full MEM-79 corpus/indexing acceptance remain incomplete.

## Execution checkpoint, 2026-09-10

Namespace `jmix-ocr` was created by CLI at 11:49:18 UTC directly in
`local:p-vs2td`. Rancher populated the project label and generated quota:
requests/limits 8 CPU and 16 GiB, PVC/storage both zero. Project-owner workload
access succeeds. The inaccessible existing namespace `ocr` was not modified.
Dedicated API-key and GHCR pull Secrets were provisioned without logging values.
Deployment and Service server dry-runs passed and both resources were created;
the pod was scheduled to `iks-node5` (`10.123.123.195`). Its initial image pull
took 3m35.745s, and the Deployment became Available at 11:54:08 UTC. Pod
`docling-56bb8d7d74-qgv7f` is Running, Ready 1/1, with zero restarts.

Service `jmix-ocr/docling` has ClusterIP `10.43.145.41`, port 5001 and NodePort
31079. From Windows over VPN, `http://10.123.123.194:31079/health` returned 200.
Authenticated `POST /v1/convert/source` processed the two-page image-only
Vietnamese/English PDF in **14.09 seconds**, returned HTTP 200/status success,
319 text characters, expected phrases/numbers and page provenance `{1,2}`.
Missing and wrong API keys each returned 401. Cluster output is recorded in
`.tmp/mem79-deploy/ocr-result-cluster.json`; the earlier local output is retained
in `ocr-result-local.json`. This small fixture does not establish large-corpus
capacity or MEM-79 extraction/indexing acceptance.

The requested hostname `http://ocr.10.123.123.194.nip.io` is deployed as nginx
Ingress `jmix-ocr/docling`, routing `/` to Service `docling:5001`. Health returns
200 and the same Vietnamese/English conversion through ingress returns success
in **10.06 seconds**, with missing/wrong-key checks both 401. Evidence is in
`.tmp/mem79-deploy/ocr-result-ingress.json`. Public DNS and `getent` on MemoryOS
resolve the hostname to `10.123.123.194`. Windows' resolver `192.168.1.1` returned
no A record; the HTTP tests therefore explicitly pinned the destination IP and
supplied the nip.io Host header. DNS and hosts-file settings were not changed.
The ingress YAML passed JetBrains inspection, server dry-run and apply; the
affected Gradle compile tasks passed again in 29 seconds.

Runtime UID/GID are 1001/1001. Cgroup limits confirm 8 CPU and 16 GiB memory;
OOM and OOM-kill counts are zero. Pod metrics after the test were 718m CPU and
1326 MiB memory (point-in-time, not peak measurements). Runtime Tesseract lists
eng, osd and vie. The API key is held in Secret `jmix-ocr/docling-api-key`, key
`api-key`; registry pull credentials are in `jmix-ocr/ghcr-pull`.

Rancher lists `jmix-ocr` under Jmix team and reports total allocated requests
16 CPU / 32 GiB, limits 30 CPU / 48 GiB, PVC 19 and storage 84 GiB. These remain
within the unchanged project ceilings.

The user approved execution after the planning checkpoint. Quota reductions have
been applied and re-read from Rancher's generated ResourceQuota objects. Backup
snapshots are in `.tmp/mem79-deploy/quota-before-20260910-171052/`. The 34 existing
Deployment/StatefulSet objects retain their previous desired replica counts;
all 28 desired replicas are still Ready. Post-deployment comparison also confirms
all existing container images are unchanged. Evidence is saved in
`.tmp/mem79-deploy/existing-workloads-after.json`.

Published private image:
`ghcr.io/kl3init/memoryos-docling-vie@sha256:65b6b57695c60168bfa7dd36f7af32e6c47b34902b101b91f3a279b1ed288449`.
The registry index resolves to linux/amd64 manifest
`sha256:dbf024d7a08557c1272d4a038ff4c111d89c42cca046fa08a20e321c19a4e350`.
The runtime contains Docling Serve 1.32.0, docling-slim 2.124.0, and
`tesseract-langpack-vie-4.1.0-3.el9`; `tesseract --list-langs` lists eng, osd, vie.
Existing layout, table, RapidOCR and EasyOCR assets remain in the image.

Local smoke used the actual image with one conversion worker, read-only root,
offline model setting, 2 CPU and a 4 GiB memory limit. A synthetic image-only PDF
with one Vietnamese and one English page completed in **14.04 seconds**, with
HTTP 200/status success, both page-provenance values, expected numeric strings,
and expected Vietnamese/English phrases. Missing and wrong API keys returned
401. This is a small-fixture service check, not the 8/16 cluster capacity or
MEM-79 corpus/indexing acceptance. The local idle-memory observation was about
1.201 GiB; it is not a peak-memory benchmark.

## Original namespace diagnosis

Namespace creation through kubectl and through the CSRF-complete official
Rancher API both reached the namespace admission webhook and were rejected as
Unauthorized. The user then created `ocr` and moved it into Jmix team in the UI.
The old and newly exported kubeconfig both resolve to user `u-bjl26tlaze` and
still receive Forbidden for reading the namespace or creating its workloads.
Project ownership is established through GitHub group `github_team://18926287`
and role `project-owner`; stc-hy has generated `admin` and `project-owner`
RoleBindings for that group. Effective namespace rights still list the old
namespaces but not `ocr`; before creating `jmix-ocr`, the allocated total was 22 CPU /
32 GiB limits and 8 CPU / 16 GiB requests, excluding OCR. Namespace metadata and
Rancher reconciliation must be checked before assigning a specific root cause.

Follow-up CLI diagnosis isolated the original creation failure: a server-side
dry-run for `ocr-cli-validation` with the project annotation and 0-PVC quota
passes without PSA labels, while the identical request adding
`pod-security.kubernetes.io/enforce=restricted` and `enforce-version=v1.33`
is denied by the namespace webhook. The account has `manage-namespaces` on
`local/p-vs2td`, but lacks `updatepsa`. No validation namespace was persisted.
The manifest now leaves namespace PSA policy to Rancher's existing policy;
the Deployment retains its non-root user, seccomp, dropped capabilities,
read-only filesystem and no privilege escalation. A dry-run create for `ocr`
returns AlreadyExists, confirming that the user's namespace exists; its
project association and effective access remain unresolved.
The official namespace `move` action was also attempted with
`projectId=local:p-vs2td` and rejected with 403 while reading `ocr`, before any
move occurred. The authenticated browser's namespace detail page reports
"Resource namespace with id ocr not found, unable to display resource details".
Inspecting or repairing that original namespace requires an operator who can
view it. This no longer blocks the OCR service deployed in `jmix-ocr`.

The exported kubeconfig points to `https://10.123.123.199:30443`, whose server
certificate issuer is `dynamiclistener-ca@1763532182`. Rancher's exported CA is
`dynamiclistener-ca@1763532181`, so TLS validation fails for that direct route.
Checking through the existing valid HTTPS hostname preserves certificate
verification and still returns the same namespace authorization failure. No TLS
verification or admission control was disabled.

## Remaining external-server connectivity

The server `72.62.193.33` has no installed OpenVPN client/service. A post-rollout
request to `http://10.123.123.194:31079/health` timed out after 5 seconds with
curl HTTP 000. Windows has an active OpenVPN interface and the
private cluster route. A dedicated server profile and credentials are needed for
the persistent VPN connection; existing personal credentials have not been
copied to the server. The verified private service endpoint is
`http://10.123.123.194:31079`, authenticated with `X-Api-Key` over the VPN.
The SSH account `dat` also requires a password for `sudo`; a non-interactive
sudo check failed, so client installation cannot yet proceed in that session.

## Source verification

YAML parsing and kubectl client and server dry-runs passed. JetBrains namespace inspection
passed; deployment inspection reports only external Secret/key references,
which are intentionally absent from source. Both the Secret and its required
key were provisioned before rollout and verified by successful authentication.

The required Gradle compile
`gradlew.bat :core:compileJava :connector:compileJava :api:compileJava :worker:compileJava --no-daemon --console=plain`
passed in 18 seconds after the YAML changes. An earlier attempt had failed on an
unrelated Chat `SearchTool` constructor mismatch, which was resolved by ongoing
work outside this deployment. No Java files were edited for OCR. No
repository-wide `clean check` or MEM-79 end-to-end acceptance pass is claimed.

## Earlier planning checkpoint, before execution on 2026-09-10

The user requested the plan and complete service inventory before continuing.
At that checkpoint, no Rancher quota or namespace mutation had been performed,
and the local build had been stopped without publishing an image. The user
subsequently approved execution; the execution checkpoint above supersedes
that earlier pause. See [inventory](inventory.md) for the inspected services
and quota allocation.

## Thirty-minute server timeout correction

The caller's Infisical values were confirmed as `30m` / `1800` / `1810`.
The standalone Rancher manifests still fixed the server at `900` / `910`,
with a 930-second ingress timeout. Infisical does not populate this Deployment's
literal environment values.

- [x] Align the Deployment document/sync budgets to 1800/1810 seconds and
      Uvicorn/pod shutdown budgets to 1820/1830 seconds.
- [x] Align ingress read/send timeouts to 1830 seconds without changing routing.
- [x] Inspect both YAML files, compile, and validate against the target cluster.
- [x] Apply only the OCR timeout changes; verify the replacement container,
      effective Docling settings, and an authenticated scanned-PDF conversion.
- [x] Record runtime evidence and update the deployment runbook.

### Verified runtime correction, 2026-09-11

The live `jmix-ocr/docling` container returned `900` and `910` before the fix.
The synthetic scanned-PDF request with `document_timeout=1800` reproduced HTTP
422: `document_timeout exceeds the configured maximum of 900.0 seconds.`
The existing deployment kubeconfig provided authenticated, TLS-verified Rancher
access; neither Infisical nor Kubernetes credentials were changed.

JetBrains inspections returned no errors or warnings for both modified YAML
files. The checked-in wrapper's `:core:compileJava :connector:compileJava
:api:compileJava :worker:compileJava --no-daemon --console=plain` succeeded in
25 seconds; all four compilation tasks were up-to-date. Kubernetes server-side
dry-run accepted the Deployment and Ingress, with the Service unchanged.

Resource-version-guarded patches changed only the two ingress timeout
annotations, the three Deployment timeout environment values and pod termination
grace. Deployment generation advanced from 5 to 6. The replacement pod
`docling-7c855c6876-bzzjq` became Running/Ready 1/1 with zero restarts.
Container `printenv` returned 1800/1810/1820; ingress read/send settings were
both 1830. Comparing the full Deployment spec against the pre-change snapshot
confirmed no other spec changes; the ingress routing spec was unchanged.

Authenticated conversion through the same ingress with
`document_timeout=1800` returned HTTP 200/status success in 12.09 seconds,
319 text characters, both page-provenance values `{1,2}`, and all expected
Vietnamese/English phrases and numeric strings. A request for 1801 seconds
returned HTTP 422 naming the new 1800-second maximum. Health returned 200;
missing and wrong keys each returned 401.

No image rebuild, quota/storage change, user-document reindex or Infisical
mutation was performed. The smoke reused the existing synthetic fixture and
kept its request and result in memory; no new throwaway files were left behind.
This is not a full thirty-minute load test or external-Worker/indexing acceptance.

## 100 MiB and sixty-minute service limits

The user authorized raising the actual OCR ingress and Docling service limits.
The baseline is Deployment generation 6 with a 20 MiB file cap, 1800/1810-second
service budgets, and a 30 MiB / 1830-second ingress. The caller's Infisical
configuration is already confirmed and is not part of this change.

- [x] Set ingress body size to 150 MiB for a base64-encoded 100 MiB file plus JSON,
      and read/send timeouts to 3630 seconds.
- [x] Set Docling's file maximum to 104857600 bytes, processing/sync budgets to
      3600/3610 seconds, and Uvicorn/pod shutdown budgets to 3620/3630 seconds.
- [x] Inspect both YAML files, compile and validate the target-cluster changes.
- [x] Roll out only the OCR limits, preserving image, routing, resources and Secrets.
- [x] Verify real 100 MiB request admission and OCR through ingress, the
      3600-second request budget, and authentication/upper-limit rejection.
- [x] Record observed limits/results and remove only newly created smoke artifacts.

The synthetic boundary request is not a sixty-minute endurance test or a
large-corpus benchmark. Worker rollout and user-document reindexing must not be
claimed from service-side smoke results.

### Verified large-file rollout

Deployment generation advanced from 6 to 7. Pod `docling-54b48bf589-c2z7k`
became Running/Ready 1/1 with zero restarts. Container `printenv` returned
104857600 / 3600 / 3610 / 3620 for the file, document, sync and Uvicorn budgets.
Ingress body/read/send values are 150m / 3630 / 3630. Resource-version-guarded
patches changed only these approved limits and pod termination grace.
Full spec comparison, normalizing Kubernetes' environment-entry reordering,
confirmed image, resources, Secrets, volumes and other settings were unchanged;
the ingress routing spec was unchanged.

JetBrains found no ingress problems and only two weak external-Secret reference
warnings in the Deployment. `docling-api-key` and its `api-key` entry were
verified live without printing the value; no placeholder Secret was added to
source. Kubernetes server-side dry-run accepted the Deployment/Ingress and left
the Service unchanged. The checked-in wrapper's four compileJava tasks passed
in 9 seconds, all up-to-date. The project account lacks ingress-controller
pod-exec permission, so no direct `nginx -t` execution is claimed.

The generated valid one-page PDF was exactly 104857600 bytes, primarily a
104430000-byte uncompressed RGB raster containing Vietnamese/English text.
Base64 JSON length was 139810458 bytes (133.334 MiB). Through the actual ingress
with a requested 3600-second budget, it returned HTTP 200/status success in
47.0 seconds, 276 text characters, expected phrases and both numeric formats,
and page provenance `{1}`. Authentication denial remained 401 for missing and
wrong keys. A 3601-second budget returned HTTP 422 naming the 3600-second maximum.

Appending one legal trailing whitespace byte to the PDF inside the request
exercised the exact upper boundary. Docling's worker log confirmed:
`File size 104857601 exceeds the max_file_size limit of 104857600 bytes.`
The HTTP response was 404 with `Task result not found. Please wait for a
completion status.` after 30.23 seconds. The size limit is enforced, but this
upstream error-reporting behavior is not a clean file-size HTTP response and
was not changed by this configuration-only rollout. Health remained 200.

The generated PDF, JSON request and their dedicated scratch directory were
removed. Existing smoke files, images, credentials, Infisical settings and
application deployments were preserved. No Worker rollout or user-data reindex
was performed; no sixty-minute endurance or large-corpus acceptance is claimed.
