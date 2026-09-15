import type { Nodes, Root } from "mdast";
import remarkGfm from "remark-gfm";
import remarkParse from "remark-parse";
import { unified } from "unified";

/** The answer's citation markers, as rendered by `remarkCitations`. */
const CITATION = /\[(\d{1,2})\]/g;
const SKIPPED = new Set([
  "code",
  "html",
  "image",
  "imageReference",
  "definition",
  "footnoteDefinition",
  "footnoteReference",
  "thematicBreak",
]);
const BLOCKS = new Set(["root", "paragraph", "heading", "blockquote", "list", "listItem", "table"]);

/**
 * Plain text for reading an answer aloud. Markdown syntax, code blocks, images, link targets and citation markers are
 * dropped and blocks become lines, so the voice pauses between them. Answer text is prepared for speech only here.
 */
export function speechText(markdown: string): string {
  const tree = unified().use(remarkParse).use(remarkGfm).parse(markdown) as Root;
  const lines: string[] = [];
  let line = "";
  const flush = () => {
    const text = line
      .replace(/\s+/g, " ")
      .replace(/\s+([.,;:!?…])/g, "$1")
      .replace(/[\s,]+$/, "")
      .trim();
    if (text) lines.push(text);
    line = "";
  };
  const visit = (node: Nodes) => {
    if (SKIPPED.has(node.type)) return;
    if (node.type === "text") {
      line += node.value.replace(CITATION, (marker, id: string) =>
        Number(id) >= 1 && Number(id) <= 24 ? "" : marker,
      );
      return;
    }
    if (node.type === "inlineCode") {
      line += node.value;
      return;
    }
    if (node.type === "break") {
      line += " ";
      return;
    }
    if (node.type === "tableRow") {
      flush();
      for (const cell of node.children) {
        for (const child of cell.children) visit(child);
        line += ", ";
      }
      flush();
      return;
    }
    if (!("children" in node)) return;
    const block = BLOCKS.has(node.type);
    if (block) flush();
    for (const child of node.children) visit(child as Nodes);
    if (block) flush();
  };
  visit(tree);
  flush();
  return lines.join("\n");
}
