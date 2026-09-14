# Standalone Vietnamese/English OCR

The deployment targets namespace `jmix-ocr` in Rancher project `local:p-vs2td`
(Jmix team). Its requests and limits are both 8 CPU / 16 GiB, with one replica
and a Recreate update strategy. Existing project workloads keep their images,
replicas and storage. See the [deployment record](../../../docs/increments/active/mem-79-rancher-ocr/plan.md)
for the actual rollout state; checked-in manifests do not establish deployment.

## Image and request contract

`Dockerfile` derives from the repository's pinned Docling Serve CPU 1.32.0,
adds the pinned Vietnamese Tesseract language package, and restricts Tesseract
CLI threading through the wrapper. It retains upstream model assets and starts
the owned `memoryos_docling` service composition.
`deployment.yaml` pins the published private registry digest, not a mutable tag.

The service accepts file bytes and in-body results, up to 100 MiB and 200 pages,
with 3600-second document processing and a 3610-second synchronous wait. Supply
the API key in `X-Api-Key`. For the tested scanned-PDF request, choose
`ocr_engine=tesseract`, `ocr_lang=["vie","eng"]`, `do_ocr=true`, and
`force_ocr=true`. Mixed native PDFs should choose force_ocr deliberately.

Worker supports configured OCR, the API-key header and a bounded asynchronous
Source observer; see the [ingestion contract](../../../docs/specs/ingestion.md).
Building this image does not update an endpoint, engine revision or deployment.

## Pre-layout orientation

The local-engine service keeps upstream conversion routes, authentication,
admission, options and task handling. UI mode and non-local engines fail startup.
Standard PDF conversions with OCR enabled use the requested PDF backend behind
an orientation wrapper; other formats and pipelines retain upstream behavior.

Pages containing native text are left alone. Raster analysis has a 1600-pixel
maximum edge and a five-second limit per Tesseract process. The cooperative
preprocessing budget is at most thirty seconds, further limited by the requested
document timeout; native PDFium work is not forcibly interrupted. A proposed
quarter-turn is accepted only when both disjoint page halves report upright
after that turn. Missing, conflicting or timed-out evidence abstains.

Only a temporary normalized PDF changes rotation metadata. Original source
bytes remain untouched; the temporary file must fit admission bounds and is
removed on backend unload. `body.meta.memoryos__orientation` records source-frame
provenance and is retained as canonical `page_orientation`. Corrected layout does
not prove period ancestry or financial digits; see the
[Document contract](../../../docs/specs/document.md).

Local build and regression verification, without publishing or deploying:

```powershell
docker build -t memoryos-docling:orientation-candidate infrastructure/deployment/ocr
$tests = (Resolve-Path infrastructure/deployment/ocr/tests).Path
docker run --rm --network none --entrypoint python --mount "type=bind,source=$tests,target=/tests,readonly" memoryos-docling:orientation-candidate -m unittest discover -s /tests -v
```

The checked-in deployment digest is intentionally unchanged. Selecting a new
published image and its matching caller engine revision requires separate
deployment authorization.

## Apply

Use a kubeconfig with verified TLS and permission to manage `jmix-ocr`. Confirm the
namespace's project association and generated quota before applying the workload.
Rancher may require namespace creation or assignment through its UI. The
namespace declaration is in `namespace.yaml`; do not overwrite an existing
namespace's unrelated metadata.
The namespace manifest leaves Pod Security admission labels to Rancher's
existing policy: this project's owner can manage namespaces but cannot change
PSA labels (`updatepsa`). The pod's own security settings are explicit in the
Deployment.

Provision these Secrets in `jmix-ocr` through the deployment's secret management:

- `ghcr-pull`: `kubernetes.io/dockerconfigjson`, registry pull credentials for
  the private `ghcr.io/kl3init/memoryos-docling-vie` image.
- `docling-api-key`: key `api-key`, a cryptographically random service key.

Never place credential values in these manifests, image layers, issues or logs.
After provisioning the Secrets:

```powershell
kubectl --kubeconfig $ocrKubeconfig apply --dry-run=server -f infrastructure/deployment/ocr/deployment.yaml -f infrastructure/deployment/ocr/ingress.yaml
kubectl --kubeconfig $ocrKubeconfig apply -f infrastructure/deployment/ocr/deployment.yaml -f infrastructure/deployment/ocr/ingress.yaml
kubectl --kubeconfig $ocrKubeconfig -n jmix-ocr rollout status deployment/docling --timeout=15m
```

Verify `/health`, 401 for missing/wrong keys on conversion, and a scanned PDF
through the authenticated conversion endpoint. Inspect status, document text
and page provenance; health alone is insufficient. Measure resource usage and
verify existing project workloads after rollout.

