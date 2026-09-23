import { HighlightedCode } from "@/components/assistant-ui/elements/code-renderers.aui";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { codeLanguage, type PreviewKind } from "./preview-kind";

/** Source, plain text and Markdown read as highlighted source; JSON is reformatted when it was read whole. */
export function TextView({
  text,
  filename,
  kind,
  truncated = false,
}: {
  text: string;
  filename: string;
  kind: Extract<PreviewKind, "code" | "text" | "markdown">;
  truncated?: boolean;
}) {
  const ui = useAppTranslation();
  const language = codeLanguage(filename, kind);
  let shown = text;
  if (language === "json" && !truncated) {
    try {
      shown = JSON.stringify(JSON.parse(text), null, 2);
    } catch {
      shown = text;
    }
  }
  return (
    <div className="min-h-full bg-surface-sunken p-4 text-sm">
      <HighlightedCode code={shown} language={language} />
      {truncated && (
        <p className="mt-3 text-xs text-content-muted">{ui("Chỉ hiển thị 1 MB đầu của tệp.")}</p>
      )}
    </div>
  );
}
