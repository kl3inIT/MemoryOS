import { z } from "zod";
import type { Root, RootContent } from "mdast";
import { documentSourceTypesSchema } from "@/features/search/document-source-presentation";

// Presentation-only metadata recorded when the evidence was cited; older answers omit both.
const mediaTypeSchema = z.string().min(1).max(160).nullish();
const sourceTypesSchema = documentSourceTypesSchema.optional();
// Only the backend-built Drive open URL is accepted; anything else would be an arbitrary outbound link.
const providerUrlSchema = z
  .string()
  .regex(/^https:\/\/drive\.google\.com\/open\?id=[A-Za-z0-9_-]{10,256}$/)
  .nullish();

const documentSourceSchema = z
  .object({
    citationId: z.number().int().min(1),
    documentId: z.string().uuid(),
    generation: z.string().uuid(),
    fileId: z.null().optional(),
    fileLocation: z.null().optional(),
    web: z.null().optional(),
    title: z.string().max(1024),
    startOrdinal: z.number().int().min(0).max(9999),
    endOrdinal: z.number().int().min(0).max(9999),
    provenance: z
      .array(
        z.object({ ordinal: z.number().int().nonnegative(), provenanceJson: z.string().max(8192) }),
      )
      .min(1)
      .max(60),
    mediaType: mediaTypeSchema,
    sourceTypes: sourceTypesSchema,
    providerUrl: providerUrlSchema,
  })
  .refine(
    (source) =>
      source.endOrdinal >= source.startOrdinal &&
      source.provenance.every(
        (item) => item.ordinal >= source.startOrdinal && item.ordinal <= source.endOrdinal,
      ),
    "Source provenance must stay within its ordered passage range",
  );
export const sourceSchema = z.union([
  z.object({
    citationId: z.number().int().min(1),
    title: z.string().max(1024),
    documentId: z.null(),
    generation: z.null(),
    fileId: z.null(),
    fileLocation: z.null().optional(),
    startOrdinal: z.literal(0),
    endOrdinal: z.literal(0),
    provenance: z.array(z.never()).max(0),
    web: z.object({
      url: z
        .string()
        .max(2048)
        .url()
        .refine((url) => {
          const parsed = new URL(url);
          return (
            ["http:", "https:"].includes(parsed.protocol) && !parsed.username && !parsed.password
          );
        }),
      excerpt: z.string().max(4000),
      retrievedAt: z.string().datetime({ offset: true }),
    }),
    mediaType: z.null().optional(),
    sourceTypes: z.array(z.never()).max(0).optional(),
    providerUrl: z.null().optional(),
  }),
  documentSourceSchema,
  z.object({
    citationId: z.number().int().min(1),
    fileId: z.string().uuid(),
    web: z.null().optional(),
    fileLocation: z
      .union([
        z.object({
          offset: z.number().int().min(0).max(2000000),
          count: z.number().int().min(1).max(16000),
          generation: z.null(),
          ordinal: z.null(),
        }),
        z.object({
          offset: z.null(),
          count: z.null(),
          generation: z.string().uuid(),
          ordinal: z.number().int().min(0).max(9999),
        }),
      ])
      .nullish(),
    title: z.string().max(1024),
    documentId: z.null(),
    generation: z.null(),
    startOrdinal: z.literal(0),
    endOrdinal: z.literal(0),
    provenance: z.array(z.never()).max(0),
    mediaType: mediaTypeSchema,
    sourceTypes: z.array(z.never()).max(0).optional(),
    providerUrl: z.null().optional(),
  }),
]);
export type ChatSource = z.infer<typeof sourceSchema>;
// No citation count cap, as Onyx; the server bounds stored bytes.
export const sourcesSchema = z.array(sourceSchema);
/** Transform prose only; code and existing links keep their original meaning. */
export function remarkCitations() {
  return (tree: Root) => {
    function walk(parent: { children: RootContent[] }) {
      parent.children = parent.children.flatMap((node): RootContent[] => {
        if (node.type === "text") {
          const parts: RootContent[] = [];
          let offset = 0;
          for (const match of node.value.matchAll(/\[(\d{1,5})\]/g)) {
            const id = Number(match[1]);
            if (id < 1) continue;
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

/**
 * Models trained on ChatGPT prefix the links to files they generated with `sandbox:`, which the link hardener shows as
 * "[blocked]". The prefix is dropped: the file_link run_python returned then opens its preview, and any other path
 * stays plain text as every model-written path does.
 */
export function remarkSandboxLinks() {
  return (tree: Root) => {
    function walk(node: Root | RootContent) {
      if (node.type === "link" && /^sandbox:\//.test(node.url))
        node.url = node.url.slice("sandbox:".length);
      if ("children" in node) for (const child of node.children) walk(child);
    }
    walk(tree);
  };
}
