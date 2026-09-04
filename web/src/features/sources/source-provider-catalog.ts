import { Files } from "lucide-react";
import type { LucideIcon } from "lucide-react";

export type SourceProvider = {
  type: "FILE";
  name: string;
  description: string;
  setupPath: "/admin/sources/new/file";
  icon: LucideIcon;
};

export const sourceProviders = [
  {
    type: "FILE",
    name: "Files",
    description: "Upload PDF, DOCX, TXT, and Markdown documents.",
    setupPath: "/admin/sources/new/file",
    icon: Files,
  },
] as const satisfies readonly SourceProvider[];

export function findSourceProvider(type: string | undefined) {
  return sourceProviders.find((provider) => provider.type === type);
}
