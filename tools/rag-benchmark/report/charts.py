"""Draws the report's benchmark charts as PNG from `results/`, with the same figures as the workbook.

    uv run --with matplotlib python report/charts.py <out dir>

fig-stages.png: each stage on its own, in pipeline order, with its 95% interval.
fig-layers.png: the stages by the text layer of the gold documents.
fig-retrieval.png: hybrid search against keyword only and vector only.
fig-differences.png: paired differences with their intervals (retrieval and step ablation).
"""

from __future__ import annotations

import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent))

import compare  # noqa: E402
import summary  # noqa: E402

# Validated categorical slots (light surface), in fixed order; ink and grid stay neutral.
SERIES = ["#2a78d6", "#eb6834", "#1baf7a"]
INK, MUTED, GRID, SURFACE = "#0b0b0b", "#52514e", "#e4e3df", "#ffffff"
WIDTH = 6.3  # inches: the text width of an A4 page in the report


def style() -> None:
    plt.rcParams.update(
        {
            "font.family": "Calibri",
            "font.size": 10,
            "axes.edgecolor": GRID,
            "axes.labelcolor": MUTED,
            "axes.titlesize": 11,
            "axes.titleweight": "bold",
            "axes.titlecolor": INK,
            "axes.titlelocation": "left",
            "xtick.color": MUTED,
            "ytick.color": INK,
            "figure.facecolor": SURFACE,
            "axes.facecolor": SURFACE,
            "savefig.dpi": 300,
        }
    )


def finish(ax: plt.Axes, percent: bool = True) -> None:
    ax.grid(axis="x", color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    for side in ("top", "right", "left"):
        ax.spines[side].set_visible(False)
    ax.tick_params(axis="y", length=0)
    if percent:
        ax.set_xlim(0, 100)
        ax.set_xticks(range(0, 101, 20))
        ax.set_xticklabels([f"{t}%" for t in range(0, 101, 20)])


def stages(data: dict, out: Path) -> None:
    rows = data["stages"]
    fig, ax = plt.subplots(figsize=(WIDTH, 0.42 * len(rows) + 1.0))
    for i, (_, _, (p, lo, hi)) in enumerate(rows):
        ax.plot([lo * 100, hi * 100], [i, i], color=SERIES[0], linewidth=2, solid_capstyle="round")
        ax.plot(
            p * 100,
            i,
            "o",
            color=SERIES[0],
            markersize=8,
            markeredgecolor=SURFACE,
            markeredgewidth=2,
        )
        ax.text(hi * 100 + 1.5, i, f"{p * 100:.1f}%", va="center", color=INK, fontsize=9)
    ax.set_yticks(range(len(rows)))
    ax.set_yticklabels([f"{stage}  {name}" for stage, name, _ in rows])
    ax.invert_yaxis()
    finish(ax)
    ax.set_title("Each stage of the RAG flow on its own (grounded mode)")
    ax.set_xlabel("Rate, with its 95% interval")
    fig.tight_layout()
    fig.savefig(out)
    plt.close(fig)


def grouped(
    groups: dict[str, dict[str, compare.Rate]],
    names: dict[str, str],
    heading: str,
    out: Path,
) -> None:
    """Horizontal bars: one group per measure, one bar per series, the value written at its end."""
    series = list(names)
    bar = 0.26
    fig, ax = plt.subplots(figsize=(WIDTH, 0.95 * len(groups) + 1.2))
    for g, values in enumerate(groups.values()):
        for s, key in enumerate(series):
            p = values[key][0] * 100
            y = g + (s - (len(series) - 1) / 2) * (bar + 0.03)
            ax.barh(y, p, height=bar, color=SERIES[s], edgecolor=SURFACE, linewidth=1)
            ax.text(p + 1, y, f"{p:.1f}%", va="center", fontsize=8.5, color=INK)
    ax.set_yticks(range(len(groups)))
    ax.set_yticklabels(list(groups))
    ax.invert_yaxis()
    finish(ax)
    ax.set_title(heading)
    handles = [plt.Rectangle((0, 0), 1, 1, color=SERIES[i]) for i in range(len(series))]
    ax.legend(
        handles,
        [names[k] for k in series],
        loc="upper center",
        frameon=False,
        ncol=len(series),
        bbox_to_anchor=(0.5, -0.12 if len(groups) > 3 else -0.18),
    )
    fig.tight_layout()
    fig.savefig(out)
    plt.close(fig)


def differences(data: dict, out: Path) -> None:
    rows: list[tuple[str, compare.Rate]] = []
    for other, short in (("retrieval-keyword", "keyword"), ("retrieval-vector", "vector")):
        for metric, name in compare.RANKING.items():
            rows.append((f"Hybrid vs {short}: {name}", data["retrieval_diff"][(metric, other)]))
    for name, label in list(compare.VARIANTS.items())[1:]:
        rows.append((f"{label} vs V0: rows passing", data["ablation_diff"][name]))
    fig, ax = plt.subplots(figsize=(WIDTH, 0.36 * len(rows) + 1.3))
    for i, (_, (p, lo, hi)) in enumerate(rows):
        shown = lo > 0 or hi < 0
        ax.plot([lo * 100, hi * 100], [i, i], color=SERIES[0], linewidth=2, solid_capstyle="round")
        ax.plot(
            p * 100,
            i,
            "o",
            markersize=8,
            markeredgewidth=2,
            color=SERIES[0] if shown else SURFACE,
            markeredgecolor=SERIES[0],
        )
        ax.text(
            hi * 100 + 0.6,
            i,
            f"{p * 100:+.1f} [{lo * 100:+.1f}, {hi * 100:+.1f}]",
            va="center",
            fontsize=8.5,
            color=INK,
        )
    ax.axvline(0, color=MUTED, linewidth=1)
    ax.axhline(7.5, color=GRID, linewidth=1)
    ax.set_yticks(range(len(rows)))
    ax.set_yticklabels([r[0] for r in rows])
    ax.invert_yaxis()
    finish(ax, percent=False)
    ax.set_xlim(-8, 27)
    ax.set_xlabel(
        "Paired difference in percentage points, with its 95% interval\n"
        "Filled: the interval excludes zero. Hollow: no measured difference"
    )
    ax.set_title("Paired differences: search modes and step ablation")
    fig.tight_layout()
    fig.savefig(out)
    plt.close(fig)


def draw(out: Path, data: dict) -> None:
    out.mkdir(parents=True, exist_ok=True)
    style()
    stages(data, out / "fig-stages.png")
    grouped(
        data["by_layer"],
        {"text": "Text", "mixed": "Mixed", "scanned": "Scanned"},
        "Every stage by the text layer of the gold documents",
        out / "fig-layers.png",
    )
    grouped(
        {compare.RANKING[m]: v for m, v in data["retrieval"].items()},
        compare.SEARCHES,
        "Retrieval: hybrid search against keyword only and vector only",
        out / "fig-retrieval.png",
    )
    differences(data, out / "fig-differences.png")


def main() -> None:
    out = Path(sys.argv[1])
    draw(out, compare.comparisons(compare.load_runs(), summary.text_layers()))
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
