import { useQuery } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { sanitizeDocxHtml } from "@/features/chat/chat-file-preview";
import { useAppTranslation } from "@/i18n/use-app-translation";

/**
 * Renders a stored Word original with docx-preview, as the Chat file preview does. The library writes
 * document-controlled markup, so the output is sanitized and only its own stylesheets are adopted.
 */
export function DocxPreview({
  blob,
  onLoad,
}: {
  blob: Blob;
  onLoad?: (result: { words: number; text: string }) => void;
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
        // Dynamic on purpose: docx-preview pulls in a zip reader and its own layout CSS, which only a reader
        // that actually opens a Word document should download, as pdf.js is lazily loaded for PDFs.
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
        onLoadRef.current?.({ words: text.split(/\s+/).filter(Boolean).length, text });
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

/**
 * Shows the whole authorized Word original of a search result or citation. The route serves octet-stream under
 * nosniff, so the bytes are read once into a Blob that docx-preview unzips; there are no page ranges to fetch.
 */
export function DocumentDocxView({ url }: { url: string }) {
  const ui = useAppTranslation();
  const original = useQuery({
    queryKey: ["document-original-docx", url],
    gcTime: 0,
    staleTime: 0,
    retry: false,
    queryFn: async ({ signal }) => {
      const response = await fetch(url, { credentials: "same-origin", signal });
      if (!response.ok) throw new Error(`original ${response.status}`);
      return await response.blob();
    },
  });

  if (original.isPending)
    return (
      <p role="status" className="py-12 text-center font-main-ui-body text-content-secondary">
        {ui("Đang tải tài liệu…")}
      </p>
    );
  if (original.isError)
    return (
      <p role="alert" className="py-12 text-center font-main-ui-body text-content-secondary">
        {ui("Không đọc được tài liệu Word này.")}
      </p>
    );
  return (
    <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
      <DocxPreview blob={original.data} />
    </div>
  );
}
