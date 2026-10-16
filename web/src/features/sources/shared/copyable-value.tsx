import { useAppTranslation } from "@/i18n/use-app-translation";
import { Check, Copy } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";

/** A short value an administrator pastes into a provider's console, with a copy button. */
export function CopyableValue({ value }: { value: string }) {
  const ui = useAppTranslation();

  const [copied, setCopied] = useState(false);
  return (
    <span className="flex items-center gap-1">
      <span className="min-w-0 truncate font-mono text-xs">{value}</span>
      <Button
        size="sm"
        prominence="tertiary"
        aria-label={ui("Copy {{v1}}", { v1: value })}
        onClick={() => {
          navigator.clipboard
            .writeText(value)
            .then(() => setCopied(true))
            .catch(() => setCopied(false));
        }}
      >
        {copied ? <Check aria-hidden="true" /> : <Copy aria-hidden="true" />}
      </Button>
    </span>
  );
}
