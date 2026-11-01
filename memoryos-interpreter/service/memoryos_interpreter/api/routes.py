from __future__ import annotations

import threading
from collections.abc import Generator
from pathlib import Path
from urllib.parse import quote

from fastapi import APIRouter, File, HTTPException, UploadFile, status
from fastapi.responses import Response, StreamingResponse
from starlette.concurrency import run_in_threadpool
from starlette.types import Receive, Scope, Send

from memoryos_interpreter.app_configs import get_settings
from memoryos_interpreter.models.schemas import (
    BashExecRequest,
    BashExecResponse,
    CreateSessionRequest,
    CreateSessionResponse,
    ExecuteFile,
    ExecuteRequest,
    ExecuteResponse,
    FileMetadataResponse,
    ListFilesResponse,
    StreamErrorEvent,
    StreamOutputEvent,
    StreamResultEvent,
    UploadFileResponse,
    WorkspaceFile,
)
from memoryos_interpreter.services.executor_base import (
    EntryKind,
    SessionNotFoundError,
    StreamChunk,
    StreamResult,
    WorkspaceEntry,
)
from memoryos_interpreter.services.executor_factory import (
    execute_python,
    execute_python_streaming,
    get_executor,
)
from memoryos_interpreter.services.file_storage import FileStorageService

router = APIRouter()

UPLOAD_CHUNK_BYTES = 1024 * 1024

# Initialize file storage service
_file_storage: FileStorageService | None = None


class ExecutionSlots:
    """Bounds executions running at once; ``limit`` 0 means unlimited."""

    def __init__(self, limit: int) -> None:
        self.limit = limit
        self._semaphore = threading.BoundedSemaphore(limit) if limit > 0 else None

    def try_acquire(self) -> _ExecutionSlot | None:
        if self._semaphore is not None and not self._semaphore.acquire(blocking=False):
            return None
        return _ExecutionSlot(self._semaphore)


class _ExecutionSlot:
    """One acquired slot; releasing it more than once is a no-op."""

    def __init__(self, semaphore: threading.BoundedSemaphore | None) -> None:
        self._semaphore = semaphore
        self._lock = threading.Lock()

    def release(self) -> None:
        with self._lock:
            semaphore, self._semaphore = self._semaphore, None
        if semaphore is not None:
            semaphore.release()


class _SlotStreamingResponse(StreamingResponse):
    """Frees the execution slot only after the executor is cleaned up, also on client disconnect.

    The body generator releases the slot in its own ``finally``, after closing the executor stream.
    When the response ends, the generator is closed so that cleanup runs now. A generator that never
    started holds no executor, so its slot is released here.
    """

    def __init__(
        self, content: Generator[str, None, None], slot: _ExecutionSlot, **kwargs: object
    ) -> None:
        super().__init__(content, **kwargs)  # type: ignore[arg-type]
        self._content = content
        self._slot = slot

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        try:
            await super().__call__(scope, receive, send)
        finally:
            await run_in_threadpool(self._close_body)

    def _close_body(self) -> None:
        try:
            self._content.close()
        except ValueError:
            # A worker thread is still producing a chunk; the generator releases the slot when
            # that chunk returns and the generator is closed.
            return
        self._slot.release()


_execution_slots: ExecutionSlots | None = None
_execution_slots_lock = threading.Lock()


def get_execution_slots() -> ExecutionSlots:
    """Get or create the global ExecutionSlots instance."""
    global _execution_slots
    with _execution_slots_lock:
        if _execution_slots is None:
            _execution_slots = ExecutionSlots(get_settings().max_concurrent_executions)
        return _execution_slots


def _acquire_execution_slot() -> _ExecutionSlot:
    slot = get_execution_slots().try_acquire()
    if slot is None:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=f"All {get_execution_slots().limit} execution slots are in use; retry later",
            headers={"Retry-After": "1"},
        )
    return slot


def get_file_storage() -> FileStorageService:
    """Get or create the global FileStorageService instance."""
    global _file_storage
    if _file_storage is None:
        settings = get_settings()
        _file_storage = FileStorageService(Path(settings.file_storage_dir))
    return _file_storage


def _content_disposition(filename: str) -> str:
    """Build an attachment header that keeps non-Latin-1 names through RFC 6266 ``filename*``."""
    fallback = "".join(
        char if char.isascii() and char.isprintable() and char not in '"\\' else "_"
        for char in filename
    )
    return f"attachment; filename=\"{fallback}\"; filename*=UTF-8''{quote(filename, safe='')}"


def _validate_timeout(req: ExecuteRequest) -> None:
    settings = get_settings()
    if req.timeout_ms > settings.max_exec_timeout_ms:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"timeout_ms exceeds maximum of {settings.max_exec_timeout_ms} ms",
        )