## Private connectivity from MemoryOS

The primary endpoint is `http://ocr.10.123.123.194.nip.io`, routed by the existing
nginx ingress to `docling:5001`. Conversion uses `POST /v1/convert/source` and
the `X-Api-Key` header. The ingress accepts a 150 MiB request body to accommodate
base64 encoding of a 100 MiB input file, with 3630-second read/send timeouts.
The direct NodePort endpoint remains `http://10.123.123.194:31079`.
The hostname resolves to a private node IP. Connect the calling server
to the organization's VPN, allow the OCR port through the applicable firewall,
and check access from the actual Worker container as well as the host.

For OpenVPN, use a profile issued for the server and managed credentials. Keep
the server's default route; send only the needed cluster subnet through VPN.
The approved cluster subnet is `10.123.123.0/24`. Review pushed routes and the
profile's existing directives before using `route-nopull` plus
`route 10.123.123.0 255.255.255.0`. Enable persistence through the installed
OpenVPN client's supported systemd unit. Verify the route, return traffic and
Docker egress after reconnect. Do not copy a personal certificate to a second
machine without checking the VPN server's concurrent-session policy.

At the 2026-09-10 checkpoint, the service is Ready 1/1 and the endpoint passes
authenticated two-page Vietnamese/English OCR from Windows over VPN in 14.09
seconds. An additional check through nginx with the nip.io Host header completed
in 10.06 seconds. Missing and wrong keys return 401 on both paths. Public DNS
and MemoryOS server DNS resolve the hostname correctly; Windows' current DNS
resolver (`192.168.1.1`) returns no A record, so ingress verification used an
explicit IP/Host mapping. No machine DNS or hosts-file settings were changed.
`72.62.193.33` still times out on the private endpoint; VPN installation and
connection have not been completed. nip.io supplies DNS, not network routing.

## Timeout configuration ownership

The standalone Kubernetes Deployment supplies Docling's environment directly;
the caller's Infisical settings do not update this pod. Keep
`DOCLING_SERVE_MAX_FILE_SIZE=104857600`,
`DOCLING_SERVE_MAX_DOCUMENT_TIMEOUT=3600` and `DOCLING_SERVE_MAX_SYNC_WAIT=3610`
in `deployment.yaml`, with Uvicorn graceful shutdown at 3620 seconds and pod
termination grace at 3630 seconds. Apply the matching 150 MiB body allowance
and 3630-second ingress timeouts as well. A caller-only increase can otherwise
hit ingress HTTP 413 or the unchanged service's file/processing limits.

The 2026-09-11 correction verified 1800/1810 inside the replacement container.
Through ingress, the scanned Vietnamese/English PDF succeeded with a requested
1800-second budget in 12.09 seconds; 1801 seconds was rejected with HTTP 422.
Missing and wrong API keys still returned 401. This verifies request admission
and OCR after the configuration change, not a full thirty-minute conversion or
external-Worker indexing.

The subsequent 100 MiB / sixty-minute correction verified the three limits
inside the replacement container. An exact 104857600-byte valid scanned PDF
containing an uncompressed 5900×5900 RGB image produced a 139810458-byte JSON
request. Through ingress, that request with `document_timeout=3600` completed
with HTTP 200/status success in 47.0 seconds, expected Vietnamese/English
phrases and numeric strings, and page provenance `{1}`. This is a one-page
admission/OCR boundary check, not a sixty-minute or large-corpus benchmark.

A 3601-second budget is rejected with HTTP 422; missing/wrong API keys remain
401. For a file of 104857601 bytes, the service log confirms rejection by the
104857600-byte file limit, but this upstream version returns HTTP 404
`Task result not found` rather than a clear size-policy response. Do not
interpret that response as a missing Deployment or a successful conversion.

The ingress controller manages Nginx reconciliation. The current project
account cannot exec into `ingress-nginx` to run `nginx -t`; verification used
Kubernetes server-side dry-run, applied annotations and the real ingress request.

## Rollback

Use the pre-change namespace annotation snapshots referenced in the deployment
record. Stop and remove only the OCR Ingress, Deployment, Service and dedicated
Secrets after draining work. Release its quota before restoring the original
namespace allocations; restoring quotas first would exceed the project memory
ceiling. Restore each namespace's `field.cattle.io/resourceQuota` annotation
and verify Rancher's generated quota and existing workload readiness. Keep all
existing PVCs and databases. The scaled-to-zero old Airflow stack needs a new
capacity review before being started under its reduced quota.
