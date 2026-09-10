FROM quay.io/docling-project/docling-serve-cpu:v1.32.0@sha256:576fc2074ac77bcfbf3fe27633aa0dd89b452a170b2cd31689c8751e94d60f7a

USER 0
RUN dnf install -y --nodocs --setopt=install_weak_deps=False tesseract-langpack-vie-4.1.0-3.el9 \
    && dnf clean all
COPY --chmod=755 tesseract /usr/local/bin/tesseract
USER 1001
