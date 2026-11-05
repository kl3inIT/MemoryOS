FROM quay.io/docling-project/docling-serve-cpu:v1.38.2@sha256:7a480a1604fa3b42efeddc7db9527690d9f9bc903226136013e2bfe6b1a4dfb0

USER 0
RUN dnf install -y --nodocs --setopt=install_weak_deps=False tesseract-langpack-vie-4.1.0-3.el9 \
    && dnf clean all
COPY --chmod=755 tesseract /usr/local/bin/tesseract
COPY --chown=1001:0 memoryos_docling /opt/app-root/src/memoryos_docling
ENV PYTHONDONTWRITEBYTECODE=1
USER 1001
CMD ["python", "-m", "memoryos_docling"]
