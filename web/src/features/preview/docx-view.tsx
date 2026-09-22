import { Loader2 } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { sanitizeDocxHtml } from "./preview-kind";

/**
 * A Word document rendered by docx-preview, sanitized before it reaches the page. The pages stay on
 * `surface-document`, which is white in both themes, because a document is authored on white and a citation
 * highlight multiplies onto it.
 */
export function DocxView({
  blob,
  onLoad,
}: {
  blob: Blob;
  onLoad: (result: { words: number; text: string }) => void;
}) {
  const body = useRef<HTMLDivElement>(null);
  const styles = useRef<HTMLDivElement>(null);
  const onLoadRef = useRef(onLoad);
  const [state, setState] = useState<"rendering" | "done" | "failed">("rendering");
  useEffect(() => {
    onLoadRef.current = onLoad;
  }, [onLoad]);
  useEffect(() => {
    if (!body.current || !styles.current) return undefined;
    let current = true;
    const bodyElement = body.current;
    const styleElement = styles.current;
    let adopted: CSSStyleSheet[] = [];
    void (async () => {
      try {
        const { renderAsync } = await import("docx-preview");
        // Render detached, then attach only sanitized markup and library <style> elements (Onyx sanitizeDocxHtml).
        const renderedBody = document.createElement("div");
        const renderedStyles = document.createElement("div");
        await renderAsync(blob, renderedBody, renderedStyles, {
          className: "docx",
          inWrapper: false,
          ignoreWidth: false,
          ignoreHeight: false,
          ignoreFonts: false,
          breakPages: true,
          useBase64URL: true,
          renderHeaders: true,
          renderFooters: true,
          renderFootnotes: true,
          renderEndnotes: true,
        });
        if (!current) return;
        bodyElement.innerHTML = sanitizeDocxHtml(renderedBody.innerHTML);
        // The deployment CSP (style-src 'self') ignores style attributes parsed from markup and inline <style>
        // elements; the same rules are applied through CSSOM, which the policy allows.
        for (const element of bodyElement.querySelectorAll<HTMLElement>("[style]"))
          element.style.cssText = element.getAttribute("style") ?? "";
        adopted = Array.from(renderedStyles.querySelectorAll("style")).flatMap((style) => {
          try {
            const sheet = new CSSStyleSheet();
            sheet.replaceSync(style.textContent ?? "");
            return [sheet];
          } catch {
            return [];
          }
        });
        document.adoptedStyleSheets = [...document.adoptedStyleSheets, ...adopted];
        styleElement.replaceChildren();
        const text = bodyElement.innerText ?? "";
        onLoadRef.current({ words: text.split(/\s+/).filter(Boolean).length, text });
        setState("done");
      } catch {
        if (current) setState("failed");
      }
    })();
    return () => {
      current = false;
      document.adoptedStyleSheets = document.adoptedStyleSheets.filter(
        (sheet) => !adopted.includes(sheet),
      );
    };
  }, [blob]);
  const ui = useAppTranslation();
  return (
    <>
      {state === "rendering" && (
        <div className="flex justify-center p-6" role="status">
          <Loader2 className="size-8 animate-spin text-content-muted" aria-hidden />
        </div>
      )}
      {state === "failed" && (
        <p className="p-6 text-center text-sm text-content-secondary">
          {ui("Không đọc được tài liệu Word này.")}
        </p>
      )}
      <div ref={styles} />
      <div
        ref={body}
        data-slot="docx-preview"
        // Pages keep their layout as in Onyx; narrow screens scroll sideways instead of reflowing.
        className="overflow-auto px-4 py-6 text-content-document [&_section.docx]:mx-auto [&_section.docx]:mb-6 [&_section.docx]:bg-surface-document [&_section.docx]:shadow-md"
      />
    </>
  );
}
