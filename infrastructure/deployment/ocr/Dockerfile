FROM quay.io/docling-project/docling-serve-cpu:v1.38.1@sha256:203c2ce82f19413bc3b83641962082165510352b9f93c1a840c0036be616eebf

USER 0
RUN dnf install -y --nodocs --setopt=install_weak_deps=False tesseract-langpack-vie-4.1.0-3.el9 \
    && dnf clean all
COPY --chmod=755 tesseract /usr/local/bin/tesseract
COPY --chown=1001:0 memoryos_docling /opt/app-root/src/memoryos_docling
ENV PYTHONDONTWRITEBYTECODE=1
USER 1001
CMD ["python", "-m", "memoryos_docling"]
