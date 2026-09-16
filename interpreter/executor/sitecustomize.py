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
