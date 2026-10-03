from __future__ import annotations

import json

import pytest
from fastapi.testclient import TestClient

from memoryos_interpreter.main import create_app


def test_numpy_pandas_matplotlib_stack() -> None:
    client = TestClient(create_app())

    code = """
import io
import json

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

data = np.arange(6, dtype=np.float64).reshape(3, 2)
df = pd.DataFrame(data, columns=['x', 'y'])

summary = {
    'shape': list(df.shape),
    'x_mean': float(df['x'].mean()),
    'y_total': float(df['y'].sum()),
}

fig, ax = plt.subplots()
ax.plot(df['x'], df['y'])
buf = io.BytesIO()
fig.savefig(buf, format='png')
plt.close(fig)

print(json.dumps({'summary': summary, 'png_bytes': len(buf.getvalue())}))
""".strip()

    response = client.post(
        "/v1/execute",
        json={
            "code": code,
            "stdin": None,
            "timeout_ms": 5000,
        },
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["stderr"] == ""
    assert payload["exit_code"] == 0
    assert payload["timed_out"] is False

    stdout = payload["stdout"].strip()
    result = json.loads(stdout)

    assert result["summary"]["shape"] == [3, 2]
    assert result["summary"]["x_mean"] == pytest.approx(2.0, rel=1e-9)
    assert result["summary"]["y_total"] == pytest.approx(9.0, rel=1e-9)
    assert result["png_bytes"] > 0


def test_matplotlib_creates_graph_and_returns_as_file() -> None:
    client = TestClient(create_app())

    code = """
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np

# Create sample data
x = np.linspace(0, 10, 100)
y = np.sin(x)

# Create the plot
fig, ax = plt.subplots(figsize=(8, 6))
ax.plot(x, y, 'b-', linewidth=2, label='sin(x)')
ax.set_xlabel('X axis')
ax.set_ylabel('Y axis')
ax.set_title('Sine Wave')
ax.legend()
ax.grid(True, alpha=0.3)

# Save the figure
fig.savefig('sine_wave.png', dpi=100, bbox_inches='tight')
plt.close(fig)

print('Graph saved successfully')
""".strip()

    response = client.post(
        "/v1/execute",
        json={
            "code": code,
            "stdin": None,
            "timeout_ms": 3000,
        },
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["stdout"] == "Graph saved successfully\n"
    assert payload["stderr"] == ""
    assert payload["exit_code"] == 0
    assert payload["timed_out"] is False

    # Verify the PNG file was created and returned
    files = payload.get("files")
    assert isinstance(files, list)

    # Find the sine_wave.png file
    png_file = None
    for file_entry in files:
        if isinstance(file_entry, dict) and file_entry.get("path") == "sine_wave.png":
            png_file = file_entry
            break

    assert png_file is not None, "sine_wave.png not found in response files"
    assert png_file["kind"] == "file"

    # Verify the file has a file_id
    file_id = png_file.get("file_id")
    assert isinstance(file_id, str)

    # Download the file and verify it's a valid PNG
    download_response = client.get(f"/v1/files/{file_id}")
    assert download_response.status_code == 200
    png_bytes = download_response.content

    # PNG files start with these magic bytes
    assert png_bytes[:8] == b"\x89PNG\r\n\x1a\n"

    # Verify the file has reasonable size (should be several KB for a plot)
    assert len(png_bytes) > 1000


def test_the_idioms_the_prompt_teaches_hold_on_the_executor_stack() -> None:
    # RUN_PYTHON_GUIDANCE in core's ChatPrompts names these versions and idioms. Each check
    # proves one claim, so a relock that changes what the model is told fails here: update
    # the prompt with it.
    client = TestClient(create_app())

    code = """
import io
import json
import sys

import cv2
import numpy as np
import openpyxl
import pandas as pd

checks = {}
checks['python'] = list(sys.version_info[:2])
checks['numpy'] = '.'.join(np.__version__.split('.')[:2])
checks['pandas'] = '.'.join(pd.__version__.split('.')[:2])
checks['opencv'] = int(cv2.__version__.split('.')[0])

# Text columns are dtype str, and the helper the prompt names recognises them.
text = pd.DataFrame({'a': ['x', 'y']})['a']
checks['str_dtype'] = str(text.dtype) == 'str' and pd.api.types.is_string_dtype(text)

# Copy-on-write: assigning back changes the frame; a chained assignment in this script raises
# (sitecustomize turns pandas' warning into an error for __main__ only).
df = pd.DataFrame({'a': [1.0, None]})
df['a'] = df['a'].fillna(0)
checks['assign_back'] = df['a'].tolist() == [1.0, 0.0]
frame = pd.DataFrame({'a': [1, 2]})
try:
    frame['a'][0] = 9
    checks['chained_raises'] = False
except pd.errors.ChainedAssignmentError:
    checks['chained_raises'] = frame['a'].tolist() == [1, 2]
frame.loc[frame['a'] == 1, 'a'] = 9
checks['loc_assign'] = frame['a'].tolist() == [9, 2]

# Month-end aliases, and the removed ones fail loudly.
checks['freq_me'] = len(pd.date_range('2024-01-01', periods=3, freq='ME')) == 3
try:
    pd.date_range('2024-01-01', periods=3, freq='M')
    checks['freq_m_rejected'] = False
except ValueError:
    checks['freq_m_rejected'] = True
hourly = pd.Series([1, 2], index=pd.date_range('2024-01-01', periods=2, freq='h'))
checks['resample'] = hourly.resample('D').sum().tolist() == [3]
checks['ffill'] = pd.Series([1.0, None]).ffill().tolist() == [1.0, 1.0]

# A workbook read back keeps its text column as str and its numbers as int64.
book = openpyxl.Workbook()
sheet = book.active
sheet.append(['name', 'amount'])
sheet.append(['a', 1])
sheet.append(['b', 2])
buffer = io.BytesIO()
book.save(buffer)
read = pd.read_excel(io.BytesIO(buffer.getvalue()))
checks['excel_dtypes'] = [str(read['name'].dtype), str(read['amount'].dtype)] == ['str', 'int64']
pivot = pd.DataFrame({'k': ['a', 'a', 'b'], 'v': [1, 2, 3]})
pivot = pivot.pivot_table(index='k', values='v', aggfunc='sum')
checks['pivot'] = pivot['v'].tolist() == [3, 3]

# numpy 2: scalars print with their type, and the removed aliases are gone.
checks['scalar_repr'] = repr(np.float64(1.5)) == 'np.float64(1.5)'
checks['scalar_item'] = json.dumps([np.float64(1.5).item()]) == '[1.5]'
checks['nan_alias_removed'] = not hasattr(np, 'NaN')
checks['trapezoid'] = float(np.trapezoid([1.0, 1.0])) == 1.0

# OpenCV 5 still encodes images; its machine-learning module left the main wheel.
checks['cv2_encode'] = bool(cv2.imencode('.png', np.zeros((4, 4, 3), np.uint8))[0])
checks['cv2_ml_absent'] = not hasattr(cv2, 'ml')
checks['cv2_haar_absent'] = not hasattr(cv2, 'CascadeClassifier')

print(json.dumps(checks))
""".strip()

    response = client.post("/v1/execute", json={"code": code, "stdin": None, "timeout_ms": 20000})

    assert response.status_code == 200
    payload = response.json()
    assert payload["exit_code"] == 0, payload["stderr"]
    checks = json.loads(payload["stdout"].strip())
    assert checks.pop("python") == [3, 14]
    assert (checks.pop("numpy"), checks.pop("pandas"), checks.pop("opencv")) == ("2.5", "3.0", 5)
    assert {name: ok for name, ok in checks.items() if not ok} == {}
