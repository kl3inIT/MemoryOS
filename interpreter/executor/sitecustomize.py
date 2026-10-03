"""Executor start-up: matplotlib's writable config, chart capture, and guards that turn silent pandas mistakes in
the model's script into errors it can read and fix.

Matplotlib: executors run as an unprivileged user on a read-only root filesystem where only /tmp is writable.
Without this, matplotlib warns on stderr that its config directory is not writable and rebuilds the font cache
on every run.
"""

import importlib.machinery
import os
import shutil
import sys
import warnings

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
warnings.filterwarnings(
    "error",
    message="A value is being set on a copy of a DataFrame or Series through chained assignment",
    module=r"__main__\Z",
)


# Models told the user writes Vietnamese pass dayfirst=True by habit, also for ISO text such as 2024-05-01. pandas
# then parses it as year-day-month without a word: 2024-05-01 becomes 5 January, and a filter on 2024-03-05 finds
# nothing (a prompt evaluation on 2026-10-03: every wrong final answer came from this). For a call from the model's
# own script whose strings are all ISO, pd.to_datetime raises instead, which the model reads and fixes. Day-first
# text such as 05/03/2024, an explicit format other than "mixed", and calls from libraries behave as in pandas.
# Installed when pandas is first imported, and its helpers (inspect, functools, re: about 35 ms of start-up) are
# imported only then, so a run that never uses pandas pays nothing.
def _all_iso(arg: object, iso_date: object) -> bool:
    if isinstance(arg, str):
        values: list[object] = [arg]
    elif isinstance(arg, (list, tuple)):
        values = list(arg)
    elif getattr(arg, "ndim", None) == 1 and hasattr(arg, "tolist"):
        values = arg.tolist()
    else:
        return False
    texts = [value for value in values if isinstance(value, str) and value.strip()]
    return bool(texts) and all(iso_date.match(text) for text in texts)  # type: ignore[attr-defined]


def _guard_to_datetime(pandas: object) -> None:
    import functools
    import inspect
    import re

    iso_date = re.compile(r"\s*\d{4}-\d{1,2}-\d{1,2}(?:[ T].*)?\s*\Z")
    original = pandas.to_datetime  # type: ignore[attr-defined]
    signature = inspect.signature(original)

    @functools.wraps(original)
    def to_datetime(*args: object, **kwargs: object) -> object:
        if sys._getframe(1).f_globals.get("__name__") == "__main__":
            try:
                bound = signature.bind(*args, **kwargs).arguments
            except TypeError:
                bound = {}
            dayfirst_without_format = bound.get("dayfirst") is True and bound.get("format") in (None, "mixed")
            if dayfirst_without_format and _all_iso(bound.get("arg"), iso_date):
                raise ValueError(
                    "dayfirst=True does not apply to ISO dates such as '2024-05-01': they are year-month-day, and "
                    "pandas would read them as year-day-month. Remove dayfirst=True (or pass format='ISO8601')."
                )
        return original(*args, **kwargs)

    pandas.to_datetime = to_datetime  # type: ignore[attr-defined]


class _PandasImportHook:
    """Wraps pandas.to_datetime right after pandas finishes importing."""

    @staticmethod
    def find_spec(name: str, path: object = None, target: object = None) -> object:
        if name != "pandas":
            return None
        spec = importlib.machinery.PathFinder.find_spec(name, path)
        if spec is None or spec.loader is None:
            return spec
        execute = spec.loader.exec_module

        def exec_module(module: object) -> None:
            execute(module)
            try:
                _guard_to_datetime(module)
            except Exception:  # noqa: BLE001 - a guard that cannot install must never break pandas
                pass

        spec.loader.exec_module = exec_module  # type: ignore[method-assign]
        return spec


sys.meta_path.insert(0, _PandasImportHook())


# Figures a run leaves open become chart data plus PNG at exit (memoryos_charts.capture).
try:
    import atexit

    from memoryos_charts.capture import capture_open_figures

    atexit.register(capture_open_figures)
except ImportError:
    pass
