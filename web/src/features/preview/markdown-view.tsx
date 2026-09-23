import { Streamdown } from "streamdown";

import { markdownRendering } from "@/components/assistant-ui/elements/markdown-text";
import { useAppTranslation } from "@/i18n/use-app-translation";

/**
 * A Markdown original read as the document it is: headings, paragraphs and tables, on the document sheet
 * the other readers use. It renders through the same pipeline as an assistant answer, so the same file
 * reads the same whether Chat quotes it or the library opens it.
 */
export function MarkdownView({ text, truncated = false }: { text: string; truncated?: boolean }) {
  const ui = useAppTranslation();
  const { preprocess, ...rendering } = markdownRendering;

  return (
    <div className="mx-auto min-h-full w-full max-w-[78ch] bg-surface-document px-8 py-10 text-content-primary">
      <div className="aui-md">
        <Streamdown mode="static" {...rendering}>
          {preprocess(text)}
        </Streamdown>
      </div>
      {truncated && (
        <p className="mt-6 text-xs text-content-muted">{ui("Chỉ hiển thị 1 MB đầu của tệp.")}</p>
      )}
    </div>
  );
}
