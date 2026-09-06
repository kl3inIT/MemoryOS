# Docling extraction runtime

The base compose service pins Docling Serve CPU v1.32.0 by image digest. Models and EasyOCR English/Latin assets are baked into that same image; do not mount an unversioned model cache over them. Worker `engine-revision` uses the image digest in its processing profile. Change image and profile together. In-flight operations refuse a changed profile; retry with the old deployment or initiate a new reindex operation. Completed versions remain immutable.

The service uses one local conversion worker, no GPU, a read-only root filesystem and bounded `/tmp`. It is reachable only on the internal network and accepts file bytes/in-body results; it receives no Google or MinIO credentials. CPU 4 and RAM 8 GiB are initial enforced limits, not a measured capacity guarantee. A cold start loads models before `/health` becomes available. The actual request selects EasyOCR `vi,en`; the service's default warm-up may also initialize RapidOCR. There is no runtime model download in the pinned image.

Java sends synchronous SDK requests. Transport limits responses to 64 MiB and rejects redirects. Canonical output is limited separately to 32 MiB and text to 2 million characters. The server enforces 10 MiB, 200 pages and 300 seconds; worker timeout is 315 seconds. A server timeout is not proof of process termination. PostgreSQL claims fence publication, and container memory/CPU/scratch limits bound the isolated service.

## Local verification

Start the pinned image with the environment and limits in `compose.base.yaml`, on a loopback-only test port if needed. Set `DOCLING_TEST_ENDPOINT` only in the test process, then run the checked-in wrapper:

```powershell
$env:DOCLING_TEST_ENDPOINT = 'http://127.0.0.1:15062'
.\gradlew.bat clean check --no-daemon
```

The connector service tests cover Vietnamese DOCX tables and scanned PDF OCR. The worker integration test switches its synthetic upload from TXT to DOCX when that endpoint is supplied, exercising Redis, PostgreSQL and MinIO end-to-end. Without it, the real-service tests skip and the worker exercises TXT; do not describe that run as Docling verification. Unit tests separately verify transport limits and typed failures.

Artifact cleanup runs each minute. `document_extraction_artifacts` tracks STAGED, ACTIVE and DELETING objects. A DELETING row with `write_complete=false` is an intentional tombstone for an uncertain PUT and is retried daily; do not delete it solely because the object is currently absent. Back up the private `extracted/` prefix with the database references. No new public artifact endpoint or separate operator UI is introduced.
