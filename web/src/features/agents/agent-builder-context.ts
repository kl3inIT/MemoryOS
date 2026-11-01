import { createContext } from "react";
import type { BuilderChanges } from "./agent-builder-changes";

/** The builder thread's changes and the names its change card shows for an option's ID (MEM-280). */
export type BuilderCardContext = {
  changes: BuilderChanges;
  /** The name of a Source, Document Set, MCP server or model, as the editor shows it. */
  nameOf: (
    field: "sourceIds" | "documentSetIds" | "mcpServerIds" | "modelConfigurationId",
    id: string,
  ) => string;
};
export const BuilderCard = createContext<BuilderCardContext | null>(null);
