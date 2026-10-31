"""Save the figures a run leaves open as chart data plus PNG, as E2B returns an ``e2b/chart`` result.

The executor runs a plain Python process without a Jupyter display hook, so ``sitecustomize`` calls
:func:`capture_open_figures` at interpreter exit. Each open figure becomes ``chart-{n}.png`` and, when its axes
are recognised, ``chart-{n}.json`` under :data:`CHART_DIR` in the workspace. The Java tool reads that directory
apart from the model's own files.
"""

from __future__ import annotations

import os
import sys

CHART_DIR = ".memoryos-charts"
MAX_FIGURES = 10
MAX_JSON_BYTES = 256 * 1024


def capture_open_figures(workspace: str | None = None) -> None:
    # Only a run that imported pyplot can have figures; never import matplotlib for other runs.
    pyplot = sys.modules.get("matplotlib.pyplot")
    if pyplot is None:
        return
    try:
        numbers = pyplot.get_fignums()[:MAX_FIGURES]
        if not numbers:
            return
        target = os.path.join(workspace or os.getcwd(), CHART_DIR)
        os.makedirs(target, exist_ok=True)
        for index, number in enumerate(numbers, start=1):
            figure = pyplot.figure(number)
            if not figure.get_axes():
                continue
            figure.savefig(os.path.join(target, f"chart-{index}.png"), dpi=150, bbox_inches="tight")
            data = _chart_json(figure)
            if data is not None:
                with open(os.path.join(target, f"chart-{index}.json"), "w", encoding="utf-8") as out:
                    out.write(data)
    except Exception as failure:  # noqa: BLE001 - a capture must never change the run's outcome
        print(f"Chart capture failed: {type(failure).__name__}: {failure}", file=sys.stderr)


def _chart_json(figure) -> str | None:  # noqa: ANN001 - matplotlib Figure, imported lazily
    try:
        from memoryos_charts.main import chart_figure_to_chart

        chart = chart_figure_to_chart(figure)
    except Exception:  # noqa: BLE001 - an unrecognised figure keeps only its PNG
        return None
    if chart is None:
        return None
    data = chart.model_dump_json()
    return data if len(data.encode("utf-8")) <= MAX_JSON_BYTES else None
