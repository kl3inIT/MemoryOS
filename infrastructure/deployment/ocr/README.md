# Standalone Vietnamese/English OCR

The deployment targets namespace `jmix-ocr` in Rancher project `local:p-vs2td`
(Jmix team). Its requests and limits are both 8 CPU / 16 GiB, with one replica
and a Recreate update strategy. Existing project workloads keep their images,
replicas and storage. See the [deployment record](../../../docs/increments/completed/mem-79-rancher-ocr/plan.md)
for the actual rollout state; checked-in manifests do not establish deployment.

## Image and request contract

`Dockerfile` derives from the repository's pinned Docling Serve CPU 1.32.0,
adds the pinned Vietnamese Tesseract language package, and restricts Tesseract
CLI threading through the wrapper. It retains the upstream model assets.
`deployment.yaml` pins the published private registry digest, not a mutable tag.

The service accepts file bytes and in-body results, up to 20 MiB and 200 pages,
with 900-second document processing and a 910-second synchronous wait. Supply
the API key in `X-Api-Key`. For the tested scanned-PDF request, choose
`ocr_engine=tesseract`, `ocr_lang=["vie","eng"]`, `do_ocr=true`, and
`force_ocr=true`. Mixed native PDFs should choose force_ocr deliberately.

Current main's Worker remains a separate integration step: it uses EasyOCR,
limits input to 10 MiB, and does not supply the service API-key header. Merely
changing its endpoint does not complete this authenticated integration.

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
the `X-Api-Key` header. The ingress accepts a 30 MiB request body to accommodate
base64 encoding of a 20 MiB input file, with 930-second read/send timeouts.
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

## Rollback

Use the pre-change namespace annotation snapshots referenced in the deployment
record. Stop and remove only the OCR Ingress, Deployment, Service and dedicated
Secrets after draining work. Release its quota before restoring the original
namespace allocations; restoring quotas first would exceed the project memory
ceiling. Restore each namespace's `field.cattle.io/resourceQuota` annotation
and verify Rancher's generated quota and existing workload readiness. Keep all
existing PVCs and databases. The scaled-to-zero old Airflow stack needs a new
capacity review before being started under its reduced quota.
