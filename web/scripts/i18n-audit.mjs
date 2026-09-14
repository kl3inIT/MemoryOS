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
  const ast = parse(source, { sourceType: "module", plugins: ["typescript", "jsx"] });
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
if (process.argv.includes("--check")) {
  const failures = [
    ...results.map((result) => `${result.file}:${result.line} untranslated ${result.value}`),
    ...missingKeys,
  ];
  for (const failure of failures) console.error(failure);
  console.log(`i18n audit: ${failures.length} direct UI literals or missing static keys.`);
  if (failures.length) process.exitCode = 1;
} else if (process.argv.includes("--quiet") || process.argv.includes("--candidates")) {
  /* Imported by the one-off mechanical migration. */
} else if (process.argv.includes("--json")) console.log(JSON.stringify(results, null, 2));
else if (process.argv.includes("--values"))
  console.log(JSON.stringify([...new Set(results.map((r) => r.value))], null, 2));
else
  for (const result of results)
    console.log(`${result.file}:${result.line} ${result.kind} ${result.value}`);
