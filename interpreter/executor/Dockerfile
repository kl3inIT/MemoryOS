FROM python:3.11-slim

ARG VCS_REF=unknown
ARG BUILD_DATE=unknown

LABEL org.opencontainers.image.title="MemoryOS Interpreter Executor" \
      org.opencontainers.image.source="https://github.com/kl3inIT/MemoryOS" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"

# Install build prerequisites and uv for dependency management
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential \
        curl \
        gfortran \
        iptables \
        libfreetype6-dev \
        liblapack-dev \
        libopenblas-dev \
        libpng-dev \
        pkg-config \
        fontconfig \
        fonts-crosextra-caladea \
        fonts-crosextra-carlito \
        fonts-dejavu-core \
        fonts-liberation \
        fonts-noto-core \
        libreoffice-calc-nogui \
        poppler-utils \
        qpdf \
        sqlite3 \
        unzip \
        zip \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/* \
    && curl -LsSf https://astral.sh/uv/install.sh | sh \
    && rm -rf /tmp/*

ENV PATH="/root/.local/bin:${PATH}"

# Pre-install the scientific stack via uv using a dedicated lockfile
COPY pyproject.toml uv.lock /tmp/executor/
RUN uv sync --directory /tmp/executor \
        --no-dev \
        --frozen \
    && mv /tmp/executor/.venv /opt/executor-venv \
    && rm -rf /tmp/executor

ENV VIRTUAL_ENV="/opt/executor-venv"
ENV PATH="${VIRTUAL_ENV}/bin:${PATH}"

# Executors run as an unprivileged user on a read-only root, so the matplotlib font cache is built here
# (after the system fonts above) instead of on every execution.
# Fontconfig reads the system cache built above and needs a writable user cache: /tmp is the executor's tmpfs.
# sitecustomize.py copies this matplotlib cache to /tmp at interpreter start.
ENV LANG=C.UTF-8 \
    MPLBACKEND=Agg \
    XDG_CACHE_HOME=/tmp/.cache
COPY sitecustomize.py /opt/executor-venv/lib/python3.11/site-packages/sitecustomize.py
# Chart extraction owned by MemoryOS, started from E2B's MIT e2b-charts (see NOTICE.md).
COPY memoryos_charts /opt/executor-venv/lib/python3.11/site-packages/memoryos_charts
RUN mkdir -p /opt/matplotlib \
    && MPLCONFIGDIR=/opt/matplotlib python -c "import matplotlib.font_manager as fm; fm.fontManager.findfont('DejaVu Sans')" \
    && chmod -R a+rX /opt/matplotlib

# recalc-xlsx: formula values for workbooks written with openpyxl or xlsxwriter (LibreOffice Calc, installed above).
COPY recalc_xlsx.py /usr/local/bin/recalc-xlsx
RUN chmod 0755 /usr/local/bin/recalc-xlsx
