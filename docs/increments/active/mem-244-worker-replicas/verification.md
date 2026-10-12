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

## Pull request 3 on staging (2026-10-12)

Release `4877daeb` (with the Lark connector): the Worker started in 13.2 s on the new composition with no new ERROR or WARN, 15 and 10 application connections, every recurring task running. A short scanned PDF was read through PaddleOCR-VL and `SUCCEEDED` in 5 s on attempt 1; the 18 search operations of the hour all ended `SUCCESS`.

That check found the relay's ceiling: after a Worker restart the search operation of a document just read stayed `NOT_STARTED` with `dispatch_attempts = 0` for more than two minutes while the search relay published 2 rows every 5 s (06:13:24, :29, :34 …), every stream was empty and no transaction ran longer than 3 s. Without a restart the same operation was published 2 s after its creation and done in 7 s. Pull request 4 follows from it.

## Pull request 4 on staging (2026-10-12)

Release `68c10df7`, one Worker, 44 seconds after it started (the case that took 150 s before). Sixteen scanned PDFs of at most 1.2 MB were reindexed at once.

| Measure | Before (`4877daeb`, after a restart) | After |
| --- | --- | --- |
| Search operation, creation to publication | 73 s average, 150 s at worst | 0.9 s average, 1.9 s at worst (17 operations) |
| Search operation, creation to start | not measured apart | 10.4 s average, 34.1 s at worst: two run at once, the rest wait for a slot |
| Publications an operation | not measured | 1 each |
| Consumers a group in Redis | more than 230 | 3 |

All sixteen documents `SUCCEEDED` on attempt 1 and their seventeen search operations ended `SUCCESS` on attempt 1. Eight documents ran at once; the ninth started 33 s after its creation, when the first slot came free, where a reader that waited for its batch would have started it after the slowest of the eight (254 s). No ERROR was logged; the streams were empty afterwards.

What bounds search now is the Worker: two search operations at once (`MEMORYOS_WORKER_SEARCH_BATCH_SIZE`). Publishing right after commit would save under a second of the 10 s, so it is not added.

## Pull request 2 on staging (2026-10-12)

Release `99420a9a`, one Worker: `limits.memory` 4 GiB, stop timeout 180 s, `JAVA_TOOL_OPTIONS` with `InitialRAMPercentage=5`, `MaxRAMPercentage=50`, `UseSerialGC`, `UseCompactObjectHeaders`.

| Measure | Before (`68c10df7`, no limit) | After |
| --- | --- | --- |
| Start | 14.1 s | 12.3 s |
| Container memory, idle after start | not taken | 479 to 483 MiB |
| Container memory after the day's bursts | 1.06 GiB | 719 to 744 MiB |
| Peak while 12 Markdown files and 16 scanned PDFs were read (sampled each second for the first 150 s of 510 s) | not taken | 827 MiB of 4 GiB |
| Tika child JVMs alive at once, 12 Markdown files uploaded together (sampled four times a second) | up to the workload's slots | 2 |

All 28 documents of the burst `SUCCEEDED` on attempt 1 with their 28 search operations; the 12 Markdown files of the second run `SUCCEEDED` on attempt 1, the slowest 17.1 s after its creation. The container was not killed for memory and did not restart. Collection pauses and the memory of one child were not measured. Production does not run this release yet.

## Two replicas on production (2026-10-12)

Release `3ed6da57`, `MEMORYOS_WORKER_REPLICAS=2`, the application role's connection limit raised from 40 to 60 (15 + 2 × 10 + 10 = 45), PaddleOCR-VL requests lowered from 8 to 4 a Worker so the serving node still receives 8.

- `check_database_budget` passed; `memoryos-worker-1` and `memoryos-worker-2` healthy, started in 15.2 s and 15.1 s, no ERROR or WARN logged.
- 35 application connections: 15 for the API and 10 for each Worker.
- 624 and 638 MiB idle.
- Twelve generated Markdown files (about 266 KB each) in a temporary private FILE source: all `SUCCEEDED` with `processing_attempts = 1` within 13 s, seven read by one Worker and five by the other, none read twice. The source was then deleted: no connector, document, chunk or stored object left.

Not exercised on production: scanned PDFs through OCR under two Workers, and a stop while a document is read.
