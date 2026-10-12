# Verification — Worker replicas (MEM-244)

## Pull request 1 on staging (2026-10-12)

Release `d8b2a3cf`: the Worker container is `memoryos-worker-1`. A 45 MB scanned PDF was reindexed and the Worker stopped with `docker stop -t 120` while reading it.

| Probe | Result |
| --- | --- |
| `docker stop -t 120` on `d8b2a3cf` | Returned in 0.46 s, exit 1: `[FATAL tini (1)] Unexpected error when forwarding signal: 'Operation not permitted'`. docker-init runs as root with every capability dropped and the JVM as `memoryos`, so without `CAP_KILL` every stop of the api, the Worker and Redis was an immediate kill. Older than #582; fixed by #583. The document was not failed: its lease lapsed, the restarted Worker read it again (attempt 2) and it succeeded |
| SIGTERM to the JVM, a short document, 3 s into the read | The read finished first: `SUCCEEDED`, attempt 1; the JVM exited after 11 s |
| SIGTERM to the JVM, the 45 MB PDF, 10 s into the read | Waited the 100 s grace, then `redis.delivery.shutdown_cut` and `redis.delivery.left_for_reclaim`; nothing recorded; the JVM exited after 109 s; read again after the lease (attempt 2), `SUCCEEDED` |
| `docker stop -t 120` on `3ed6da57` (#583), the 45 MB PDF | 108 s, exit 143, no tini error, the same two events; the operation stayed `IN_PROGRESS` with no error code and was claimed again (attempt 2) |

Open after these probes: the stop budget is tight (100 s grace and about 9 s of shutdown against 120 s, and db-scheduler may wait up to 60 s for a running task); a read cut off by a stop still counts in `processing_attempts`, so it spends one retry of a later `CONNECTION_FAILED`; an interrupted read logs `paddleocr_vl.extraction.failed` at WARN.

## Two replicas on production (2026-10-12)

Release `3ed6da57`, `MEMORYOS_WORKER_REPLICAS=2`, the application role's connection limit raised from 40 to 60 (15 + 2 × 10 + 10 = 45), PaddleOCR-VL requests lowered from 8 to 4 a Worker so the serving node still receives 8.

- `check_database_budget` passed; `memoryos-worker-1` and `memoryos-worker-2` healthy, started in 15.2 s and 15.1 s, no ERROR or WARN logged.
- 35 application connections: 15 for the API and 10 for each Worker.
- 624 and 638 MiB idle.
- Twelve generated Markdown files (about 266 KB each) in a temporary private FILE source: all `SUCCEEDED` with `processing_attempts = 1` within 13 s, seven read by one Worker and five by the other, none read twice. The source was then deleted: no connector, document, chunk or stored object left.

Not exercised on production: scanned PDFs through OCR under two Workers, and a stop while a document is read.