def _resolve_uploaded_files(
    files: list[ExecuteFile],
    storage: FileStorageService,
) -> tuple[list[tuple[str, bytes]], dict[str, bytes]]:
    """Resolve uploaded file IDs into content for the executor.

    Returns (staged_files, input_files_map).
    """
    staged_files: list[tuple[str, bytes]] = []
    input_files_map: dict[str, bytes] = {}
    for file in files:
        try:
            content, _ = storage.get_file(file.file_id)
        except FileNotFoundError as exc:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"File with ID '{file.file_id}' not found for path '{file.path}'.",
            ) from exc
        staged_files.append((file.path, content))
        input_files_map[file.path] = content
    return staged_files, input_files_map


def _stage_request_files(
    req: ExecuteRequest,
    storage: FileStorageService,
) -> tuple[list[tuple[str, bytes]], dict[str, bytes]]:
    """Resolve uploaded file IDs into content for the executor.

    Returns (staged_files, input_files_map).
    """
    return _resolve_uploaded_files(req.files, storage)


def _save_workspace_files(
    entries: tuple[WorkspaceEntry, ...],
    input_files_map: dict[str, bytes],
    storage: FileStorageService,
) -> list[WorkspaceFile]:
    """Filter and save new/modified workspace files to storage."""
    workspace_files: list[WorkspaceFile] = []
    for entry in entries:
        if entry.kind == EntryKind.DIRECTORY:
            continue
        if entry.kind == EntryKind.FILE and entry.content is not None:
            if entry.path in input_files_map and entry.content == input_files_map[entry.path]:
                continue
            file_id = storage.save_file(entry.content, entry.path)
            workspace_files.append(WorkspaceFile(path=entry.path, kind=entry.kind, file_id=file_id))
    return workspace_files


@router.post("/execute", response_model=ExecuteResponse, status_code=status.HTTP_200_OK)
def execute(req: ExecuteRequest) -> ExecuteResponse:
    """Execute provided Python code synchronously within an isolated Docker container."""
    _validate_timeout(req)
    settings = get_settings()
    storage = get_file_storage()
    staged_files, input_files_map = _stage_request_files(req, storage)
    slot = _acquire_execution_slot()

    try:
        result = execute_python(
            code=req.code,
            stdin=req.stdin,
            timeout_ms=req.timeout_ms,
            max_output_bytes=settings.max_output_bytes,
            cpu_time_limit_sec=settings.cpu_time_limit_sec,
            memory_limit_mb=settings.memory_limit_mb,
            files=staged_files,
            last_line_interactive=req.last_line_interactive,
        )
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=str(exc),
        ) from exc
    finally:
        slot.release()

    return ExecuteResponse(
        stdout=result.stdout,
        stderr=result.stderr,
        exit_code=result.exit_code,
        timed_out=result.timed_out,
        duration_ms=result.duration_ms,
        files=_save_workspace_files(result.files, input_files_map, storage),
    )


@router.post("/execute/stream")
def execute_stream(req: ExecuteRequest) -> StreamingResponse:
    """Execute Python code with streaming output via Server-Sent Events."""
    _validate_timeout(req)
    settings = get_settings()
    storage = get_file_storage()
    staged_files, input_files_map = _stage_request_files(req, storage)
    # Acquired before the response starts, so a full service can still answer 429.
    slot = _acquire_execution_slot()

    def generate() -> Generator[str, None, None]:
        events = execute_python_streaming(
            code=req.code,
            stdin=req.stdin,
            timeout_ms=req.timeout_ms,
            max_output_bytes=settings.max_output_bytes,
            cpu_time_limit_sec=settings.cpu_time_limit_sec,
            memory_limit_mb=settings.memory_limit_mb,
            files=staged_files,
            last_line_interactive=req.last_line_interactive,
        )
        try:
            for event in events:
                if isinstance(event, StreamChunk):
                    yield StreamOutputEvent(stream=event.stream, data=event.data).to_sse()

                elif isinstance(event, StreamResult):
                    yield StreamResultEvent(
                        exit_code=event.exit_code,
                        timed_out=event.timed_out,
                        duration_ms=event.duration_ms,
                        files=_save_workspace_files(event.files, input_files_map, storage),
                    ).to_sse()

        except Exception as exc:
            yield StreamErrorEvent(message=str(exc)).to_sse()
        finally:
            # Closing the executor stream kills its container before the slot is freed.
            events.close()
            slot.release()

    return _SlotStreamingResponse(
        generate(),
        slot,
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@router.post("/files", response_model=UploadFileResponse, status_code=status.HTTP_201_CREATED)
async def upload_file(file: UploadFile = File(...)) -> UploadFileResponse:  # noqa: B008
    """Upload a file for later use in code execution."""
    settings = get_settings()
    storage = get_file_storage()

    # Read in chunks so an oversized upload is rejected without holding all of it in memory
    max_size_bytes = settings.max_file_size_mb * 1024 * 1024
    buffer = bytearray()
    while chunk := await file.read(UPLOAD_CHUNK_BYTES):
        buffer.extend(chunk)
        if len(buffer) > max_size_bytes:
            raise HTTPException(
                status_code=status.HTTP_413_CONTENT_TOO_LARGE,
                detail=f"File size exceeds maximum of {settings.max_file_size_mb} MB",
            )
    content = bytes(buffer)

    # Save file and get ID
    filename = file.filename or "unnamed"
    file_id = storage.save_file(content, filename)

    return UploadFileResponse(
        file_id=file_id,
        filename=filename,
        size_bytes=len(content),
    )


@router.get("/files/{file_id}")
async def download_file(file_id: str) -> Response:
    """Download a previously uploaded file by its ID."""
    storage = get_file_storage()

    try:
        content, metadata = storage.get_file(file_id)
    except FileNotFoundError as exc:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"File with ID '{file_id}' not found",
        ) from exc

    return Response(
        content=content,
        media_type="application/octet-stream",
        headers={"Content-Disposition": _content_disposition(metadata.filename)},
    )


