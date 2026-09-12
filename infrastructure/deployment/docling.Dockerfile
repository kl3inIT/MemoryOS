FROM quay.io/docling-project/docling-serve-cpu:v1.32.0@sha256:576fc2074ac77bcfbf3fe27633aa0dd89b452a170b2cd31689c8751e94d60f7a

USER 0
RUN dnf install -y --setopt=install_weak_deps=False tesseract-langpack-vie-4.1.0-3.el9 \
    && dnf clean all
RUN printf '%s\n' '#!/bin/sh' 'OMP_THREAD_LIMIT=1 exec /usr/bin/tesseract "$@"' \
    > /usr/local/bin/tesseract \
    && chmod 0755 /usr/local/bin/tesseract
USER 1001
