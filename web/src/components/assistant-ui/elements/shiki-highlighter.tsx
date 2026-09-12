// Adapted from assistant-ui elements-shiki-highlighter (MIT), 2026-09-12.
import { useShikiHighlighter } from "react-shiki";

export default function ShikiHighlighter({ code, language }: { code: string; language: string }) {
  const highlighted = useShikiHighlighter(
    code,
    language,
    { dark: "github-dark-default", light: "github-light-default" },
    // Named engine is cached by react-shiki. No WebAssembly / unsafe-eval CSP exception.
    { engine: "javascript", delay: 150, defaultColor: "light-dark()" },
  );
  return (
    <div className="aui-shiki-base [&_pre]:overflow-x-auto [&_pre]:rounded-b-xl [&_pre]:border [&_pre]:border-t-0 [&_pre]:border-border/50 [&_pre]:bg-muted/30! [&_pre]:p-3.5 [&_pre]:text-[13px] [&_pre]:leading-relaxed [&_.line]:px-0!">
      {highlighted ?? (
        <pre>
          <code>{code}</code>
        </pre>
      )}
    </div>
  );
}
