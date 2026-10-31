# Notice

`computer/` started on 2026-10-15 as a copy of `interpreter/executor/` (now `memoryos-interpreter/executor/`), the MemoryOS interpreter executor, which itself
started from the executor of [onyx-dot-app/python-sandbox](https://github.com/onyx-dot-app/python-sandbox) (MIT,
Copyright (c) 2025-present DanswerAI, Inc.; see [`memoryos-interpreter/NOTICE.md`](../memoryos-interpreter/NOTICE.md) and
[`memoryos-interpreter/LICENSE`](../memoryos-interpreter/LICENSE)).

- `computer/memoryos_charts` started from [e2b-charts](https://pypi.org/project/e2b-charts/) 1.0.0 (MIT, Copyright (c)
  2025 FOUNDRYLABS, INC.); its license is kept in `computer/memoryos_charts/LICENSE`.
- Changes from the executor: the image is `memoryos-sandbox-computer`; a `sandbox` user (uid 1000) owns `/workspace`; the
  virtual environment is `/opt/sandbox-venv`; added git, jq, ripgrep, fd, file, 7-Zip, bc, pandoc, LibreOffice
  Writer, Tesseract with Vietnamese data, ImageMagick, ffmpeg, Node with `pptxgenjs` 4.0.1, and the Python packages
  duckdb, plotly and pytesseract (pypdfium2 was already present).
