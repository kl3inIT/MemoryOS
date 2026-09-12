// Adapted from assistant-ui elements-mermaid-diagram (MIT), 2026-09-12.
// SVG is an inert image, not model-controlled live DOM; Dialog owns modal behavior.
import { renderMermaidSVG } from "beautiful-mermaid";
import { useContext, useMemo, useState } from "react";
import { Dialog } from "radix-ui";
import { Maximize2, Minus, Plus, RotateCcw, X } from "lucide-react";
import { useTranslation } from "react-i18next";
import { ThemeContext } from "@/features/theme/theme-context";
import { IconButton } from "@/components/ui/icon-button";

export default function MermaidDiagram({ code }: { code: string }) {
  const { t } = useTranslation("renderers");
  const theme = useContext(ThemeContext)?.resolvedTheme ?? "light";
  const [scale, setScale] = useState(1);
  const src = useMemo(() => {
    if (code.length > 20_000 || code.split(/\r?\n/).length > 250) return undefined;
    try {
      const svg = renderMermaidSVG(code, {
        bg: theme === "dark" ? "#18181b" : "#fafafa",
        fg: theme === "dark" ? "#f4f4f5" : "#18181b",
        transparent: true,
      });
      return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
    } catch {
      return undefined;
    }
  }, [code, theme]);
  if (!src)
    return (
      <div data-slot="mermaid-fallback" className="rounded-b-xl bg-muted/30">
        <pre className="overflow-x-auto p-4 text-sm">
          <code>{code}</code>
        </pre>
        <p role="status" className="border-t px-4 py-2 text-xs text-muted-foreground">
          {t("diagramFallback")}
        </p>
      </div>
    );
  return (
    <Dialog.Root onOpenChange={() => setScale(1)}>
      <div
        className="relative rounded-b-xl border border-t-0 border-border/50 bg-muted/30 p-3"
        data-slot="mermaid-diagram"
      >
        <img src={src} alt={t("diagram")} className="mx-auto max-h-96 max-w-full" />
        <Dialog.Trigger asChild>
          <IconButton aria-label={t("expand")} className="absolute right-2 top-2" size="sm">
            <Maximize2 />
          </IconButton>
        </Dialog.Trigger>
      </div>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-content-primary/25" />
        <Dialog.Content
          aria-describedby={undefined}
          className="fixed inset-4 z-50 flex min-h-0 flex-col rounded-xl border bg-surface-overlay shadow-lg outline-none"
        >
          <header className="flex shrink-0 items-center gap-2 border-b p-3">
            <Dialog.Title className="min-w-0 flex-1 truncate font-medium">
              {t("diagram")}
            </Dialog.Title>
            <IconButton
              size="sm"
              aria-label={t("zoomOut")}
              disabled={scale <= 0.5}
              onClick={() => setScale((s) => Math.max(0.5, s / 1.25))}
            >
              <Minus />
            </IconButton>
            <IconButton
              size="sm"
              aria-label={t("zoomIn")}
              disabled={scale >= 4}
              onClick={() => setScale((s) => Math.min(4, s * 1.25))}
            >
              <Plus />
            </IconButton>
            <IconButton size="sm" aria-label={t("reset")} onClick={() => setScale(1)}>
              <RotateCcw />
            </IconButton>
            <Dialog.Close asChild>
              <IconButton size="sm" aria-label={t("close")}>
                <X />
              </IconButton>
            </Dialog.Close>
          </header>
          <div className="min-h-0 flex-1 overflow-auto p-4">
            <img
              src={src}
              alt={t("diagram")}
              className="mx-auto max-w-none"
              style={{ width: `${scale * 100}%` }}
            />
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
