"""Own only local lifecycle and converter injection; mount the pinned Serve API intact."""

import asyncio
import logging
import shutil
import subprocess
from contextlib import asynccontextmanager
from typing import Any

from docling.datamodel.service.responses import HealthCheckResponse, ReadinessResponse
from docling_jobkit.orchestrators.local.orchestrator import LocalOrchestrator
from docling_jobkit.orchestrators.local.worker import AsyncLocalWorker
from docling_serve.app import create_app as create_vendor_app
from docling_serve.orchestrator_factory import get_async_orchestrator
from docling_serve.public_errors import build_public_http_detail
from docling_serve.settings import AsyncEngine, docling_serve_settings
from docling_serve.storage import get_scratch
from docling_serve.websocket_notifier import WebsocketNotifier
from fastapi import FastAPI, HTTPException

from .converter import OrientationConverterManager

_log = logging.getLogger(__name__)


class _WorkerContext(LocalOrchestrator):
    """Give the stock worker its own manager without duplicating its task loop."""

    def __init__(
        self, owner: LocalOrchestrator, manager: OrientationConverterManager
    ) -> None:
        """Bind a worker-local converter manager to the owning orchestrator."""
        self.owner = owner
        self.cm = manager

    def __getattr__(self, name: str) -> Any:
        """Delegate unowned worker context attributes to the orchestrator."""
        return getattr(self.owner, name)


class OrientationOrchestrator(LocalOrchestrator):
    async def process_queue(self) -> None:
        """Run configured local workers with shared or worker-local managers."""
        async with asyncio.TaskGroup() as workers:
            for index in range(self.config.num_workers):
                context: LocalOrchestrator = self
                if not self.config.shared_models:
                    manager = OrientationConverterManager(self.cm.config)
                    self.worker_cms.append(manager)
                    context = _WorkerContext(self, manager)
                worker = AsyncLocalWorker(
                    index,
                    context,
                    use_shared_manager=True,
                    scratch_dir=self.scratch_dir,
                )
                workers.create_task(worker.loop())


def create_app() -> FastAPI:
    """Build the local API-only Docling application with orientation support."""
    settings = docling_serve_settings
    if settings.eng_kind != AsyncEngine.LOCAL or settings.enable_ui:
        raise ValueError(
            "MemoryOS preprocessing requires the local API-only Docling service"
        )
    stock = get_async_orchestrator()
    if not isinstance(stock, LocalOrchestrator):
        raise TypeError("Expected the configured local orchestrator")
    orchestrator = OrientationOrchestrator(
        stock.config, OrientationConverterManager(stock.cm.config)
    )
    vendor = create_vendor_app()
    vendor.dependency_overrides[get_async_orchestrator] = lambda: orchestrator
    ready = asyncio.Event()
    failed = asyncio.Event()

    def supervise(task: asyncio.Task) -> None:
        """Mark the service unavailable when its queue processor fails."""
        if not task.cancelled() and task.exception() is not None:
            failed.set()
            _log.error("Docling queue processor failed", exc_info=task.exception())

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        """Initialize the local worker lifecycle and release its scratch data."""
        languages = await asyncio.to_thread(
            subprocess.run,
            ["tesseract", "--list-langs"],
            capture_output=True,
            text=True,
            timeout=5,
            check=True,
        )
        if "osd" not in languages.stdout.split():
            raise RuntimeError("Tesseract orientation data is required")
        orchestrator.bind_notifier(WebsocketNotifier(orchestrator))
        if settings.load_models_at_boot:
            await orchestrator.warm_up_caches()
        ready.set()
        queue = asyncio.create_task(orchestrator.process_queue())
        queue.add_done_callback(supervise)
        try:
            yield
        finally:
            ready.clear()
            queue.cancel()
            try:
                await queue
            except asyncio.CancelledError:
                pass
            finally:
                if settings.scratch_path is not None:
                    shutil.rmtree(get_scratch(), ignore_errors=True)

    app = FastAPI(openapi_url=None, docs_url=None, redoc_url=None, lifespan=lifespan)

    @app.get("/ready", include_in_schema=False)
    @app.get("/readyz", include_in_schema=False)
    async def readiness() -> ReadinessResponse:
        """Report readiness only while models and the queue processor are usable."""
        if not ready.is_set():
            raise HTTPException(status_code=503, detail="Models not yet loaded")
        if failed.is_set():
            raise HTTPException(
                status_code=503, detail="Background queue processor is not running."
            )
        try:
            await orchestrator.check_connection()
        except Exception as error:
            raise HTTPException(
                status_code=503,
                detail=build_public_http_detail(
                    exc=error,
                    debug_enabled=settings.debug_error_details,
                    fallback_message="Readiness check failed",
                ),
            ) from error
        return ReadinessResponse()

    @app.get("/livez", include_in_schema=False)
    async def liveness() -> HealthCheckResponse:
        """Report whether the background queue processor remains alive."""
        if failed.is_set():
            raise HTTPException(
                status_code=503, detail="Background queue processor is not running."
            )
        return HealthCheckResponse()

    app.mount("/", vendor)
    return app
