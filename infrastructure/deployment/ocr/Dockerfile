FROM quay.io/docling-project/docling-serve-cpu:v1.37.0@sha256:6eda9c524992147c1b0b06a1a9be4894bd68b1f43c86d92c32f996c4c60d18a3

USER 0
RUN dnf install -y --nodocs --setopt=install_weak_deps=False tesseract-langpack-vie-4.1.0-3.el9 \
    && dnf clean all
COPY --chmod=755 tesseract /usr/local/bin/tesseract
COPY --chown=1001:0 memoryos_docling /opt/app-root/src/memoryos_docling
ENV PYTHONDONTWRITEBYTECODE=1
USER 1001
CMD ["python", "-m", "memoryos_docling"]