@router.get("/files", response_model=ListFilesResponse, status_code=status.HTTP_200_OK)
def list_files() -> ListFilesResponse:
    """List all uploaded files with their metadata."""
    storage = get_file_storage()
    files = storage.list_files()

    return ListFilesResponse(
        files=[
            FileMetadataResponse(
                file_id=f.file_id,
                filename=f.filename,
                size_bytes=f.size_bytes,
                upload_time=f.upload_time,
            )
            for f in files
        ]
    )


@router.delete("/files/{file_id}")
def delete_file(file_id: str) -> Response:
    """Delete a previously uploaded file by its ID."""
    storage = get_file_storage()

    if not storage.delete_file(file_id):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"File with ID '{file_id}' not found",
        )

    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post(
    "/sessions",
    response_model=CreateSessionResponse,
    status_code=status.HTTP_201_CREATED,
)
def create_session(req: CreateSessionRequest) -> CreateSessionResponse:
    """Create a long-lived code-executor pod with the given TTL.

    The pod is guaranteed to be torn down at or before the TTL expires, even
    if the API service crashes and restarts.
    """
    settings = get_settings()
    storage = get_file_storage()
    staged_files, _ = _resolve_uploaded_files(req.files, storage)

    try:
        info = get_executor().create_session(
            ttl_seconds=req.ttl_seconds,
            files=staged_files,
            cpu_time_limit_sec=settings.cpu_time_limit_sec,
            memory_limit_mb=settings.memory_limit_mb,
        )
    except NotImplementedError as exc:
        raise HTTPException(
            status_code=status.HTTP_501_NOT_IMPLEMENTED,
            detail=str(exc),
        ) from exc
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=str(exc),
        ) from exc

    return CreateSessionResponse(
        session_id=info.session_id,
        expires_at=info.expires_at,
    )


@router.delete("/sessions/{session_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_session(session_id: str) -> Response:
    """Tear down a session pod by ID."""
    try:
        deleted = get_executor().delete_session(session_id)
    except NotImplementedError as exc:
        raise HTTPException(
            status_code=status.HTTP_501_NOT_IMPLEMENTED,
            detail=str(exc),
        ) from exc

    if not deleted:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Session '{session_id}' not found",
        )

    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post(
    "/sessions/{session_id}/bash",
    response_model=BashExecResponse,
    status_code=status.HTTP_200_OK,
)
def session_exec_bash(session_id: str, req: BashExecRequest) -> BashExecResponse:
    """Run a bash command inside an existing session.

    The session pod has no network access (enforced at session creation), and
    that restriction continues to apply for every command run via this route.
    """
    settings = get_settings()
    if req.timeout_ms > settings.max_exec_timeout_ms:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"timeout_ms exceeds maximum of {settings.max_exec_timeout_ms} ms",
        )

    try:
        result = get_executor().execute_bash_in_session(
            session_id,
            cmd=req.cmd,
            timeout_ms=req.timeout_ms,
            max_output_bytes=settings.max_output_bytes,
        )
    except SessionNotFoundError as exc:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(exc),
        ) from exc
    except NotImplementedError as exc:
        raise HTTPException(
            status_code=status.HTTP_501_NOT_IMPLEMENTED,
            detail=str(exc),
        ) from exc

    return BashExecResponse(
        stdout=result.stdout,
        stderr=result.stderr,
        exit_code=result.exit_code,
        timed_out=result.timed_out,
        duration_ms=result.duration_ms,
    )
