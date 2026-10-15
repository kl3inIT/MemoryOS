FROM python:3.14-slim@sha256:a2b82f3c48559aa0a8446d9af49826b6e2b2016f4cd2afabfe6013ec53729170

ARG VCS_REF=unknown
ARG BUILD_DATE=unknown

LABEL org.opencontainers.image.title="MemoryOS Sandbox Computer" \
      org.opencontainers.image.source="https://github.com/kl3inIT/MemoryOS" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"

# The interpreter executor's packages (MEM-110), then the tools Dust, Claude's code execution or Onyx Craft ship for
# office work (MEM-270 design, "Image contents"): git, jq, ripgrep, fd, file, 7-Zip, bc, pandoc, LibreOffice Writer,
# Tesseract with Vietnamese, ImageMagick, ffmpeg and Node.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bc \
        build-essential \
        ca-certificates \
        curl \
        fd-find \
        ffmpeg \
        file \
        fontconfig \
        fonts-crosextra-caladea \
        fonts-crosextra-carlito \
        fonts-dejavu-core \
        fonts-liberation \
        fonts-noto-core \
        gfortran \
        git \
        imagemagick \
        jq \
        libfreetype6-dev \
        liblapack-dev \
        libopenblas-dev \
        libpng-dev \
        libreoffice-calc-nogui \
        libreoffice-impress-nogui \
        libreoffice-writer-nogui \
        nodejs \
        npm \
        p7zip-full \
        pandoc \
        pkg-config \
        poppler-utils \
        procps \
        qpdf \
        ripgrep \
        sqlite3 \
        tesseract-ocr \
        tesseract-ocr-vie \
        unzip \
        zip \
    && ln -s /usr/bin/fdfind /usr/local/bin/fd \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/*

# uv from its release image pinned by digest, as astral documents for Docker, instead of piping an install script.
COPY --from=ghcr.io/astral-sh/uv:0.13.1@sha256:4a1f3666816dafadd20ceb71eac9a7d56d7ef30279f7d64048519ab901ada336 /uv /uvx /usr/local/bin/

# pptxgenjs builds presentations from JavaScript, as Dust and Onyx Craft use it; same version as Dust.
RUN npm install --global --no-audit --no-fund pptxgenjs@4.0.1 \
    && npm cache clean --force
ENV NODE_PATH=/usr/local/lib/node_modules

# The Python stack from a dedicated lockfile.
COPY pyproject.toml uv.lock /tmp/sandbox/
RUN uv sync --directory /tmp/sandbox \
        --no-dev \
        --frozen \
    && mv /tmp/sandbox/.venv /opt/sandbox-venv \
    && rm -rf /tmp/sandbox

ENV VIRTUAL_ENV="/opt/sandbox-venv"
ENV PATH="${VIRTUAL_ENV}/bin:${PATH}"

# Commands run as user 1000 (the MEM-270 probe found OpenSandbox runs them as root otherwise) in /workspace, the
# directory the workspace archive covers. The matplotlib font cache is built here, after the system fonts, instead
# of on every run; sitecustomize.py copies it to /tmp at interpreter start.
ENV LANG=C.UTF-8 \
    MPLBACKEND=Agg \
    XDG_CACHE_HOME=/tmp/.cache
COPY sitecustomize.py /opt/sandbox-venv/lib/python3.14/site-packages/sitecustomize.py
# Chart extraction owned by MemoryOS, started from E2B's MIT e2b-charts (see NOTICE.md).
COPY memoryos_charts /opt/sandbox-venv/lib/python3.14/site-packages/memoryos_charts
RUN mkdir -p /opt/matplotlib \
    && MPLCONFIGDIR=/opt/matplotlib python -c "import matplotlib.font_manager as fm; fm.fontManager.findfont('DejaVu Sans')" \
    && chmod -R a+rX /opt/matplotlib \
    && groupadd --gid 1000 sandbox \
    && useradd --uid 1000 --gid 1000 --home-dir /workspace --no-create-home --shell /bin/bash sandbox \
    && install -d -o 1000 -g 1000 -m 0755 /workspace

# Models trained on ChatGPT's Code Interpreter write outputs to /mnt/data.
RUN mkdir -p /mnt && ln -s /workspace /mnt/data

# recalc-xlsx: formula values for workbooks written with openpyxl or xlsxwriter (LibreOffice Calc).
COPY recalc_xlsx.py /usr/local/bin/recalc-xlsx
# check-docx: the formatting faults a model cannot see in the document it just wrote (python-docx).
COPY check_docx.py /usr/local/bin/check-docx
# pptx-to-pdf: the Chat preview of generated presentations (MEM-111, LibreOffice Impress).
COPY pptx_to_pdf.py /usr/local/bin/pptx-to-pdf
RUN chmod 0755 /usr/local/bin/recalc-xlsx /usr/local/bin/check-docx /usr/local/bin/pptx-to-pdf

WORKDIR /workspace
