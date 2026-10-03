import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import { parse } from "@babel/parser";
import { appEn } from "../src/i18n/app-translations.ts";

const root = "src";
const attributes = new Set([
  "title",
  "description",
  "label",
  "aria-label",
  "placeholder",
  "alt",
  "emptyMessage",
  "submitLabel",
  "cancelLabel",
  "confirmLabel",
  "pendingLabel",
  "message",
  "tooltip",
  "loadingLabel",
  "previousLabel",
  "nextLabel",
  "closeLabel",
  "retryLabel",
  "searchPlaceholder",
  "errorMessage",
  "heading",
  "pageTitle",
]);
export const results = [];
const missingKeys = [];
function files(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    return entry.isDirectory()
      ? files(path)
      : /\.tsx?$/.test(path) && !/\.(test|gen)\./.test(path) && !path.includes("i18n")
        ? [path]
        : [];
  });
}
for (const path of files(root)) {
  const source = readFileSync(path, "utf8");
  // Babel 8 rejects a `<` that starts a type argument list while the jsx plugin is on, so only
  // .tsx files get it; .ts files use generics such as `useState<Record<string, string>>()`.
  const plugins = path.endsWith(".tsx") ? ["typescript", "jsx"] : ["typescript"];
  const ast = parse(source, { sourceType: "module", plugins });
  function visit(node, ancestors = []) {
    if (!node || typeof node !== "object" || !node.type) return;
    const parent = ancestors.at(-1);
    if (
      node.type === "CallExpression" &&
      ["ui", "appText"].includes(node.callee?.name) &&
      node.arguments[0]?.type === "StringLiteral" &&
      !Object.hasOwn(appEn, node.arguments[0].value)
    ) {
      missingKeys.push(
        `${path}:${node.loc.start.line} missing catalog key ${node.arguments[0].value}`,
      );
    }
    if (
      process.argv.includes("--candidates") &&
      node.type === "StringLiteral" &&
      /[\p{L}]/u.test(node.value) &&
      !["ImportDeclaration", "ExportNamedDeclaration", "JSXAttribute", "TSLiteralType"].includes(
        parent?.type,
      ) &&
      !(
        parent?.type === "CallExpression" && ["ui", "t", "appText"].includes(parent.callee?.name)
      ) &&
      !/className|^@\/|^\.\.?\//.test(node.value) &&
      (/\p{Script=Latin}.*\s+\p{Script=Latin}/u.test(node.value) ||
        [...node.value].some((character) => character.codePointAt(0) > 127))
    ) {
      if (!appEn[node.value])
        console.log(
          `${relative(".", path).replaceAll("\\", "/")}:${node.loc.start.line} ${JSON.stringify(node.value)}`,
        );
    }
    const owners = ancestors.filter((p, i) => {
      const name =
        p.type === "FunctionDeclaration"
          ? p.id?.name
          : p.type === "ArrowFunctionExpression" || p.type === "FunctionExpression"
            ? ancestors[i - 1]?.id?.name
            : undefined;
      return (
        name && (/^[A-Z]/.test(name) || /^use[A-Z]/.test(name)) && p.body?.type === "BlockStatement"
      );
    });
    const owner = owners.at(-1);
    function collect(valueNode, kind) {
      if (!valueNode) return;
      if (valueNode.type === "ConditionalExpression") {
        collect(valueNode.consequent, "expression");
        collect(valueNode.alternate, "expression");
        return;
      }
      if (valueNode.type === "LogicalExpression") {
        collect(valueNode.right, "expression");
        return;
      }
      const value =
        valueNode.type === "JSXText"
          ? valueNode.value.replace(/\s+/g, " ").trim()
          : valueNode.type === "StringLiteral"
            ? valueNode.value
            : valueNode.type === "TemplateLiteral"
              ? valueNode.quasis
                  .map(
                    (q, i) =>
                      q.value.cooked + (i < valueNode.expressions.length ? `{{v${i + 1}}}` : ""),
                  )
                  .join("")
              : undefined;
      if (value && /[\p{L}]/u.test(value) && !/^https?:|^\//.test(value))
        results.push({
          file: relative(".", path).replaceAll("\\", "/"),
          line: valueNode.loc.start.line,
          kind,
          value,
          start: valueNode.start,
          end: valueNode.end,
          ownerStart: owner?.body.start,
          expressions:
            valueNode.type === "TemplateLiteral"
              ? valueNode.expressions.map((e) => source.slice(e.start, e.end))
              : undefined,
        });
    }
    if (node.type === "JSXText") collect(node, "text");
    if (node.type === "JSXAttribute" && attributes.has(node.name.name))
      collect(
        node.value?.type === "JSXExpressionContainer" ? node.value.expression : node.value,
        node.value?.type === "JSXExpressionContainer" ? "expression" : "attribute",
      );
    if (node.type === "JSXExpressionContainer" && parent?.type !== "JSXAttribute")
      collect(node.expression, "expression");
    for (const [key, child] of Object.entries(node)) {
      if (["loc", "start", "end", "extra", "comments", "tokens"].includes(key)) continue;
      if (Array.isArray(child)) child.forEach((v) => visit(v, [...ancestors, node]));
      else visit(child, [...ancestors, node]);
    }
  }
  visit(ast);
}
/**
 * Vietnamese copy keeps one tone style, the older one: an open "oa", "oe" or "uy" carries the tone on its first vowel
 * ("xóa", "khóa", "hủy", "tùy"), never on the second ("xoá"). "quý" is not such a pair, since "qu" is one consonant.
 */
const secondVowelTone =
  /(?<!\p{L})\p{L}*?(?:[oO][áàảãạÁÀẢÃẠ]|[oO][éèẻẽẹÉÈẺẼẸ]|(?<![qQ])[uU][ýỳỷỹỵÝỲỶỸỴ])(?!\p{L})/gu;
const toneFailures = ["vi.ts", "app-translations.vi.ts", "app-translations.en.ts"].flatMap((name) =>
  readFileSync(join(root, "i18n", name), "utf8")
    .split("\n")
    .flatMap((line, index) =>
      [...line.matchAll(secondVowelTone)].map(
        ([word]) => `${root}/i18n/${name}:${index + 1} tone on the second vowel in "${word}"`,
      ),
    ),
);
if (process.argv.includes("--check")) {
  const failures = [
    ...results.map((result) => `${result.file}:${result.line} untranslated ${result.value}`),
    ...missingKeys,
    ...toneFailures,
  ];
  for (const failure of failures) console.error(failure);
  console.log(
    `i18n audit: ${failures.length} direct UI literals, missing static keys or second-vowel tones.`,
  );
  if (failures.length) process.exitCode = 1;
} else if (process.argv.includes("--quiet") || process.argv.includes("--candidates")) {
  /* Imported by the one-off mechanical migration. */
} else if (process.argv.includes("--json")) console.log(JSON.stringify(results, null, 2));
else if (process.argv.includes("--values"))
  console.log(JSON.stringify([...new Set(results.map((r) => r.value))], null, 2));
else
  for (const result of results)
    console.log(`${result.file}:${result.line} ${result.kind} ${result.value}`);
