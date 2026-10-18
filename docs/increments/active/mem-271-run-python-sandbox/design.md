# MEM-271: `run_python` in the conversation's sandbox

[MEM-271](https://linear.app/memory-os/issue/MEM-271) moves `run_python` from `memoryos-interpreter` to the
conversation's OpenSandbox sandbox (MEM-270) and retires the interpreter. The decision and its reasons are in the
MEM-270 design, ["`run_python` and the interpreter"](../mem-270-chat-sandbox/design.md#run_python-and-the-interpreter):
Claude and ChatGPT run Python, bash and files in one container per conversation, no reference falls back from one
runtime to another, and `run_python` runs only in the sandbox, with no switch back (owner, 2026-10-18). This page
settles how.

## References

| Reference | What it does | Taken |
| --- | --- | --- |
| ChatGPT data analysis | Python in the conversation's container; uploads under `/mnt/data`, which later calls and turns still see; a file is offered to the user when the model links it | One container, files that persist, uploads under one directory |
| Claude code execution | One container per conversation with bash, Python and a file editor; files written by one call are there for the next | `run_python` and `bash` share `/workspace` |
| Onyx 40eb240df `python_tool.py` | Stages chat files and the source files behind search hits, runs with a fixed timeout, returns every file the run left in its directory, captures nothing else; its executor wraps the code so a bare last expression prints, as in Jupyter (`wrap_last_line_interactive`) | Staging order and caps, result JSON, the last-line wrapper, generated files returned to the user, all as today |
| Onyx Craft | Converts a deck to PDF inside its sandbox | Presentation previews in a sandbox |

## How a call runs

1. **Files in.** The turn's attachments are copied into `/workspace/attachments` by the same once-a-turn step the
   sandbox tools use; the source files behind this turn's search hits go to `/workspace/.memoryos/search` for that run
   only: their authority is checked when they are copied, so they are removed before and after each run and the
   workspace archive, which leaves out `.memoryos/`, never holds them (the interpreter forgot them after each run). The staging order,
   the 25-file and 100 MiB caps and the notice are today's (Onyx). A file already there with the same size is kept,
   so the upload cache goes.
2. **The code.** It is written to `/workspace/.memoryos/run/<id>/main.py` inside Onyx's last-line wrapper, then run
   from `/workspace` as `setsid timeout -k 5 60 python3 main.py`, as `bash` runs its commands: the timeout kills the
   whole process group, exit 124 is a timeout, and a written file stops at 100 MiB.
3. **Files out.** A marker file is touched before the run; every regular file under `/workspace` newer than it is
   new or changed by this run and is handed to the user as today (a `chat_file_artifact` row and a Library
   artifact), at most 25 files of at most 25 MiB each. `attachments/`, `.memoryos/` and the directories the
   workspace archive leaves out (`node_modules`, `.venv`, `__pycache__`, `.next`) are not searched. Files an earlier
   call or turn wrote stay in the workspace and are not handed over again.
4. **Charts.** The computer image's `sitecustomize.py` captures figures left open into `/workspace/.memoryos-charts`,
   as the executor did; the directory is emptied before each run and read after it, so charts are those of this run.
5. **Output.** stdout and stderr are read when the run ends and shown in the timeline then, as `bash` shows them;
   the interpreter streamed them while the code ran. A run lasts at most 60 seconds, so live output is not kept.

The result JSON (`stdout`, `stderr`, `exit_code`, `timed_out`, `generated_files`, `error`, `staging_notice`,
`charts`) and the file reminder do not change, so past turns replay and the web shows runs as before.

## What changes for the model

The `run_python` guidance keeps Onyx's text and the MemoryOS lines, except what the sandbox makes untrue:

- The conversation's files are in `attachments/` and the search hits' files in `.memoryos/search/` for the run, not in the current
  directory.
- The paragraph that every call is a fresh, stateless sandbox goes: files persist across calls and turns, as for
  `bash`; variables and imports still do not.
- Network access is the administrator's allowlist, and a package can be added with `uv pip install` through `bash`.
- Limits are the sandbox's: 1 CPU and 1 GiB of memory by default, 60 seconds per call, no separate CPU-time cap.

The Sandbox guidance's line that `run_python` "runs in a separate, stateless sandbox that cannot see `/workspace`"
goes.

## Where it is offered

`run_python` is offered exactly when the sandbox tools are: the agent has `code_interpreter`, the Tenant's switch is
on and the deployment has a sandbox (`SandboxService.available()`). The Tenant switch can be turned on only where the
deployment has a sandbox, instead of an interpreter. The administrator page's health check asks the sandbox server
(OpenSandbox's `/health`), not the interpreter.

## Presentation previews

`PresentationPreviewService` converts a generated deck with `pptx-to-pdf` in a short-lived sandbox that belongs to no
conversation: started with the managed and Tenant labels, the deck written, the command run, the PDF read, the sandbox
killed in a `finally`. Previews are rare and a conversion takes about 15 seconds, so they are not counted in the
running bound; at most two run at once per API process and a third answers busy, as the interpreter's busy answer
does today. A sandbox a crash leaves behind has no row, so the Worker's reconciliation kills it after its grace.

## Quality gate

`tools/interpreter-eval` runs its ten tasks with the computer image as the candidate's runtime and the sandbox
guidance, against the executor image and today's guidance. The switch merges only on an equal or better score
(MEM-271 plan, step 2).

## What goes

The switch removes the Java client (`InterpreterClient`, `InterpreterApi`, the interpreter's base URL and key) and
its tests. `InterpreterService` stays: it owns the Tenant switch and the generated files, which the sandbox tools
already use. The Compose service, executor image, `images.env`, CI publication, `deploy.sh`,
`prune-release-images.sh`, the Dependabot entries and their infrastructure tests go in a separate change after the
switch has run on staging and production; `interpreter/` is then renamed `memoryos-interpreter/` and marked not built.

## Not taken

- A `PythonRunner` interface or a configuration to run on the interpreter (owner, 2026-10-18).
- A Jupyter kernel that keeps variables between calls, as ChatGPT has: a separate change if the evaluation shows it
  matters.
- Asking the model to publish `run_python` files with `publish_file`: the tool keeps handing over what it writes, as
  Onyx and today.
