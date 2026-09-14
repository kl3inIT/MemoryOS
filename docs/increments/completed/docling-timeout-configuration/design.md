# Environment-configured Docling timeouts

## Problem

The HUT Drive extraction reached Docling but exceeded its 300-second processing budget. Worker configuration already binds `memoryos.extraction.docling.timeout`, but its constructor rejects durations above 300 seconds and deployment Compose fixes the service budgets at 300/310 seconds.

## Accepted scope

- Keep the five-minute default and allow a positive Worker timeout up to fifteen minutes.
- Expose every existing Docling service environment setting through the deployment environment example and Compose interpolation, retaining current defaults and the existing environment-file convention.
- Forward `MEMORYOS_EXTRACTION_DOCLING_TIMEOUT` to Worker through Compose.
- Parameterize `DOCLING_SERVE_MAX_DOCUMENT_TIMEOUT` and `DOCLING_SERVE_MAX_SYNC_WAIT`, retaining 300/310-second defaults.
- Document coordinated overrides of `15m`, `900`, and `910`. The existing SDK read timeout remains the Worker budget plus fifteen seconds.
- Preserve OCR options, input/output bounds, retries, error classification, and running environments. No server rollout or automatic reindex is part of this change.

## Verification boundary

Use focused connector tests and compilation, resolve Compose with default and overridden environments, and exercise environment binding through the production extractor. This proves configurable budgets, not that HUT completes within fifteen minutes or that OCR output is numerically accurate. Keep this increment active until merge.
