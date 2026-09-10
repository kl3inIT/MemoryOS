import { z } from "zod";
import type { Root, RootContent } from "mdast";

export const sourceSchema = z
  .object({
    citationId: z.number().int().min(1).max(24),
    documentId: z.string().uuid(),
    generation: z.string().uuid(),
    title: z.string().max(1024),
    startOrdinal: z.number().int().min(0).max(9999),
    endOrdinal: z.number().int().min(0).max(9999),
    provenance: z
      .array(
        z.object({ ordinal: z.number().int().nonnegative(), provenanceJson: z.string().max(8192) }),
      )
      .min(1)
      .max(60),
  })
  .refine(
    (source) =>
      source.endOrdinal >= source.startOrdinal &&
      source.provenance.every(
        (item) => item.ordinal >= source.startOrdinal && item.ordinal <= source.endOrdinal,
      ),
    "Source provenance must stay within its ordered passage range",
  );
export type ChatSource = z.infer<typeof sourceSchema>;
export const sourcesSchema = z.array(sourceSchema).max(24);
export const searchEventSchema = z.object({
  toolCallId: z.string().max(200),
  stage: z.enum(["STARTED", "SELECTING", "EXPANDING", "SOURCE", "COMPLETED", "FAILED"]),
  source: sourceSchema.nullable(),
});
export type SearchProgress = Record<string, z.infer<typeof searchEventSchema>["stage"]>;

/** Transform prose only; code and existing links keep their original meaning. */
export function remarkCitations() {
  return (tree: Root) => {
    function walk(parent: { children: RootContent[] }) {
      parent.children = parent.children.flatMap((node): RootContent[] => {
        if (node.type === "text") {
          const parts: RootContent[] = [];
          let offset = 0;
          for (const match of node.value.matchAll(/\[(\d{1,2})\]/g)) {
            const id = Number(match[1]);
            if (id < 1 || id > 24) continue;
            if (match.index > offset)
              parts.push({ type: "text", value: node.value.slice(offset, match.index) });
            parts.push({
              type: "link",
              url: `#citation-${id}`,
              children: [{ type: "text", value: match[0] }],
            });
            offset = match.index + match[0].length;
          }
          parts.push({ type: "text", value: node.value.slice(offset) });
          return parts;
        }
        if ("children" in node && node.type !== "link" && node.type !== "linkReference")
          walk(node as { children: RootContent[] });
        return [node];
      });
    }
    walk(tree);
  };
}
