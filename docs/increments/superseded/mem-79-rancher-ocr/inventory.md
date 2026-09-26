# Jmix team inventory and proposed OCR allocation

Live inspection on 2026-09-10 through the configured Rancher API and Kubernetes
context `local`. Project **Jmix team**, ID `local:p-vs2td`, has four namespaces.
There is no existing namespace named `jmix` in this project. No cluster settings
were changed during this audit.

## Workloads

| Namespace | Workloads at 1/1 Ready | Workloads at zero replicas |
| --- | --- | --- |
| stc-hy | airflow-scheduler, airflow-webserver, apicurio, apicurio-ui, apisix, cube, o2p-oauth2-proxy, pgweb, postgrest, semantic-layer-service, stc-app-backend, stc-app-frontend, storage-ui, sw-seaweedfs-s3, kc-keycloakx, sw-seaweedfs-filer, sw-seaweedfs-master, sw-seaweedfs-volume, tabmis-postgres, tabmis-s3 | None |
| stc-hy-airflow | stc-airflow-postgresql | stc-airflow-api-server, stc-airflow-dag-processor, stc-airflow-scheduler, stc-airflow-redis, stc-airflow-triggerer, stc-airflow-worker |
| stc-hy-bi | superset, superset-worker, superset-postgresql, superset-redis-master | None |
| stc-hy-metadata | openmetadata, openmetadata-opensearch, openmetadata-postgresql | None |

All 28 desired workload replicas are Ready. This is readiness evidence, not
application-level acceptance. The zero-replica Airflow stack is separate from
the active scheduler/webserver in `stc-hy`.

## Kubernetes Services

All services are ClusterIP (including headless services), except the explicitly
listed NodePort. A Service object does not imply its backing workload is running.

| Namespace | Services and ports |
| --- | --- |
| stc-hy | airflow-webserver:8080; apicurio:80; apicurio-ui:80; apisix:9080; cube:4000; kc-keycloakx-headless:80; kc-keycloakx-http:9000,80,8443; o2p-oauth2-proxy:80; pgweb:8081; postgrest:3000; semantic-layer-service:8000; stc-app-backend:8080; stc-app-frontend:8080; storage-ui:23646; sw-seaweedfs-filer:8888,18888,9327; sw-seaweedfs-filer-client:8888,18888,9327; sw-seaweedfs-master:9333,19333,9327; sw-seaweedfs-s3:8333,9327; sw-seaweedfs-volume:8080,18080,9327; tabmis-airflow-access:8080 (NodePort 31306); tabmis-postgres:5432; tabmis-s3:8333; warehouse-s3:8333; warehouse-storage-internal:9333,19333,8888,18888 |
| stc-hy-airflow | stc-airflow-api-server:8080; stc-airflow-postgresql:5432; stc-airflow-postgresql-hl:5432; stc-airflow-redis:6379; stc-airflow-triggerer:8794; stc-airflow-worker:8793 |
| stc-hy-bi | superset:8088; superset-postgresql:5432; superset-postgresql-hl:5432; superset-redis-headless:6379; superset-redis-master:6379 |
| stc-hy-metadata | openmetadata:8585,8586; openmetadata-opensearch:9200,9300,9600; openmetadata-opensearch-headless:9200,9300,9600; openmetadata-postgresql:5432 |

## Quota and storage

Project ceiling: requests 16 CPU / 32 GiB; limits 32 CPU / 48 GiB.
Namespace allocation currently consumes requests 13 CPU / 26 GiB and limits
30 CPU / 48 GiB. The 48 GiB figure is allocated quota, not measured RAM use.
Admitted pod totals are requests 4.175 CPU / 9.5 GiB and limits
15.05 CPU / 25.375 GiB. These totals also are not measured runtime consumption.

| Namespace | Current pod limits CPU / GiB | Current quota limits CPU / GiB | Proposed quota limits CPU / GiB | Requests quota: current to proposed CPU / GiB | Bound PVC count / requested storage |
| --- | --- | --- | --- | --- | --- |
| stc-hy | 9.3 / 14.625 | 12 / 20 | 12 / 16 | 6 / 12 to 4 / 8 | 7 / 23 GiB |
| stc-hy-airflow | 0.5 / 0.5 | 10 / 12 | 2 / 2 | 3 / 6 to 1 / 1 | 4 / 11 GiB |
| stc-hy-bi | 2.25 / 4.25 | 4 / 8 | 4 / 6 | 2 / 4 to 1 / 3 | 2 / 3 GiB |
| stc-hy-metadata | 3 / 6 | 4 / 8 | 4 / 8 | 2 / 4, unchanged | 2 / 12 GiB |
| jmix-ocr, new | None | None | 8 / 16 | 8 / 16 | 0 / 0 GiB |

Proposed project totals: requests **16 CPU / 32 GiB**, limits **30 CPU / 48 GiB**.
Storage and PVC quotas stay as they are. All 15 existing PVCs are Bound, totaling
49 GiB requested storage. The old Airflow stack cannot be scaled back to its
former size without reviewing its reduced quota.

Six cluster nodes are Ready with no MemoryPressure, DiskPressure or PIDPressure.
Cluster-wide node metrics are forbidden to this account, so physical free memory
is not established. An OCR pod with equal requests/limits must successfully
schedule before its capacity can be claimed available.

## Execution order after the planning checkpoint

1. Save rollback snapshots; re-read pod requests/limits and Rancher project quotas.
2. Apply guarded quota reductions to stc-hy, stc-hy-airflow and stc-hy-bi. Wait
   for Rancher reconciliation and verify existing workload readiness.
3. Create `jmix-ocr` with project annotation `local:p-vs2td`, requests and limits
   8 CPU / 16 GiB, and no PVC allocation.
4. Finish and verify the derived Docling 1.32.0 Vietnamese image; publish it and
   pin the registry digest. The experimental OCR branch named in MEM-79 is not
   present on the configured remote, and current main has no derived Dockerfile.
5. Deploy one conversion worker with offline models, 20 MiB / 200-page bounds,
   900/910-second budgets, API-key authentication and a private endpoint.
6. Verify unauthorized rejection, Vietnamese/English scanned-PDF conversion,
   page provenance, resources, and connectivity from the caller's actual host.
   Caller is `72.62.193.33`; its VPN connection remains pending.
7. Record endpoint, secret location, digest, smoke evidence and rollback steps.
   Current MemoryOS main still needs explicit client integration for API-key
   authentication; its 10 MiB/EasyOCR behavior is not changed by deploying OCR.

The quota changes and standalone service deployment were subsequently executed
after user approval. See [execution evidence](plan.md) for namespace `jmix-ocr`,
the successful live OCR check, and pending server VPN connectivity. Full MEM-79
indexing and large-corpus acceptance remain separate.
