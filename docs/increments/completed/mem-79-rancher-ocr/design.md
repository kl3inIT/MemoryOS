# MEM-79: standalone OCR in Rancher

## Authorized scope

Deploy only Docling OCR in namespace `jmix-ocr`, in Rancher project **Jmix team**
(`local:p-vs2td`) on cluster `local` at `iks-as-rancher.congchuc.ai`.
The user explicitly authorized reclaiming unused quota from the other project
namespaces. Existing application replicas, volumes, databases and images stay as
they are. MemoryOS remains an external caller; this is not full MEM-79 indexing
acceptance.

On 2026-09-10 the owner requested direct integration of this standalone OCR
deployment into main without a PR, then assigned MEM-79 to Viet for review.
VPN provisioning, Worker API-key integration and the original full
indexing/corpus acceptance remain unverified and must be visible in that review.

The originally chosen name `ocr` exists but is inaccessible to the current
project-owner account. Use `jmix-ocr`, created directly with the project
annotation and explicit quota, for the actual service. The existing `ocr`
namespace has not been changed by this deployment.

## Capacity allocation

Keep the project ceiling at requests 16 CPU / 32 GiB and limits 32 CPU / 48 GiB.
The following namespace allocation fits existing admitted pod requests/limits,
with OCR receiving a guaranteed single-pod reservation of 8 CPU / 16 GiB.

| Namespace | CPU requests | RAM requests | CPU limits | RAM limits |
| --- | ---: | ---: | ---: | ---: |
| stc-hy | 4 | 8 GiB | 12 | 16 GiB |
| stc-hy-airflow | 1 | 1 GiB | 2 | 2 GiB |
| stc-hy-bi | 1 | 3 GiB | 4 | 6 GiB |
| stc-hy-metadata | 2 | 4 GiB | 4 | 8 GiB |
| jmix-ocr | 8 | 16 GiB | 8 | 16 GiB |

Existing storage/PVC quotas remain unchanged. OCR has no persistent volume claim.
Airflow's old deployments currently have zero replicas except PostgreSQL; those
deployments will need a new capacity review before scaling back up. Quota
allocation is not proof of physical-node headroom; scheduling is verified live.

## OCR runtime

Use Docling Serve CPU 1.32.0 at the existing upstream digest
`sha256:576fc2074ac77bcfbf3fe27633aa0dd89b452a170b2cd31689c8751e94d60f7a`.
The OCR experiment branch and Dockerfile named in Linear were not available on
the configured GitHub remote during this inspection. Build a standalone derived
image adding the exact Vietnamese Tesseract language package identified by the
issue; retain the upstream EasyOCR assets for compatibility with current main.
Publish and deploy by digest, then verify both language inventory and OCR output.

One conversion worker, offline model assets, 20 MiB input, 200 pages, and a
900-second document budget with 910-second synchronous wait bound the service.
Use a read-only filesystem, bounded scratch memory, no Kubernetes API token,
non-root execution, API-key authentication and a private caller endpoint.
The caller must explicitly choose OCR engine/languages and supply the API key.
The private hostname `ocr.10.123.123.194.nip.io` routes through the existing nginx
ingress to port 5001. Its 30 MiB body allowance accounts for base64 JSON encoding;
930-second proxy timeouts cover the service's synchronous wait. The direct
NodePort remains available at `10.123.123.194:31079`. Both paths require a route
to the private cluster network, and conversion requires the service API key.
Current main's Worker has a 10 MiB limit and hard-coded EasyOCR options and does
not yet send an API key; this deployment does not claim to change that client.

## Verification and rollback

Save namespace annotations and quotas before mutation, and compare their current
values before each update. Re-read Rancher's generated ResourceQuota after each
change. Confirm old workloads retain their readiness and replica counts.
Validate the published image, workload readiness, unauthorized-request rejection,
Vietnamese/English scanned-PDF conversion, page provenance and resource use.
The external caller is MemoryOS server `72.62.193.33`; its VPN connection to
`10.123.123.0/24` still needs to be provisioned.
Rollback removes only the OCR workload and restores the saved quota allocation;
existing workloads and storage are not reset. Full corpus/indexing acceptance
remains in MEM-79 after this standalone deployment.
