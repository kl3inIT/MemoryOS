import { z } from "zod";
import type { GenerativeUISpec } from "@assistant-ui/react";

export const artifactsSchema = z
  .array(
    z.object({
      id: z.uuid(),
      title: z.string().min(1).max(120),
      spec: z.string().max(16384),
    }),
  )
  .max(3)
  .default([]);
export type ChatArtifact = z.infer<typeof artifactsSchema>[number];

const properties: Record<string, readonly string[]> = {
  Card: ["title"],
  Heading: ["text"],
  Text: ["text"],
  Metric: ["label", "value"],
  Table: [],
  Row: [],
  Cell: ["text"],
};

/** Validate before the library sees any model props. No href/src/actions/styles/HTML. */
export function artifactSpec(spec: string): GenerativeUISpec | undefined {
  if (spec.length > 16384 || new TextEncoder().encode(spec).length > 16384) return undefined;
  let count = 0;
  function node(value: unknown, depth: number): boolean {
    if (++count > 80 || depth > 8 || !value || typeof value !== "object" || Array.isArray(value))
      return false;
    const record = value as Record<string, unknown>;
    if (Object.keys(record).some((key) => !["component", "props", "children"].includes(key)))
      return false;
    if (typeof record.component !== "string" || !Object.hasOwn(properties, record.component))
      return false;
    if (record.props !== undefined) {
      if (!record.props || typeof record.props !== "object" || Array.isArray(record.props))
        return false;
      for (const [key, text] of Object.entries(record.props)) {
        if (
          !properties[record.component].includes(key) ||
          typeof text !== "string" ||
          text.length > 2048
        )
          return false;
      }
    }
    if (record.children !== undefined) {
      if (!Array.isArray(record.children) || record.children.length > 24) return false;
      if (!["Card", "Table", "Row"].includes(record.component) && record.children.length)
        return false;
      for (const child of record.children) {
        if (
          (record.component === "Table" && child?.component !== "Row") ||
          (record.component === "Row" && child?.component !== "Cell") ||
          !node(child, depth + 1)
        )
          return false;
      }
    }
    return true;
  }
  try {
    const parsed = JSON.parse(spec);
    return parsed &&
      typeof parsed === "object" &&
      !Array.isArray(parsed) &&
      Object.keys(parsed).length === 1 &&
      node(parsed.root, 0)
      ? parsed
      : undefined;
  } catch {
    return undefined;
  }
}
