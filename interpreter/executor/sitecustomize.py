"""Point matplotlib at a writable config directory seeded with the font cache built into the image.

Executors run as an unprivileged user on a read-only root filesystem where only /tmp is writable. Without this,
matplotlib warns on stderr that its config directory is not writable and rebuilds the font cache on every run.
"""

import os
import shutil

_BUILT = "/opt/matplotlib"
_RUNTIME = "/tmp/matplotlib"  # noqa: S108 - /tmp is the executor's only writable tmpfs

if "MPLCONFIGDIR" not in os.environ and os.path.isdir(_BUILT):
    try:
        if not os.path.isdir(_RUNTIME):
            shutil.copytree(_BUILT, _RUNTIME)
        os.environ["MPLCONFIGDIR"] = _RUNTIME
    except OSError:
        pass


# pandas 3 never applies a chained assignment (`df['a'][0] = 1`, `df['a'].fillna(0, inplace=True)`) and only
# warns, so a run reports results computed on unchanged data. In the model's own script (__main__) the warning
# is an error, a traceback the model fixes; inside libraries it stays a warning. Matched by message, so pandas
# is not imported on every start.
import warnings

warnings.filterwarnings(
    "error",
    message="A value is being set on a copy of a DataFrame or Series through chained assignment",
    module=r"__main__\Z",
)


# Figures a run leaves open become chart data plus PNG at exit (memoryos_charts.capture).
try:
    import atexit

    from memoryos_charts.capture import capture_open_figures

    atexit.register(capture_open_figures)
except ImportError:
    pass
