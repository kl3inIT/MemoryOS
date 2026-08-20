import { createHash } from "node:crypto";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { relative, resolve } from "node:path";
import { spawnSync } from "node:child_process";

const separatorIndex = process.argv.indexOf("--");
const paths = process.argv.slice(2, separatorIndex);
const [command, ...commandArguments] = process.argv.slice(separatorIndex + 1);

if (separatorIndex < 3 || !command) {
  throw new Error("Usage: assert-generated-stable <path...> -- <command...>");
}

function digest(file) {
  return createHash("sha256").update(readFileSync(file)).digest("hex");
}

function snapshotEntry(entry, snapshot) {
  const absoluteEntry = resolve(entry);
  if (!existsSync(absoluteEntry)) {
    snapshot.set(relative(process.cwd(), absoluteEntry), "<missing>");
    return;
  }

  if (!statSync(absoluteEntry).isDirectory()) {
    snapshot.set(relative(process.cwd(), absoluteEntry), digest(absoluteEntry));
    return;
  }

  for (const child of readdirSync(absoluteEntry, { withFileTypes: true }).sort((left, right) =>
    left.name.localeCompare(right.name),
  )) {
    snapshotEntry(resolve(absoluteEntry, child.name), snapshot);
  }
}

function snapshotGeneratedPaths() {
  const snapshot = new Map();
  for (const path of paths) {
    snapshotEntry(path, snapshot);
  }
  return snapshot;
}

const before = snapshotGeneratedPaths();
const packageManagerScript = command === "pnpm" ? process.env.npm_execpath : undefined;
const executable = packageManagerScript ? process.execPath : command;
const spawnArguments = packageManagerScript
  ? [packageManagerScript, ...commandArguments]
  : commandArguments;
const result = spawnSync(executable, spawnArguments, { stdio: "inherit" });

if (result.error) {
  throw result.error;
}

if (result.status !== 0) {
  process.exit(result.status ?? 1);
}

const after = snapshotGeneratedPaths();
const changedPaths = [...new Set([...before.keys(), ...after.keys()])]
  .filter((path) => before.get(path) !== after.get(path))
  .sort();

if (changedPaths.length > 0) {
  process.stderr.write("Generated output was stale:\n");
  for (const path of changedPaths) {
    process.stderr.write(`- ${path}\n`);
  }
  process.exit(1);
}
