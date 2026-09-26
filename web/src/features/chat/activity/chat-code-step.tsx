import { useAuiState } from "@assistant-ui/react";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { HighlightedCode } from "@/components/assistant-ui/elements/code-renderers.aui";
import type { CodeRun } from "@/features/chat/interpreter/chat-code";
import type { ToolState } from "./chat-activity-labels";

/** The code run_python executed and the output it produced, streamed while it runs. */
export function ChatCodeStep({ toolCallId, state }: { toolCallId: string; state: ToolState }) {
  const ui = useAppTranslation();
  const run = useAuiState(
    (aui) =>
      (aui.message.metadata.custom.codeRuns as Record<string, CodeRun> | undefined)?.[toolCallId],
  );
  if (!run?.code) return null;
  // Onyx PythonToolRenderer: code, then Output, then Error, the generated file count, and a no-output note.
  return (
    <div className="flex flex-col gap-2 text-xs">
      <div className="text-xs [&_pre]:max-h-64 [&_pre]:rounded-lg! [&_pre]:border-t!">
        <HighlightedCode code={run.code.trim()} language="python" />
      </div>
      {run.stdout && (
        <section aria-label={ui("Kết quả")} className="rounded-lg bg-surface-subtle p-2.5">
          <p className="mb-1 font-medium text-content-muted">
            {state === "running" ? ui("Kết quả đang chạy") : ui("Kết quả")}
          </p>
          <pre className="max-h-64 overflow-auto whitespace-pre-wrap font-mono text-xs leading-relaxed text-content-primary">
            {run.stdout}
          </pre>
        </section>
      )}
      {run.stderr && (
        <section
          aria-label={ui("Lỗi")}
          className="rounded-lg border border-destructive/30 bg-destructive/5 p-2.5"
        >
          <p className="mb-1 font-medium text-destructive">{ui("Lỗi")}</p>
          <pre className="max-h-64 overflow-auto whitespace-pre-wrap font-mono text-xs leading-relaxed text-destructive">
            {run.stderr}
          </pre>
        </section>
      )}
      {run.files.length > 0 && (
        <p className="text-content-muted">
          {ui("Đã tạo {{count}} tệp", { count: run.files.length })}
        </p>
      )}
      {run.status !== "running" && !run.stdout && !run.stderr && (
        <p className="text-content-muted">{ui("Không có output")}</p>
      )}
    </div>
  );
}
