import uvicorn
from docling_serve.logging_config import setup_logging
from docling_serve.settings import docling_serve_settings, uvicorn_settings


def main() -> None:
    """Start the MemoryOS Docling service with the configured logging."""
    settings = docling_serve_settings
    setup_logging(
        log_format=settings.log_format.value,
        log_level=settings.log_level.value if settings.log_level else "INFO",
        header_prefix=settings.log_header_prefix,
    )
    uvicorn.run(
        "memoryos_docling.app:create_app",
        factory=True,
        log_config=None,
        **uvicorn_settings.model_dump(),
    )


if __name__ == "__main__":
    main()
