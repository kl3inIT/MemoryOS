# MEM-234: an unreachable extraction service is asked again

## Problem

On 2026-10-05, between about 17:41 and 18:01 (Vietnam time), 13 of the 61 documents of the [MEM-230](../mem-230-rag-bench-promptfoo/design.md) benchmark corpus on staging ended `FAILED` with `SOURCE_EXTRACTION_CONNECTION_FAILED`. Each had been tried once (`index_attempts.processing_attempts = 1`). The `paddleocr-vl-api` container on the serving node restarted in that window. A manual reindex read every one of them.

The documents stayed failed until somebody reindexed them by hand. For the benchmark, 32 of 106 answerable questions had lost their evidence, and the first baseline and the P8 runs measured an incomplete corpus without anyone knowing. Outside the benchmark, one restart of the OCR service fails a whole batch of a customer's scans the same way.

## Cause

`DefaultIngestionCoordinator.processIndex` sent every `ExtractionException` to `indexingPort.fail`, which ends the attempt. That was the written contract ("Typed `ExtractionException` failures terminate the attempt"), and it is right for a failure of the document. It was applied to `CONNECTION_FAILED` too, which says nothing about the document.

The bounded retry (`indexingPort.retry`, three attempts, five seconds apart) served only unexpected runtime exceptions. Five seconds would not have helped: the service was away for about twenty minutes.

Text PDFs, DOCX and PPTX were not hit, because Tika stands in when Docling cannot be reached ([MEM-191](../../completed/mem-191-extraction-fallback/design.md)). A scan or an image goes to PaddleOCR-VL, which has no other reader behind it ([MEM-192](../../completed/mem-192-ocr-gpu/design.md)).

Two statements of the issue did not hold up against the code:

- "No row in `source_run_errors`, so nobody sees it." That table records what a sync run could not acquire; a FILE Source has no run. The failure was on the attempt and the file: the item was `FAILED`, the API returned its `errorCode`, and the Sources page has a sentence for this code. What was missing is an alert.
- "Share the retry policy with [MEM-231](https://linear.app/memory-os/issue/MEM-231)." That issue is a model call inside one Chat turn, retried in memory while a person waits. This one is a durable attempt handed back to PostgreSQL and delivered again minutes later. They share an idea and no code.

## Decision

1. **The failure says whether it is worth asking again.** `ExtractionFailure.retryable()` is true for `CONNECTION_FAILED` only. The coordinator hands a retryable failure to `indexingPort.retry` and every other typed failure to `indexingPort.fail`, as before.
2. **Waits that outlast a restart.** 30 seconds, then 2, 5, 10 and 15 minutes: five waits, 32.5 minutes, six attempts. The sixth unreachable attempt fails the document with the same code as today. The existing `WorkLeases.retry` does the scheduling; the coordinator chooses the wait from `IndexWork.attempt`, the number of budget attempts this claim is, which the repository now loads with the claim.
3. **PaddleOCR-VL names more of its outages `CONNECTION_FAILED`.** HTTP 429 joins 502 and 503, and a connection that breaks before the answer is complete, without the deadline having passed, is no longer `INTERNAL`. This is what a restarting container does to the requests it held.
4. **Docling's classification is unchanged.** Only connection establishment is `CONNECTION_FAILED` there. The extractor's rule stands: after a conversion is submitted the task may still be running, and a second submission would repeat it.
5. **An alert for documents that still fail.** Counter `memoryos.extraction.unavailable` with `outcome` `retry_scheduled` or `exhausted`, both registered at zero, and alert `MemoryOSExtractionUnavailable` on any `exhausted` increase in 15 minutes, after `MemoryOSDoclingFallback`.

## Choices made

- **`TIMEOUT` stays final.** A timeout is usually the document (a long scan), the remote work may still be running, and each further try can cost up to the sixty-minute ceiling.
- **`INTERNAL` stays final.** For Docling it is ambiguous by design. For PaddleOCR-VL what remains in it after this change is HTTP 500 and 4xx, which a second request is likely to repeat.
- **No new failure value.** A separate `UNAVAILABLE` would need its own error code, API documentation and web copy in two languages for the same thing an operator does about it. PaddleOCR-VL already reports 502 and 503 as `CONNECTION_FAILED`.
- **One shared attempt count.** The retryable budget (six) and the unexpected-failure budget (three) both read `processing_attempts - deferred_attempts`. A document that has waited through three unreachable attempts and then hits an unexpected exception fails at once. A second counter column would need a migration to serve a case that has not been seen.
- **The wait is chosen in the coordinator.** `WorkLeases.retry` keeps its one signature for every attempt table. The alternative, a schedule passed into the persistence helper, would change it for cleanup, sync and selection work that have no use for one.

## Not in scope

- **Chat and library files (`USER_FILE`).** They already retry every processing failure three times, five seconds apart, and a person is waiting on the result; half an hour in "processing" is worse for them than a failure they can retry. That path also retries failures of the document, which is wasteful but old; it is left as it is.
- **Retrying before the Tika fallback.** When Docling cannot be reached, a text document is still published from Tika at once ([docling-fallback-alert](../docling-fallback-alert/design.md), product owner decision of 2026-10-01).
- **Reindexing documents that already failed.** A Source manager reindexes them; the alert's summary says so.
- **A dashboard panel.** The counter is available to Grafana; the alert is what was missing.

## Risk

- A service that is down for longer than half an hour still fails its documents. They then raise the alert instead of failing silently.
- While PaddleOCR-VL is away, each waiting document is delivered up to five more times. Each try fails within the connection timeout, so the cost is small.
- A connection PaddleOCR-VL drops for a reason that repeats (for example a proxy that cuts one particular large body) is now tried six times over half an hour before it fails.
