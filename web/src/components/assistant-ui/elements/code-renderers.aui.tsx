// assistant-ui runtime-aware renderer composition; do not tokenize partial streams.
import { lazy, Suspense } from "react";
import { ErrorBoundary } from "react-error-boundary";
import { useAuiState } from "@assistant-ui/react";
import type { SyntaxHighlighterProps } from "@assistant-ui/react-markdown";

const Shiki = lazy(() => import("./shiki-highlighter"));
const Mermaid = lazy(() => import("./mermaid-diagram"));

function PlainCode({ code }: { code: string }) {
  return (
    <pre className="overflow-x-auto rounded-b-xl border border-t-0 border-border/50 bg-muted/30 p-3.5 text-[13px] leading-relaxed">
      <code>{code}</code>
    </pre>
  );
}

export function SyntaxHighlighter({ code, language }: SyntaxHighlighterProps) {
  const streaming = useAuiState((s) => s.optional.part?.status.type === "running");
  const fallback = <PlainCode code={code} />;
  if (streaming || code.length > 100_000) return fallback;
  return (
    <ErrorBoundary fallback={fallback} resetKeys={[code, language]}>
      <Suspense fallback={fallback}>
        <Shiki code={code} language={language} />
      </Suspense>
    </ErrorBoundary>
  );
}

export function MermaidDiagram({ code }: SyntaxHighlighterProps) {
  const streaming = useAuiState((s) => s.optional.part?.status.type === "running");
  const fallback = <PlainCode code={code} />;
  if (streaming) return fallback;
  return (
    <ErrorBoundary fallback={fallback} resetKeys={[code]}>
      <Suspense fallback={fallback}>
        <Mermaid code={code} />
      </Suspense>
    </ErrorBoundary>
  );
}
