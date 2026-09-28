/** A retrieved passage as the chunker wrote it: the header lines it adds and the body the original contains. */

export function stripGeneratedTitlePrefix(content: string, title: string): string {
  const lineBreak = content.indexOf("\n");
  const firstLine = (lineBreak === -1 ? content : content.slice(0, lineBreak)).replace(/\r$/, "");
  if (firstLine !== `Title: ${title}`) return content;
  return lineBreak === -1 ? "" : content.slice(lineBreak + 1);
}

/**
 * The passage without the header `StructuredDocumentChunker` writes in front of it: a `Title:` line and,
 * when the block sits under headings, a `Section:` line. The original document contains neither, so a
 * citation is located and shown without them. A long header is truncated at indexing time, so the lines are
 * recognised by how they start rather than by their whole value.
 */
export function passageBody(content: string): string {
  let body = content;
  if (body.startsWith("Title: ")) body = afterFirstLine(body);
  else return content;
  if (body.startsWith("Section: ")) body = afterFirstLine(body);
  return body;
}

/**
 * The heading trail the chunker recorded for this passage, which is the context a search result is read in.
 * The original document does not contain the line, so it is shown beside a citation rather than searched for.
 */
export function passageSection(content: string): string | undefined {
  if (!content.startsWith("Title: ")) return undefined;
  const rest = afterFirstLine(content);
  if (!rest.startsWith("Section: ")) return undefined;
  const lineBreak = rest.indexOf("\n");
  return (lineBreak === -1 ? rest : rest.slice(0, lineBreak)).slice("Section: ".length).trim();
}

function afterFirstLine(value: string): string {
  const lineBreak = value.indexOf("\n");
  return lineBreak === -1 ? "" : value.slice(lineBreak + 1);
}
