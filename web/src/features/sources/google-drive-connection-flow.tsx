import { KeyRound, MoveRight } from "lucide-react";
import type { ReactNode } from "react";
import { Brand } from "@/components/brand";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { GoogleDriveIcon } from "./google-drive-icon";

/** The path content takes into MemoryOS, drawn like Databricks' partner connection panel. */
export function GoogleDriveConnectionFlow() {
  const ui = useAppTranslation();

  const nodes: { label: AppCopy; mark: ReactNode }[] = [
    { label: "Google Drive", mark: <GoogleDriveIcon /> },
    { label: "Credential", mark: <KeyRound className="text-content-secondary" /> },
    { label: "MemoryOS", mark: <Brand compact /> },
  ];

  return (
    <ol aria-label={ui("Connection path")} className="flex items-start gap-2 sm:gap-4">
      {nodes.map(({ label, mark }, index) => (
        <li key={index} className="flex items-start gap-2 sm:gap-4">
          {index > 0 ? (
            <MoveRight
              aria-hidden="true"
              className="mt-3.5 size-5 shrink-0 text-content-muted sm:size-6"
            />
          ) : null}
          <span className="flex w-20 flex-col items-center gap-2 text-center">
            <span
              aria-hidden="true"
              className="grid size-12 place-items-center rounded-full border border-border-subtle bg-surface-raised [&_svg]:size-6"
            >
              {mark}
            </span>
            <span className="text-sm text-content-secondary">{ui(label)}</span>
          </span>
        </li>
      ))}
    </ol>
  );
}
