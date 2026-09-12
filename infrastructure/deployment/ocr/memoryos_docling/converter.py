"""Keep upstream conversion options/caching and add source-frame provenance."""

from docling.datamodel.document import ConversionResult
from docling.datamodel.service.options import ConvertDocumentsOptions
from docling.document_converter import PdfFormatOption
from docling.pipeline.standard_pdf_pipeline import StandardPdfPipeline
from docling_core.types.doc.common.meta import BaseMeta
from docling_jobkit.convert.manager import DoclingConverterManager

from .backend import (
    BACKENDS,
    OrientationBackend,
    OrientationBackendOptions,
    SequentialOrientationBackend,
)


class OrientationPipeline(StandardPdfPipeline):
    def _assemble_document(self, conv_res: ConversionResult) -> ConversionResult:
        result = super()._assemble_document(conv_res)
        backend = result.input._backend
        if isinstance(backend, OrientationBackend) and backend.orientations:
            meta = result.document.body.meta or BaseMeta()
            meta.set_custom_field("memoryos", "orientation", backend.orientations)
            result.document.body.meta = meta
        return result


class OrientationConverterManager(DoclingConverterManager):
    def get_pdf_pipeline_opts(
        self, request: ConvertDocumentsOptions
    ) -> PdfFormatOption:
        option = super().get_pdf_pipeline_opts(request)
        if option.pipeline_cls is not StandardPdfPipeline or not request.do_ocr:
            return option
        for kind, backend in BACKENDS.items():
            if option.backend is not backend:
                continue
            return option.model_copy(
                update={
                    "backend": OrientationBackend
                    if backend.supports_random_page_access
                    else SequentialOrientationBackend,
                    "pipeline_cls": OrientationPipeline,
                    "backend_options": OrientationBackendOptions.model_validate(
                        {
                            **(
                                option.backend_options.model_dump()
                                if option.backend_options
                                else {}
                            ),
                            "delegate_kind": kind,
                            "delegate_options": option.backend_options,
                            "preprocessing_seconds": min(
                                30.0, request.document_timeout or 30.0
                            ),
                        }
                    ),
                }
            )
        raise ValueError("Unsupported PDF backend for orientation preprocessing")
