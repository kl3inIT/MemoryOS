FROM quay.io/docling-project/docling-serve-cpu:v1.36.0@sha256:225c8586e20d5d0fc6811a9e0e044fa602bcc4393f00389009bad42d6787b58f

USER 0
RUN dnf install -y --nodocs --setopt=install_weak_deps=False tesseract-langpack-vie-4.1.0-3.el9 \
    && dnf clean all
COPY --chmod=755 tesseract /usr/local/bin/tesseract
COPY --chown=1001:0 memoryos_docling /opt/app-root/src/memoryos_docling
ENV PYTHONDONTWRITEBYTECODE=1
USER 1001
CMD ["python", "-m", "memoryos_docling"]
