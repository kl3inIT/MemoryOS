# MEM-82 — Vanda landing page implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `https://vanda.app` as a standalone static landing page for MemoryOS by Vanda, verified in CI and deployable by digest on the staging VPS without touching the application release.

**Architecture:** A standalone pnpm package `landing/` (Vite + React + Tailwind) renders one page from a typed content module. An nginx image serves the build read-only with a strict CSP. CI gates it with lint/tests/build and an image smoke script, then publishes the image by digest in its own job; an operator deploys it as Compose project `memoryos-landing` behind Nginx Proxy Manager.

**Tech Stack:** React 19.2.8, Vite 8.2.1, TypeScript 7.0.2, Tailwind 4.3.3, lucide-react 1.34.0, Vitest 4.1.11 + Testing Library, oxlint 1.79.0, oxfmt 0.64.0, pnpm 11.22.0, nginx 1.31-alpine, Docker Compose, GitHub Actions.

Design: [design.md](design.md). Content decisions are fixed there; do not add status labels, testimonials or metrics.

## Status

2026-09-11: Tasks 1–9 and the Task 10 documentation are complete and verified locally; see [verification.md](verification.md). The browser review added two fixes: the desktop navigation starts at 1024 px, and the focused skip link keeps its padding. Pull request #95 is open and reported on MEM-82.

Later the same day: Task 5D, the landing page 6.0 motion redesign, is implemented on branch `nhuxuanviet/mem-82-landing-page-6.0` and passes the package gate; Lighthouse mobile performance on the local preview is 0.96 (TBT 130–160 ms, CLS 0). It is committed in concern clusters (`48ed13e`–`1bf0000`), not yet pushed. The Orca browser review is recorded in [verification](verification.md#landing-60), with its remaining checks listed there. Pull request #95 stays the rollback point.

Pending, and not passed: CI on the pull request, the first `Publish landing` digest, the operator deployment with the deployed checks from the [landing runbook](../../../runbooks/landing.md), Lighthouse on `https://vanda.app/`, and Laura's content review. The increment stays under `active/` until the pull request merges.

---

## File map

| Path | Responsibility |
| --- | --- |
| `landing/package.json`, `pnpm-lock.yaml` | Pinned toolchain and scripts (`check` is the package gate) |
| `landing/.gitignore`, `.dockerignore`, `.oxlintrc.json`, `.oxfmtrc.json` | Local tooling boundaries |
| `landing/tsconfig*.json`, `vite.config.ts`, `vitest.config.ts` | Build, typecheck and test configuration |
| `landing/index.html` | Title, description, canonical, Open Graph/Twitter, JSON-LD |
| `landing/public/*` | Favicon, OG image, robots, sitemap, third-party notices, pre-paint `theme-init.js` |
| `landing/scripts/font-data-url.mjs`, `assert-font-assets.mjs`, `font-data-url.test.mjs` | CSP-compatible font assertion (copied from `web/`) |
| `landing/scripts/og-image.html` | Source of `public/og-image.png` |
| `landing/scripts/smoke-image.sh` | Runtime contract of the built image |
| `landing/src/main.tsx`, `App.tsx` | Mount and page composition |
| `landing/src/content.ts` | Every string, link and list on the page |
| `landing/src/components/*` | `ActionLink`, `Section`, `BrandMark` |
| `landing/src/sections/*` | One file per page section |
| `landing/src/styles/*`, `index.css` | Light and dark tokens, Tailwind theme mapping, base layer |
| `landing/src/lib/theme.ts` | Theme toggle state, storage and system-preference tracking |
| `landing/src/App.test.tsx`, `src/lib/theme.test.ts`, `tests/site-metadata.test.mjs` | Page, theme and metadata contracts |
| `landing/Dockerfile`, `nginx.conf` | Production image |
| `infrastructure/deployment/compose.landing.yaml` | Operator runtime definition |
| `.github/workflows/ci.yml` | `landing` job, gate, `Publish landing` |
| `docs/runbooks/landing.md` | Deploy, DNS, proxy, rollback |
| `docs/increments/active/mem-82-landing-page/verification.md` | Evidence |

All commands run from the worktree root. On this Windows host use the Bash tool (Git Bash); every command is also valid on Linux.

---

### Task 1: Package scaffold and toolchain

**Files:**
- Create: `landing/package.json`, `landing/.gitignore`, `landing/.oxlintrc.json`, `landing/.oxfmtrc.json`, `landing/tsconfig.json`, `landing/tsconfig.app.json`, `landing/tsconfig.node.json`, `landing/vite.config.ts`, `landing/vitest.config.ts`, `landing/src/test/setup.ts`, `landing/src/lib/utils.ts`, `landing/scripts/font-data-url.mjs`, `landing/scripts/font-data-url.test.mjs`, `landing/scripts/assert-font-assets.mjs`
- Generate: `landing/pnpm-lock.yaml`

- [x] **Step 1: Create `landing/package.json`**

```json
{
  "name": "@memoryos/landing",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "typecheck": "tsc -b",
    "build": "vite build && node scripts/assert-font-assets.mjs && tsc -b",
    "lint": "oxlint --deny-warnings .",
    "format": "oxfmt --write .",
    "format:check": "oxfmt --check .",
    "test:unit": "vitest run",
    "test:unit:watch": "vitest",
    "preview": "vite preview",
    "check": "pnpm lint && pnpm format:check && pnpm test:unit && pnpm build"
  },
  "dependencies": {
    "@fontsource-variable/hanken-grotesk": "5.3.0",
    "clsx": "2.1.1",
    "lucide-react": "1.34.0",
    "react": "19.2.8",
    "react-dom": "19.2.8",
    "tailwind-merge": "3.6.0"
  },
  "devDependencies": {
    "@tailwindcss/vite": "4.3.3",
    "@testing-library/jest-dom": "7.0.1",
    "@testing-library/react": "16.3.2",
    "@types/node": "26.2.0",
    "@types/react": "19.2.18",
    "@types/react-dom": "19.2.5",
    "@vitejs/plugin-react": "6.0.5",
    "jsdom": "30.0.1",
    "oxfmt": "0.64.0",
    "oxlint": "1.79.0",
    "tailwindcss": "4.3.3",
    "typescript": "7.0.2",
    "vite": "8.2.1",
    "vitest": "4.1.11"
  },
  "engines": {
    "node": ">=24.0.0"
  },
  "packageManager": "pnpm@11.22.0+sha512.1ff870c4c6133dfd88fb2afc46dd13d47f09c9794b438c6fdb47ca98caf3bc16381ee0be93a091b8e3824cf01f889f46d7d9e20910fb0be1ab0fb5baa80dd621"
}
```

- [x] **Step 2: Create tooling files**

`landing/.gitignore`:

```gitignore
node_modules
dist
coverage
reports
*.local
```

`landing/.oxlintrc.json`:

```json
{
  "$schema": "./node_modules/oxlint/configuration_schema.json",
  "plugins": ["react", "typescript", "oxc"],
  "rules": {
    "react/rules-of-hooks": "error",
    "react/only-export-components": ["warn", { "allowConstantExport": true }]
  }
}
```

`landing/.oxfmtrc.json`:

```json
{
  "$schema": "./node_modules/oxfmt/configuration_schema.json",
  "ignorePatterns": ["coverage/**", "dist/**", "reports/**"]
}
```

`landing/tsconfig.json`:

```json
{
  "compilerOptions": {
    "paths": {
      "@/*": ["./src/*"]
    }
  },
  "files": [],
  "references": [{ "path": "./tsconfig.app.json" }, { "path": "./tsconfig.node.json" }]
}
```

`landing/tsconfig.app.json`:

```json
{
  "compilerOptions": {
    "tsBuildInfoFile": "./node_modules/.tmp/tsconfig.app.tsbuildinfo",
    "paths": {
      "@/*": ["./src/*"]
    },
    "target": "es2024",
    "lib": ["ES2024", "DOM"],
    "module": "esnext",
    "types": ["vite/client"],
    "skipLibCheck": true,

    /* Bundler mode */
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "verbatimModuleSyntax": true,
    "moduleDetection": "force",
    "noEmit": true,
    "jsx": "react-jsx",

    /* Linting */
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "erasableSyntaxOnly": true,
    "noFallthroughCasesInSwitch": true
  },
  "include": ["src"]
}
```

`landing/tsconfig.node.json`:

```json
{
  "compilerOptions": {
    "tsBuildInfoFile": "./node_modules/.tmp/tsconfig.node.tsbuildinfo",
    "target": "es2023",
    "lib": ["ES2023"],
    "types": ["node"],
    "skipLibCheck": true,

    /* Bundler mode */
    "module": "nodenext",
    "allowImportingTsExtensions": true,
    "verbatimModuleSyntax": true,
    "moduleDetection": "force",
    "noEmit": true,

    /* Linting */
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "erasableSyntaxOnly": true,
    "noFallthroughCasesInSwitch": true
  },
  "include": ["vite.config.ts", "vitest.config.ts"]
}
```

`landing/vite.config.ts`:

```ts
import { fileURLToPath, URL } from "node:url";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [tailwindcss(), react()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  build: {
    // Fonts must stay same-origin files: the production CSP has no data: font source.
    assetsInlineLimit: 0,
  },
});
```

`landing/vitest.config.ts`:

```ts
import { fileURLToPath, URL } from "node:url";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  test: {
    execArgv: Number(process.versions.node.split(".")[0]) >= 25 ? ["--no-webstorage"] : [],
    include: ["src/**/*.test.{ts,tsx}", "scripts/**/*.test.mjs", "tests/**/*.test.mjs"],
    environment: "jsdom",
    maxWorkers: 2,
    reporters: process.env.CI
      ? ["default", ["junit", { includeConsoleOutput: false }]]
      : ["default"],
    outputFile: process.env.CI ? { junit: "reports/unit.xml" } : undefined,
    setupFiles: ["./src/test/setup.ts"],
  },
});
```

`landing/src/test/setup.ts`:

```ts
import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

afterEach(() => {
  cleanup();
});
```

`landing/src/lib/utils.ts`:

```ts
import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
```

- [x] **Step 3: Copy the font assertion and its test from `web/`**

`landing/scripts/font-data-url.mjs`:

```js
export const inlineFontDataUrlPattern = /@font-face\b[^}]*url\(\s*(?:["'])?data:/iu;
```

`landing/scripts/font-data-url.test.mjs`:

```js
import { describe, expect, it } from "vitest";
import { inlineFontDataUrlPattern } from "./font-data-url.mjs";

describe("inlineFontDataUrlPattern", () => {
  it.each([
    '@font-face { src: url(data:font/woff2;base64,AAAA) format("woff2"); }',
    '@font-face { src: url("data:application/font-woff2;base64,AAAA") format("woff2"); }',
  ])("detects inline font data URLs in @font-face", (css) => {
    expect(inlineFontDataUrlPattern.test(css)).toBe(true);
  });

  it("ignores unrelated data URLs outside @font-face", () => {
    expect(
      inlineFontDataUrlPattern.test('.icon { background: url("data:image/svg+xml,AAAA"); }'),
    ).toBe(false);
  });
});
```

`landing/scripts/assert-font-assets.mjs`:

```js
import { readdir, readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { inlineFontDataUrlPattern } from "./font-data-url.mjs";

const distDirectory = fileURLToPath(new URL("../dist/", import.meta.url));
const emittedFiles = await readdir(distDirectory, { recursive: true });
const styleSheets = emittedFiles.filter((file) => file.endsWith(".css"));
const fontFiles = emittedFiles.filter((file) => file.endsWith(".woff2"));

if (styleSheets.length === 0) {
  throw new Error("The production build emitted no CSS to inspect");
}
if (fontFiles.length === 0) {
  throw new Error("The production build emitted no same-origin WOFF2 assets");
}

for (const styleSheet of styleSheets) {
  const css = await readFile(path.join(distDirectory, styleSheet), "utf8");
  if (inlineFontDataUrlPattern.test(css)) {
    throw new Error(`${styleSheet} contains a CSP-incompatible inline font`);
  }
}

console.log(`Verified ${fontFiles.length} emitted WOFF2 assets with no inline font URLs.`);
```

- [x] **Step 4: Install and generate the lockfile**

Run: `pnpm --dir landing install`
Expected: `landing/pnpm-lock.yaml` created, no errors. If pnpm refuses a package because of `minimumReleaseAge`, create `landing/pnpm-workspace.yaml` with a `minimumReleaseAgeExclude` list naming exactly the refused `name@version` entries (same format as `web/pnpm-workspace.yaml`), rerun, and add that file to the Dockerfile `COPY` in Task 6.

- [x] **Step 5: Verify the icon exports used later exist in lucide-react 1.34.0**

Run: `grep -oE "declare const (Menu|Check|ArrowRight|ChevronDown|LockKeyhole|BadgeCheck|Bot|Cable|ChartColumn|KeyRound|Plug|Quote|Search|ShieldCheck):" landing/node_modules/lucide-react/dist/lucide-react.d.ts | sort -u`
Expected: 14 lines. If a name is missing, pick the closest existing icon from the same file and use it consistently in Task 4.

- [x] **Step 6: Run the tooling**

Run: `pnpm --dir landing test:unit`
Expected: PASS, 3 tests in `scripts/font-data-url.test.mjs`.

Run: `pnpm --dir landing format` then `pnpm --dir landing lint` then `pnpm --dir landing typecheck`
Expected: all exit 0.

- [x] **Step 7: Commit**

```bash
git add landing
git commit -m "build(landing): scaffold standalone landing package"
```

---

### Task 2: Design tokens and global styles

**Files:**
- Create: `landing/src/styles/tokens.css`, `landing/src/styles/theme.css`, `landing/src/styles/base.css`, `landing/src/index.css`

No tests: these are declarations exercised by the build (Task 5) and browser review (Task 9).

- [x] **Step 1: Create `landing/src/styles/tokens.css`**

```css
/*
 * Light subset of web/src/styles/tokens.css, kept as a copy because the landing page and the
 * application build and deploy independently. Onyx/Opal MIT palette adapted to MemoryOS
 * semantic roles; see /THIRD_PARTY_NOTICES.txt.
 */
:root {
  color-scheme: light;

  --neutral-00: #ffffff;
  --neutral-100: #e6e6e6;
  --neutral-200: #cccccc;
  --neutral-400: #a4a4a4;
  --neutral-800: #333333;
  --neutral-900: #1c1c1c;
  --neutral-950: #000000;

  --surface-canvas: #f0f0f1;
  --surface-base: #fafafa;
  --surface-raised: var(--neutral-00);

  --content-primary: #000000e5;
  --content-secondary: #000000bf;
  --content-muted: #0000008c;
  --content-inverse: var(--neutral-00);

  --border-subtle: var(--neutral-100);
  --border-default: var(--neutral-200);
  --border-strong: var(--neutral-400);

  /* Default-tone rows of the canonical action matrix. */
  --action-default-primary-surface: var(--neutral-900);
  --action-default-primary-surface-hover: var(--neutral-800);
  --action-default-primary-surface-active: var(--neutral-950);
  --action-default-primary-content: var(--content-inverse);
  --action-default-secondary-surface: var(--surface-base);
  --action-default-secondary-surface-hover: var(--surface-canvas);
  --action-default-secondary-surface-active: var(--surface-raised);
  --action-default-secondary-content: var(--content-secondary);
  --action-default-secondary-content-hover: var(--content-primary);
  --action-default-secondary-border: var(--border-subtle);
  --action-default-secondary-border-hover: var(--border-default);
  --action-default-tertiary-surface-hover: var(--surface-canvas);
  --action-default-tertiary-surface-active: var(--surface-raised);
  --action-default-tertiary-content: var(--content-secondary);
  --action-default-tertiary-content-hover: var(--content-primary);
  --focus-ring: var(--neutral-950);

  --control-height-md: 2.25rem;
  --control-height-lg: 2.5rem;
  --control-icon-md: 1rem;

  /* Meaning-bound accents: web status-info marks citations, status-success marks approval. */
  --citation-surface: #e7effc;
  --citation-content: #245fda;
  --approval-surface: #e6f2e7;
  --approval-content: #007a2d;

  --elevation-1: 0 1px 2px #0000000d;
  --elevation-2: 0 0 0 1px #00000005, 0 4px 8px -2px #00000014, 0 12px 24px -4px #00000014;
  --radius: 0.5rem;

  --page-width-wide: 80rem;
  --page-gutter: 1rem;
}

@media (min-width: 40rem) {
  :root {
    --page-gutter: 1.5rem;
  }
}

@media (min-width: 64rem) {
  :root {
    --page-gutter: 2rem;
  }
}
```

- [x] **Step 2: Create `landing/src/styles/theme.css`**

```css
@theme inline {
  --font-sans: "Hanken Grotesk Variable", "Segoe UI Variable", "Segoe UI", sans-serif;

  --radius-lg: var(--radius);
  --shadow-sm: var(--elevation-1);
  --shadow-md: var(--elevation-2);

  --color-surface-canvas: var(--surface-canvas);
  --color-surface-base: var(--surface-base);
  --color-surface-raised: var(--surface-raised);
  --color-content-primary: var(--content-primary);
  --color-content-secondary: var(--content-secondary);
  --color-content-muted: var(--content-muted);
  --color-content-inverse: var(--content-inverse);
  --color-border-subtle: var(--border-subtle);
  --color-border-default: var(--border-default);
  --color-border-strong: var(--border-strong);
  --color-focus-ring: var(--focus-ring);
  --color-citation-surface: var(--citation-surface);
  --color-citation-content: var(--citation-content);
  --color-approval-surface: var(--approval-surface);
  --color-approval-content: var(--approval-content);
}

/* Landing-only display sizes. */
@utility font-display {
  font-family: var(--font-sans);
  font-size: clamp(2.5rem, 1.6rem + 3.6vw, 4rem);
  font-weight: 620;
  line-height: 1.04;
  letter-spacing: -0.03em;
  text-wrap: balance;
}

@utility font-heading-section {
  font-family: var(--font-sans);
  font-size: clamp(1.875rem, 1.5rem + 1.5vw, 2.5rem);
  font-weight: 600;
  line-height: 1.1;
  letter-spacing: -0.02em;
  text-wrap: balance;
}

@utility font-lead {
  font-family: var(--font-sans);
  font-size: 1.125rem;
  font-weight: 450;
  line-height: 1.75rem;
  text-wrap: pretty;
}

/* The following presets match web/src/styles/theme.css. */
@utility font-heading-h2 {
  font-family: var(--font-sans);
  font-size: 1.5rem;
  font-weight: 600;
  line-height: 2.25rem;
  letter-spacing: -0.01em;
}

@utility font-heading-h3 {
  font-family: var(--font-sans);
  font-size: 1.125rem;
  font-weight: 600;
  line-height: 1.75rem;
  letter-spacing: -0.01em;
}

@utility font-main-content-body {
  font-family: var(--font-sans);
  font-size: 1rem;
  font-weight: 450;
  line-height: 1.5rem;
}

@utility font-main-ui-body {
  font-family: var(--font-sans);
  font-size: 0.875rem;
  font-weight: 500;
  line-height: 1.25rem;
}

@utility font-main-ui-action {
  font-family: var(--font-sans);
  font-size: 0.875rem;
  font-weight: 600;
  line-height: 1.25rem;
}

@utility font-secondary-body {
  font-family: var(--font-sans);
  font-size: 0.75rem;
  font-weight: 400;
  line-height: 1rem;
}

@utility font-secondary-action {
  font-family: var(--font-sans);
  font-size: 0.75rem;
  font-weight: 600;
  line-height: 1rem;
}
```

- [x] **Step 3: Create `landing/src/styles/base.css`**

```css
@layer base {
  *,
  ::before,
  ::after {
    border-color: var(--border-subtle);
  }

  html {
    font-family: var(--font-sans);
    scroll-behavior: smooth;
  }

  body {
    @apply min-w-80 bg-surface-base font-main-content-body text-content-primary antialiased;
  }

  :where(a, summary) {
    -webkit-tap-highlight-color: transparent;
  }

  :where(a, summary):focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }

  @media (pointer: coarse) {
    [data-slot="action-link"] {
      min-height: 2.75rem;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    *,
    ::before,
    ::after {
      animation-duration: 0.01ms !important;
      animation-iteration-count: 1 !important;
      transition-duration: 0.01ms !important;
      scroll-behavior: auto !important;
    }
  }
}
```

- [x] **Step 4: Create `landing/src/index.css`**

```css
@import "tailwindcss";
@import "@fontsource-variable/hanken-grotesk";
@import "./styles/tokens.css";
@import "./styles/theme.css";
@import "./styles/base.css";
```

- [x] **Step 5: Format and commit**

Run: `pnpm --dir landing format` then `pnpm --dir landing format:check`
Expected: exit 0.

```bash
git add landing/src/styles landing/src/index.css
git commit -m "feat(landing): add MemoryOS light tokens and typography"
```

---

### Task 3: Site metadata and public assets

**Files:**
- Test: `landing/tests/site-metadata.test.mjs`
- Create: `landing/index.html`, `landing/public/favicon.svg`, `landing/public/robots.txt`, `landing/public/sitemap.xml`, `landing/public/THIRD_PARTY_NOTICES.txt`, `landing/scripts/og-image.html`
- Generate: `landing/public/og-image.png`

- [x] **Step 1: Write the failing metadata test**

`landing/tests/site-metadata.test.mjs`:

```js
import { existsSync, readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const siteOrigin = "https://vanda.app";
const landingRoot = new URL("../", import.meta.url);
const head = new DOMParser().parseFromString(
  readFileSync(new URL("index.html", landingRoot), "utf8"),
  "text/html",
).head;

function attribute(selector, name) {
  return head.querySelector(selector)?.getAttribute(name) ?? "";
}

function publicFileFor(url) {
  const { origin, pathname } = new URL(url);
  expect(origin).toBe(siteOrigin);
  return new URL(`public${pathname}`, landingRoot);
}

function structuredData() {
  const blocks = head.querySelectorAll('script[type="application/ld+json"]');
  expect(blocks).toHaveLength(1);
  const graph = JSON.parse(blocks[0].textContent)["@graph"];
  return (type) => graph.find((node) => node["@type"] === type);
}

describe("site metadata", () => {
  it("declares the canonical site URL", () => {
    expect(attribute('link[rel="canonical"]', "href")).toBe(`${siteOrigin}/`);
    expect(attribute('meta[property="og:url"]', "content")).toBe(`${siteOrigin}/`);
  });

  it.each(['meta[property="og:image"]', 'meta[name="twitter:image"]'])(
    "serves %s from the public directory",
    (selector) => {
      expect(existsSync(publicFileFor(attribute(selector, "content")))).toBe(true);
    },
  );

  it("describes Vanda, its backer and MemoryOS as structured data", () => {
    const nodeOfType = structuredData();
    const organization = nodeOfType("Organization");

    expect(organization).toMatchObject({
      name: "Vanda",
      url: `${siteOrigin}/`,
      email: "aws@vanda.app",
      funder: { name: "GenAI Fund" },
    });
    expect(existsSync(publicFileFor(organization.logo))).toBe(true);
    expect(nodeOfType("SoftwareApplication")).toMatchObject({
      name: "MemoryOS",
      publisher: { "@id": organization["@id"] },
    });
    expect(nodeOfType("WebSite")).toMatchObject({ url: `${siteOrigin}/` });
  });
});
```

- [x] **Step 2: Run it to verify it fails**

Run: `pnpm --dir landing exec vitest run tests/site-metadata.test.mjs`
Expected: FAIL with `ENOENT` for `index.html`.

- [x] **Step 3: Create `landing/index.html`**

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>MemoryOS by Vanda | Governed AI knowledge for your company</title>
    <meta
      name="description"
      content="MemoryOS connects company documents and business systems, answers with citations, and applies one permission model to search, custom agents and MCP. It runs in your own AWS environment."
    />
    <meta name="theme-color" content="#fafafa" />
    <link rel="canonical" href="https://vanda.app/" />
    <link rel="icon" type="image/svg+xml" href="/favicon.svg" />
    <meta property="og:type" content="website" />
    <meta property="og:site_name" content="Vanda" />
    <meta property="og:url" content="https://vanda.app/" />
    <meta property="og:title" content="MemoryOS by Vanda" />
    <meta
      property="og:description"
      content="One governed memory for your people and AI agents: cited answers, one permission model, and deployment in your own AWS environment."
    />
    <meta property="og:image" content="https://vanda.app/og-image.png" />
    <meta property="og:image:width" content="1200" />
    <meta property="og:image:height" content="630" />
    <meta
      property="og:image:alt"
      content="MemoryOS by Vanda: one governed memory for your people and AI agents"
    />
    <meta name="twitter:card" content="summary_large_image" />
    <meta name="twitter:title" content="MemoryOS by Vanda" />
    <meta
      name="twitter:description"
      content="One governed memory for your people and AI agents: cited answers, one permission model, and deployment in your own AWS environment."
    />
    <meta name="twitter:image" content="https://vanda.app/og-image.png" />
    <script type="application/ld+json">
      {
        "@context": "https://schema.org",
        "@graph": [
          {
            "@type": "Organization",
            "@id": "https://vanda.app/#organization",
            "name": "Vanda",
            "url": "https://vanda.app/",
            "logo": "https://vanda.app/favicon.svg",
            "email": "aws@vanda.app",
            "funder": { "@type": "Organization", "name": "GenAI Fund" }
          },
          {
            "@type": "SoftwareApplication",
            "@id": "https://vanda.app/#memoryos",
            "name": "MemoryOS",
            "applicationCategory": "BusinessApplication",
            "operatingSystem": "Web",
            "url": "https://vanda.app/",
            "description": "An AI and knowledge layer that connects approved company sources, answers with citations, and applies one permission model to search, custom agents and MCP clients.",
            "publisher": { "@id": "https://vanda.app/#organization" }
          },
          {
            "@type": "WebSite",
            "@id": "https://vanda.app/#website",
            "name": "MemoryOS by Vanda",
            "url": "https://vanda.app/",
            "publisher": { "@id": "https://vanda.app/#organization" }
          }
        ]
      }
    </script>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

- [x] **Step 4: Create the public text assets**

`landing/public/favicon.svg` (same mark as `web/public/favicon.svg`):

```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">
  <rect width="32" height="32" rx="8" fill="#0a0a0a"/>
  <path d="M8 9h3.2l4.8 7 4.8-7H24v14h-3.3v-8.7L16 21l-4.7-6.7V23H8V9Z" fill="#f4f2eb"/>
</svg>
```

`landing/public/robots.txt`:

```text
User-agent: *
Allow: /

Sitemap: https://vanda.app/sitemap.xml
```

`landing/public/sitemap.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url>
    <loc>https://vanda.app/</loc>
  </url>
</urlset>
```

`landing/public/THIRD_PARTY_NOTICES.txt`:

```text
MemoryOS landing page third-party notices

Open SaaS
https://github.com/wasp-lang/open-saas
Copyright (c) 2023 wasp-lang

The MemoryOS landing page follows the section composition, single content
module, capability grid and highlighted-feature layout of the MIT-licensed
Open SaaS template (template/app/src/landing-page at commit
cbd30162b05d798b3a3f955ab5781940b67bec89).

Onyx / Opal
https://github.com/onyx-dot-app/onyx
Copyright (c) 2023-present DanswerAI, Inc.

The landing page reuses the MemoryOS adaptation of the MIT-licensed Onyx/Opal
neutral palette, action tokens and typography presets
(web/lib/shared/tokens/primitives.json, web/lib/shared/tokens/semantic-light.json
and web/lib/shared/tokens/typography-presets.json). Only material outside
Onyx's ee directories is included. No Onyx branding assets or proprietary
KH Teka font is included.

Product names and logos belong to their respective owners; their inclusion
does not imply endorsement.

MIT Expat license (applies to each notice above)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

- [x] **Step 5: Create the OG image source and render it**

Confirm the font file name first: `ls landing/node_modules/@fontsource-variable/hanken-grotesk/files/ | grep latin-wght-normal`
Expected: `hanken-grotesk-latin-wght-normal.woff2` (use the printed name in the `src` below if it differs).

`landing/scripts/og-image.html`:

```html
<!doctype html>
<!--
  Source of public/og-image.png (1200 × 630). After `pnpm install`, from landing/:
  chrome --headless=new --allow-file-access-from-files --hide-scrollbars
    --window-size=1200,630 --screenshot=public/og-image.png scripts/og-image.html
-->
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <title>MemoryOS by Vanda</title>
    <style>
      @font-face {
        font-family: "Hanken Grotesk";
        src: url("../node_modules/@fontsource-variable/hanken-grotesk/files/hanken-grotesk-latin-wght-normal.woff2")
          format("woff2");
        font-weight: 100 900;
      }

      html,
      body {
        margin: 0;
      }

      body {
        box-sizing: border-box;
        display: flex;
        flex-direction: column;
        justify-content: space-between;
        width: 1200px;
        height: 630px;
        padding: 72px 80px;
        background: #fafafa;
        color: #000000e5;
        font-family: "Hanken Grotesk", sans-serif;
      }

      .brand {
        display: flex;
        align-items: center;
        gap: 16px;
        font-size: 34px;
        font-weight: 600;
      }

      .brand span {
        color: #0000008c;
        font-weight: 450;
      }

      h1 {
        max-width: 940px;
        margin: 0;
        font-size: 78px;
        font-weight: 620;
        line-height: 1.04;
        letter-spacing: -0.03em;
      }

      .footer {
        display: flex;
        justify-content: space-between;
        color: #000000bf;
        font-size: 28px;
        font-weight: 500;
      }
    </style>
  </head>
  <body>
    <div class="brand">
      <svg width="52" height="52" viewBox="0 0 32 32" aria-hidden="true">
        <rect width="32" height="32" rx="8" fill="#0a0a0a" />
        <path d="M8 9h3.2l4.8 7 4.8-7H24v14h-3.3v-8.7L16 21l-4.7-6.7V23H8V9Z" fill="#f4f2eb" />
      </svg>
      MemoryOS <span>by Vanda</span>
    </div>
    <h1>One governed memory for your people and AI agents</h1>
    <div class="footer">
      <p>Deploying with Tasco. Backed by GenAI Fund.</p>
      <p>vanda.app</p>
    </div>
  </body>
</html>
```

Run (Windows Git Bash, from the worktree root):

```bash
"/c/Program Files/Google/Chrome/Application/chrome.exe" --headless=new --allow-file-access-from-files --hide-scrollbars --window-size=1200,630 "--screenshot=$(cygpath -w "$PWD/landing/public/og-image.png")" "file:///$(cygpath -m "$PWD/landing/scripts/og-image.html")"
file landing/public/og-image.png
```

Expected: `PNG image data, 1200 x 630`. Open the PNG and confirm Hanken Grotesk rendered (geometric sans, not a serif fallback) and nothing is clipped.

- [x] **Step 6: Run the metadata test to verify it passes**

Run: `pnpm --dir landing exec vitest run tests/site-metadata.test.mjs`
Expected: PASS, 4 tests.

- [x] **Step 7: Format, lint and commit**

Run: `pnpm --dir landing format` then `pnpm --dir landing lint`
Expected: exit 0.

```bash
git add landing/index.html landing/public landing/scripts/og-image.html landing/tests
git commit -m "feat(landing): add site metadata, structured data and public assets"
```

---

### Task 4: Content module and shared primitives

**Files:**
- Create: `landing/src/content.ts`, `landing/src/components/action-link.tsx`, `landing/src/components/section.tsx`, `landing/src/components/brand-mark.tsx`

These have no direct tests; the page contract in Task 5 exercises them.

- [x] **Step 1: Create `landing/src/content.ts`**

```ts
import type { LucideIcon } from "lucide-react";
import {
  BadgeCheck,
  Bot,
  Cable,
  ChartColumn,
  KeyRound,
  Plug,
  Quote,
  Search,
  ShieldCheck,
} from "lucide-react";

type Link = {
  label: string;
  href: string;
};

type FooterLink = Link & {
  external?: boolean;
};

type SectionIntro = {
  title: string;
  description: string;
};

type Entry = {
  title: string;
  description: string;
};

type Highlight = Entry & {
  points: readonly string[];
};

type CapabilitySize = "wide" | "standard" | "full";

type Capability = Entry & {
  icon: LucideIcon;
  size: CapabilitySize;
};

type DeploymentHost = {
  title: string;
  platform: string;
  services: readonly string[];
};

type Milestone = Entry & {
  period: string;
  dateTime: string;
};

const contact = {
  label: "Contact us",
  email: "aws@vanda.app",
  href: "mailto:aws@vanda.app",
} as const;

const navigation: readonly Link[] = [
  { label: "Product", href: "#product" },
  { label: "How it works", href: "#how-it-works" },
  { label: "Deployment", href: "#deployment" },
  { label: "Roadmap", href: "#roadmap" },
  { label: "FAQ", href: "#faq" },
];

const hero = {
  title: "One governed memory for your people and AI agents",
  description:
    "MemoryOS connects your company's documents and business systems, answers questions with citations, and applies the same access rules to search, custom agents and MCP clients. It runs in your own AWS environment.",
  secondaryAction: { label: "See how it works", href: "#how-it-works" },
} as const;

const productPreview = {
  label: "Example of a cited answer in MemoryOS",
  question: "What is the approval flow for a new supplier contract?",
  assistantName: "MemoryOS",
  answer: [
    {
      text: "Contracts above your department's spending limit need Legal review before signature.",
      citation: 1,
    },
    {
      text: "After approval, Procurement registers the supplier and attaches the signed contract.",
      citation: 2,
    },
  ],
  sourcesLabel: "Sources",
  citations: [
    { index: 1, title: "Procurement policy 2026.pdf", location: "Google Drive" },
    { index: 2, title: "Supplier onboarding checklist.docx", location: "Uploaded file" },
  ],
  accessNote: "Answered only from documents you can access",
} as const;

const trustSignals: readonly Entry[] = [
  {
    title: "Deploying with Tasco",
    description: "Tasco runs MemoryOS in a Production PoC from September to December 2026.",
  },
  {
    title: "Backed by GenAI Fund",
    description: "Vanda builds MemoryOS with backing from GenAI Fund.",
  },
];

const product = {
  title: "Knowledge people can trust, and AI your company can govern",
  description:
    "MemoryOS is a separate AI and knowledge layer over the systems you already use. Employees and AI agents work from approved company data, with sources they can check.",
  search: {
    title: "Ask once instead of searching five systems",
    description:
      "Policies live in Drive, specifications in shared files, and numbers in business systems. MemoryOS indexes the sources your company approves and gives everyone one place to search and ask. Every answer cites the passages it used.",
    points: [
      "Full-text and semantic search across approved sources",
      "Answers that cite the original document and passage",
      "Built-in Python that turns data into tables, charts and reports",
    ],
    sources: ["Google Drive", "Uploaded files", "OpenAPI and REST APIs", "Approved business systems"],
    indexLabel: "MemoryOS index",
    outputs: ["Search", "Cited answers", "Analysis and reports"],
  },
  governance: {
    title: "Enterprise AI that follows your access rules",
    description:
      "People sign in with your SSO. MemoryOS checks access before it retrieves anything, so the model only sees what that person may read. Search, custom agents and MCP clients share one permission model, and every AI asset has an owner, a version and an approval.",
    points: [
      "SSO/OIDC with your existing identity provider",
      "Access checked before every retrieval",
      "Owners, versions and approvals for agents, instructions and tools",
    ],
    asset: {
      name: "Supplier review agent",
      status: "Approved",
      fields: [
        { term: "Owner", detail: "Procurement team" },
        { term: "Version", detail: "3" },
        { term: "Knowledge", detail: "Procurement policies" },
        { term: "Tools", detail: "Contract lookup" },
        { term: "Available to", detail: "Procurement and Legal" },
      ],
    },
  },
} as const;

const capabilities: SectionIntro & { items: readonly Capability[] } = {
  title: "Everything a company knowledge layer needs",
  description:
    "Each capability works under the same permission model, from the first connected source to the last agent.",
  items: [
    {
      title: "Data connectors",
      description:
        "Connect Google Drive, uploaded files, OpenAPI and REST APIs, and other business systems you approve. Docling and OCR extract text from documents and scans, and the index stays current as sources change.",
      icon: Cable,
      size: "wide",
    },
    {
      title: "Enterprise search",
      description:
        "Hybrid full-text and vector search across everything a person is allowed to see, ranked for relevance.",
      icon: Search,
      size: "wide",
    },
    {
      title: "Cited answers",
      description:
        "Retrieval-augmented answers link each claim to the document and passage it came from.",
      icon: Quote,
      size: "standard",
    },
    {
      title: "Analysis and reports",
      description:
        "Built-in Python calculates, builds tables and charts, and produces reports from retrieved data.",
      icon: ChartColumn,
      size: "standard",
    },
    {
      title: "Enterprise SSO",
      description:
        "Sign in through SSO/OIDC and the identity and access management you already run.",
      icon: KeyRound,
      size: "standard",
    },
    {
      title: "Custom agents",
      description:
        "Agents for each department or workflow, with their own knowledge, instructions and tools.",
      icon: Bot,
      size: "standard",
    },
    {
      title: "AI asset governance",
      description:
        "Versions, owners, approvals and permissions for agents, instructions and tools, so teams reuse what is approved.",
      icon: BadgeCheck,
      size: "standard",
    },
    {
      title: "MCP server",
      description:
        "Approved knowledge and tools for Codex, Claude Desktop and other agents through the Model Context Protocol.",
      icon: Plug,
      size: "standard",
    },
    {
      title: "Permission-aware by design",
      description:
        "Access is checked before retrieval for search, answers, agents and MCP alike. Only permitted context ever reaches the model.",
      icon: ShieldCheck,
      size: "full",
    },
  ],
};

const howItWorks: SectionIntro & {
  steps: readonly Entry[];
  request: { title: string; steps: readonly Entry[] };
} = {
  title: "From connected sources to answers you can verify",
  description: "The same path serves every search, answer, agent and MCP request.",
  steps: [
    {
      title: "Connect",
      description:
        "Administrators connect approved sources. Each source keeps its access rules.",
    },
    {
      title: "Index",
      description:
        "Workers extract text with Docling and OCR, split it into passages, create embeddings and keep the full-text and vector index current.",
    },
    {
      title: "Ask",
      description:
        "People search and ask in plain language, work with custom agents, or use the same knowledge from MCP clients.",
    },
    {
      title: "Verify",
      description:
        "Every answer cites its documents and passages, so people can check the source before they act.",
    },
  ],
  request: {
    title: "On every request",
    steps: [
      {
        title: "Sign in with SSO",
        description: "Identity comes from your SSO/OIDC provider and existing IAM.",
      },
      {
        title: "Check access first",
        description: "MemoryOS applies access rules before any retrieval runs.",
      },
      {
        title: "Send only permitted context",
        description: "The model receives only passages the person is allowed to read.",
      },
    ],
  },
};

const deployment: SectionIntro & {
  caption: string;
  people: Entry;
  account: string;
  network: string;
  application: DeploymentHost;
  data: DeploymentHost;
  model: DeploymentHost;
  sharedServices: readonly Entry[];
} = {
  title: "Runs inside your AWS environment",
  description:
    "MemoryOS ships as containers on two Amazon EC2 instances in a private VPC. Only the web and API entry point is public; application, data and model traffic stays on private networking or protected endpoints.",
  caption: "MemoryOS Production PoC architecture on AWS",
  people: { title: "Employees and MCP clients", description: "HTTPS with SSO sign-in" },
  account: "Your AWS account",
  network: "Private VPC",
  application: {
    title: "Application and processing",
    platform: "Amazon EC2",
    services: [
      "MemoryOS web and API",
      "Connectors and workers",
      "Docling and OCR",
      "Reverse proxy and monitoring",
    ],
  },
  data: {
    title: "Data, search and storage",
    platform: "Amazon EC2 with Amazon EBS",
    services: [
      "PostgreSQL for metadata, users and access rules",
      "Redis for queues and cache",
      "OpenSearch for full-text and vector search",
      "MinIO for original files and citations",
    ],
  },
  model: {
    title: "Managed model endpoint",
    platform: "On AWS",
    services: ["Receives only permitted context", "No GPU servers for you to run"],
  },
  sharedServices: [
    { title: "Amazon S3 and EBS snapshots", description: "Independent backups" },
    { title: "Amazon CloudWatch", description: "Monitoring and alerts" },
    { title: "AWS KMS and Secrets Manager", description: "Encryption keys and secrets" },
    { title: "AWS IAM", description: "Least-privilege access" },
    { title: "Amazon ECR", description: "Container images" },
  ],
};

const roadmap: SectionIntro & {
  milestones: readonly Milestone[];
  next: { title: string; items: readonly Entry[] };
} = {
  title: "From Production PoC to company-wide memory",
  description:
    "The Tasco Production PoC runs from September to December 2026, and each month ends with a working deliverable.",
  milestones: [
    {
      period: "September 2026",
      dateTime: "2026-09",
      title: "Foundation",
      description:
        "Infrastructure, private network, SSO and sample data confirmed. Web, API, security and monitoring deployed.",
    },
    {
      period: "October 2026",
      dateTime: "2026-10",
      title: "Knowledge",
      description:
        "Sources connected, ingested and indexed. Enterprise search and cited answers respect access rules.",
    },
    {
      period: "November 2026",
      dateTime: "2026-11",
      title: "Agents",
      description:
        "Model integration and benchmark. Custom agents, AI asset governance and the MCP server working end to end.",
    },
    {
      period: "December 2026",
      dateTime: "2026-12",
      title: "Acceptance",
      description:
        "User testing. Answer quality, citations, latency and performance measured. PoC report and next-phase proposal delivered.",
    },
  ],
  next: {
    title: "After the PoC",
    items: [
      {
        title: "Company-wide rollout",
        description: "Every department on the same governed memory.",
      },
      {
        title: "More business systems",
        description: "Connectors for the ERP, CRM and HR systems teams depend on.",
      },
      {
        title: "Web search and deep research",
        description: "Answers that combine internal knowledge with vetted external sources.",
      },
      {
        title: "High availability",
        description: "Multi-AZ deployment for production scale.",
      },
    ],
  },
};

const faq: SectionIntro & { items: readonly { question: string; answer: string }[] } = {
  title: "Frequently asked questions",
  description: "What IT, security and business teams ask before a pilot.",
  items: [
    {
      question: "What is MemoryOS?",
      answer:
        "MemoryOS is an AI and knowledge layer built by Vanda. It connects approved company sources, answers questions with citations, and gives employees, custom agents and MCP clients one permission model.",
    },
    {
      question: "Where does our data stay?",
      answer:
        "In your own cloud environment. MemoryOS runs on Amazon EC2 in a private VPC, stores backups on Amazon S3 and EBS snapshots, and keeps keys and secrets in AWS KMS and Secrets Manager.",
    },
    {
      question: "Which AI model answers questions?",
      answer:
        "A managed large language model endpoint on AWS. MemoryOS sends it only the context the signed-in person may read, and you do not operate GPU servers.",
    },
    {
      question: "How are permissions enforced?",
      answer:
        "People sign in through SSO/OIDC. MemoryOS checks access rules before retrieval, so search, answers, custom agents and MCP clients return only what that person may see.",
    },
    {
      question: "Which sources can we connect?",
      answer:
        "Google Drive, uploaded files, OpenAPI and REST APIs, and other business systems your company approves. Docling and OCR process documents, including scanned files.",
    },
    {
      question: "Who uses MemoryOS today?",
      answer:
        "Tasco is deploying MemoryOS in a Production PoC from September to December 2026. Vanda is backed by GenAI Fund.",
    },
    {
      question: "How do we start?",
      answer:
        "Email aws@vanda.app. We scope a pilot around your sources, identity provider and first use cases.",
    },
  ],
};

const footer: SectionIntro & {
  action: string;
  organization: string;
  links: readonly FooterLink[];
} = {
  title: "Bring MemoryOS to your company",
  description:
    "Tell us about your sources, identity provider and first use cases, and we will scope a pilot together.",
  action: "Email aws@vanda.app",
  organization: "Vanda",
  links: [
    {
      label: "Source code on GitHub",
      href: "https://github.com/kl3inIT/MemoryOS",
      external: true,
    },
    { label: "Third-party notices", href: "/THIRD_PARTY_NOTICES.txt" },
  ],
};

export {
  capabilities,
  contact,
  deployment,
  faq,
  footer,
  hero,
  howItWorks,
  navigation,
  product,
  productPreview,
  roadmap,
  trustSignals,
  type Capability,
  type CapabilitySize,
  type DeploymentHost,
  type Highlight,
};
```

- [x] **Step 2: Create `landing/src/components/action-link.tsx`**

```tsx
import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";

type ActionProminence = "primary" | "secondary" | "tertiary";
type ActionSize = "md" | "lg";

// Default-tone rows of the web action matrix (web/src/components/ui/action-styles.ts), as links.
const prominenceClasses: Record<ActionProminence, string> = {
  primary:
    "border-transparent bg-[var(--action-default-primary-surface)] text-[var(--action-default-primary-content)] hover:bg-[var(--action-default-primary-surface-hover)] active:bg-[var(--action-default-primary-surface-active)]",
  secondary:
    "border-[var(--action-default-secondary-border)] bg-[var(--action-default-secondary-surface)] text-[var(--action-default-secondary-content)] hover:border-[var(--action-default-secondary-border-hover)] hover:bg-[var(--action-default-secondary-surface-hover)] hover:text-[var(--action-default-secondary-content-hover)] active:bg-[var(--action-default-secondary-surface-active)]",
  tertiary:
    "border-transparent bg-transparent text-[var(--action-default-tertiary-content)] hover:bg-[var(--action-default-tertiary-surface-hover)] hover:text-[var(--action-default-tertiary-content-hover)] active:bg-[var(--action-default-tertiary-surface-active)]",
};

const sizeClasses: Record<ActionSize, string> = {
  md: "h-[var(--control-height-md)] gap-2 px-3",
  lg: "h-[var(--control-height-lg)] gap-2 px-4",
};

type ActionLinkProps = ComponentProps<"a"> & {
  prominence?: ActionProminence;
  size?: ActionSize;
};

function ActionLink({ prominence = "primary", size = "md", className, ...props }: ActionLinkProps) {
  return (
    <a
      {...props}
      data-slot="action-link"
      className={cn(
        "inline-flex shrink-0 items-center justify-center rounded-lg border font-main-ui-action whitespace-nowrap transition-colors duration-150 outline-none select-none focus-visible:ring-3 focus-visible:ring-focus-ring/40 focus-visible:ring-offset-2 focus-visible:ring-offset-surface-base [&_svg]:size-[var(--control-icon-md)] [&_svg]:shrink-0",
        prominenceClasses[prominence],
        sizeClasses[size],
        className,
      )}
    />
  );
}

export { ActionLink };
```

- [x] **Step 3: Create `landing/src/components/section.tsx`**

```tsx
import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

type SectionProps = {
  id: string;
  title: string;
  description?: string;
  className?: string;
  children: ReactNode;
};

function Section({ id, title, description, className, children }: SectionProps) {
  const headingId = `${id}-heading`;

  return (
    <section
      id={id}
      aria-labelledby={headingId}
      className={cn("scroll-mt-16 px-[var(--page-gutter)] py-20 sm:py-28", className)}
    >
      <div className="mx-auto w-full max-w-[var(--page-width-wide)]">
        <div className="max-w-2xl">
          <h2 id={headingId} className="font-heading-section text-content-primary">
            {title}
          </h2>
          {description ? (
            <p className="mt-4 font-lead text-content-secondary">{description}</p>
          ) : null}
        </div>
        <div className="mt-12 sm:mt-16">{children}</div>
      </div>
    </section>
  );
}

export { Section };
```

- [x] **Step 4: Create `landing/src/components/brand-mark.tsx`**

```tsx
type BrandMarkProps = {
  className?: string;
};

// Same mark as public/favicon.svg; always shown next to the product name, so it is decorative.
function BrandMark({ className }: BrandMarkProps) {
  return (
    <svg viewBox="0 0 32 32" aria-hidden="true" className={className}>
      <rect width="32" height="32" rx="8" fill="#0a0a0a" />
      <path d="M8 9h3.2l4.8 7 4.8-7H24v14h-3.3v-8.7L16 21l-4.7-6.7V23H8V9Z" fill="#f4f2eb" />
    </svg>
  );
}

export { BrandMark };
```

- [x] **Step 5: Typecheck, format, lint and commit**

Run: `pnpm --dir landing format` then `pnpm --dir landing lint` then `pnpm --dir landing typecheck`
Expected: exit 0.

```bash
git add landing/src/content.ts landing/src/components
git commit -m "feat(landing): add page content and shared primitives"
```

---

### Task 5: Page sections and composition

**Files:**
- Test: `landing/src/App.test.tsx`
- Create: `landing/src/sections/header.tsx`, `hero.tsx`, `product-preview.tsx`, `trust-strip.tsx`, `product-highlights.tsx`, `capabilities.tsx`, `how-it-works.tsx`, `deployment.tsx`, `roadmap.tsx`, `faq.tsx`, `footer.tsx`, `landing/src/App.tsx`, `landing/src/main.tsx`

- [x] **Step 1: Write the failing page contract test**

`landing/src/App.test.tsx`:

```tsx
import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { App } from "@/App";

function renderPage() {
  return render(<App />).container;
}

describe("landing page", () => {
  it("has exactly one level-one heading", () => {
    expect(renderPage().querySelectorAll("h1")).toHaveLength(1);
  });

  it("points every in-page link at a rendered element", () => {
    const page = renderPage();
    const targets = [...page.querySelectorAll('a[href^="#"]')].map(
      (link) => link.getAttribute("href")?.slice(1) ?? "",
    );

    expect(targets).toEqual(
      expect.arrayContaining(["main", "product", "how-it-works", "deployment", "roadmap", "faq"]),
    );
    for (const target of targets) {
      expect(page.querySelector(`[id="${target}"]`), `#${target}`).not.toBeNull();
    }
  });

  it("sends every email link to the AWS contact address", () => {
    const emailLinks = [...renderPage().querySelectorAll('a[href^="mailto:"]')];

    expect(emailLinks.length).toBeGreaterThan(0);
    expect(new Set(emailLinks.map((link) => link.getAttribute("href")))).toEqual(
      new Set(["mailto:aws@vanda.app"]),
    );
  });

  it("isolates every new-tab link from the opener", () => {
    const newTabLinks = [...renderPage().querySelectorAll('a[target="_blank"]')];

    expect(newTabLinks.length).toBeGreaterThan(0);
    for (const link of newTabLinks) {
      expect(link.getAttribute("rel")?.split(" ")).toEqual(
        expect.arrayContaining(["noopener", "noreferrer"]),
      );
    }
  });

  it("names every graphic or hides it from assistive technology", () => {
    const page = renderPage();

    for (const image of page.querySelectorAll("img")) {
      expect(image.hasAttribute("alt")).toBe(true);
    }
    for (const graphic of page.querySelectorAll("svg")) {
      const named =
        graphic.getAttribute("role") === "img" &&
        (graphic.hasAttribute("aria-label") || graphic.hasAttribute("aria-labelledby"));
      expect(named || graphic.getAttribute("aria-hidden") === "true", graphic.outerHTML).toBe(true);
    }
  });
});
```

- [x] **Step 2: Run it to verify it fails**

Run: `pnpm --dir landing exec vitest run src/App.test.tsx`
Expected: FAIL, cannot resolve `@/App`.

- [x] **Step 3: Create the header**

`landing/src/sections/header.tsx`:

```tsx
import { Menu } from "lucide-react";
import { useRef } from "react";
import { ActionLink } from "@/components/action-link";
import { BrandMark } from "@/components/brand-mark";
import { contact, navigation } from "@/content";

function Header() {
  const mobileMenu = useRef<HTMLDetailsElement>(null);
  const closeMobileMenu = () => mobileMenu.current?.removeAttribute("open");

  return (
    <header className="sticky top-0 z-40 border-b border-border-subtle bg-surface-base/90 px-[var(--page-gutter)] backdrop-blur-md">
      <a
        href="#main"
        className="sr-only rounded-lg bg-surface-raised px-3 py-2 font-main-ui-action focus:not-sr-only focus:absolute focus:top-3 focus:left-3"
      >
        Skip to content
      </a>
      <div className="mx-auto flex h-16 max-w-[var(--page-width-wide)] items-center gap-3">
        <a href="/" className="mr-auto flex items-center gap-2.5 rounded-md">
          <BrandMark className="size-7" />
          <span className="font-heading-h3 text-content-primary">MemoryOS</span>
          <span className="hidden font-main-ui-body text-content-muted sm:inline">by Vanda</span>
        </a>
        <nav aria-label="Primary" className="hidden md:block">
          <ul className="flex items-center gap-1">
            {navigation.map((item) => (
              <li key={item.href}>
                <ActionLink href={item.href} prominence="tertiary">
                  {item.label}
                </ActionLink>
              </li>
            ))}
          </ul>
        </nav>
        <ActionLink href={contact.href}>{contact.label}</ActionLink>
        <details ref={mobileMenu} className="relative md:hidden">
          <summary className="flex size-11 cursor-pointer list-none items-center justify-center rounded-lg text-content-secondary hover:bg-surface-canvas [&::-webkit-details-marker]:hidden">
            <Menu aria-hidden="true" className="size-5" />
            <span className="sr-only">Menu</span>
          </summary>
          <nav
            aria-label="Primary"
            className="absolute top-full right-0 mt-2 w-56 rounded-xl border border-border-subtle bg-surface-raised p-2 shadow-md"
          >
            <ul>
              {navigation.map((item) => (
                <li key={item.href}>
                  <a
                    href={item.href}
                    onClick={closeMobileMenu}
                    className="block rounded-lg px-3 py-2.5 font-main-ui-action text-content-secondary hover:bg-surface-canvas hover:text-content-primary"
                  >
                    {item.label}
                  </a>
                </li>
              ))}
            </ul>
          </nav>
        </details>
      </div>
    </header>
  );
}

export { Header };
```

- [x] **Step 4: Create the hero, product preview and trust strip**

`landing/src/sections/product-preview.tsx`:

```tsx
import { LockKeyhole } from "lucide-react";
import { BrandMark } from "@/components/brand-mark";
import { productPreview } from "@/content";

type CitationMarkerProps = {
  index: number;
};

function CitationMarker({ index }: CitationMarkerProps) {
  return (
    <span className="inline-flex size-5 shrink-0 items-center justify-center rounded-md bg-citation-surface align-text-bottom font-secondary-action text-citation-content">
      <span className="sr-only">Source </span>
      {index}
    </span>
  );
}

function ProductPreview() {
  const { label, question, assistantName, answer, sourcesLabel, citations, accessNote } =
    productPreview;

  return (
    <figure aria-label={label} className="rounded-2xl bg-surface-canvas p-3 sm:p-4">
      <div className="overflow-hidden rounded-xl border border-border-subtle bg-surface-raised shadow-md">
        <div className="space-y-5 p-5 sm:p-6">
          <p className="ml-auto w-fit max-w-[85%] rounded-2xl rounded-br-md bg-surface-canvas px-4 py-2.5 font-main-content-body text-content-primary">
            {question}
          </p>
          <div>
            <p className="flex items-center gap-2 font-main-ui-action text-content-primary">
              <BrandMark className="size-5" />
              {assistantName}
            </p>
            <p className="mt-3 font-main-content-body text-content-primary">
              {answer.map((segment) => (
                <span key={segment.citation}>
                  {segment.text} <CitationMarker index={segment.citation} />{" "}
                </span>
              ))}
            </p>
          </div>
          <div>
            <p className="font-secondary-action text-content-muted">{sourcesLabel}</p>
            <ol className="mt-2 space-y-2">
              {citations.map((citation) => (
                <li
                  key={citation.index}
                  className="flex items-start gap-3 rounded-lg border border-border-subtle px-3 py-2.5"
                >
                  <CitationMarker index={citation.index} />
                  <span className="min-w-0">
                    <span className="block truncate font-main-ui-body text-content-primary">
                      {citation.title}
                    </span>
                    <span className="block font-secondary-body text-content-muted">
                      {citation.location}
                    </span>
                  </span>
                </li>
              ))}
            </ol>
          </div>
        </div>
        <p className="flex items-center gap-2 border-t border-border-subtle bg-surface-base px-5 py-3 font-secondary-body text-content-secondary sm:px-6">
          <LockKeyhole aria-hidden="true" className="size-3.5 shrink-0" />
          {accessNote}
        </p>
      </div>
    </figure>
  );
}

export { ProductPreview };
```

`landing/src/sections/hero.tsx`:

```tsx
import { ActionLink } from "@/components/action-link";
import { contact, hero } from "@/content";
import { ProductPreview } from "@/sections/product-preview";

function Hero() {
  return (
    <section
      aria-labelledby="hero-heading"
      className="px-[var(--page-gutter)] pt-14 pb-20 sm:pt-20 sm:pb-24 lg:pt-24"
    >
      <div className="mx-auto grid max-w-[var(--page-width-wide)] items-center gap-14 lg:grid-cols-[minmax(0,1fr)_minmax(0,32rem)] lg:gap-20">
        <div className="max-w-2xl">
          <h1 id="hero-heading" className="font-display text-content-primary">
            {hero.title}
          </h1>
          <p className="mt-6 max-w-xl font-lead text-content-secondary">{hero.description}</p>
          <div className="mt-10 flex flex-wrap gap-3">
            <ActionLink href={contact.href} size="lg">
              {contact.label}
            </ActionLink>
            <ActionLink href={hero.secondaryAction.href} prominence="secondary" size="lg">
              {hero.secondaryAction.label}
            </ActionLink>
          </div>
        </div>
        <ProductPreview />
      </div>
    </section>
  );
}

export { Hero };
```

`landing/src/sections/trust-strip.tsx`:

```tsx
import { trustSignals } from "@/content";

function TrustStrip() {
  return (
    <section
      aria-label="Customer and backing"
      className="border-y border-border-subtle bg-surface-raised px-[var(--page-gutter)]"
    >
      <ul className="mx-auto grid max-w-[var(--page-width-wide)] divide-y divide-border-subtle sm:grid-cols-2 sm:divide-x sm:divide-y-0">
        {trustSignals.map((signal) => (
          <li key={signal.title} className="py-6 sm:py-8 sm:pr-8 sm:not-first:pl-8">
            <p className="font-heading-h3 text-content-primary">{signal.title}</p>
            <p className="mt-1 font-main-ui-body text-content-secondary">{signal.description}</p>
          </li>
        ))}
      </ul>
    </section>
  );
}

export { TrustStrip };
```

- [x] **Step 5: Create the product highlights and capabilities**

`landing/src/sections/product-highlights.tsx`:

```tsx
import { ArrowRight, BadgeCheck, Check } from "lucide-react";
import type { ReactNode } from "react";
import { Section } from "@/components/section";
import { product, type Highlight } from "@/content";
import { cn } from "@/lib/utils";

type HighlightRowProps = {
  highlight: Highlight;
  visual: ReactNode;
  reversed?: boolean;
};

function HighlightRow({ highlight, visual, reversed = false }: HighlightRowProps) {
  return (
    <article className="grid items-center gap-10 lg:grid-cols-2 lg:gap-16">
      <div className={cn("max-w-xl", reversed && "lg:order-last")}>
        <h3 className="font-heading-h2 text-content-primary">{highlight.title}</h3>
        <p className="mt-4 font-main-content-body text-content-secondary">
          {highlight.description}
        </p>
        <ul className="mt-6 space-y-3">
          {highlight.points.map((point) => (
            <li key={point} className="flex gap-3 font-main-content-body text-content-primary">
              <Check aria-hidden="true" className="mt-1 size-4 shrink-0" />
              {point}
            </li>
          ))}
        </ul>
      </div>
      {visual}
    </article>
  );
}

function SourcesVisual() {
  const { sources, indexLabel, outputs } = product.search;

  return (
    <div className="grid items-center gap-4 rounded-2xl bg-surface-canvas p-6 sm:grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)] sm:p-8">
      <ul className="space-y-2">
        {sources.map((source) => (
          <li
            key={source}
            className="rounded-lg border border-border-subtle bg-surface-raised px-3 py-2 font-main-ui-body text-content-primary"
          >
            {source}
          </li>
        ))}
      </ul>
      <ArrowRight
        aria-hidden="true"
        className="mx-auto size-5 rotate-90 text-content-muted sm:rotate-0"
      />
      <div className="rounded-xl bg-[var(--action-default-primary-surface)] p-5 text-content-inverse">
        <p className="font-main-ui-action">{indexLabel}</p>
        <ul className="mt-3 space-y-2 font-main-ui-body text-white/75">
          {outputs.map((output) => (
            <li key={output}>{output}</li>
          ))}
        </ul>
      </div>
    </div>
  );
}

function GovernanceVisual() {
  const { asset } = product.governance;

  return (
    <div className="rounded-2xl bg-surface-canvas p-6 sm:p-8">
      <div className="rounded-xl border border-border-subtle bg-surface-raised shadow-sm">
        <div className="flex items-center justify-between gap-3 border-b border-border-subtle px-5 py-4">
          <p className="font-main-ui-action text-content-primary">{asset.name}</p>
          <span className="inline-flex items-center gap-1.5 rounded-full bg-approval-surface px-2.5 py-1 font-secondary-action text-approval-content">
            <BadgeCheck aria-hidden="true" className="size-3.5" />
            {asset.status}
          </span>
        </div>
        <dl className="divide-y divide-border-subtle px-5">
          {asset.fields.map((field) => (
            <div key={field.term} className="flex justify-between gap-4 py-3 font-main-ui-body">
              <dt className="text-content-muted">{field.term}</dt>
              <dd className="text-right text-content-primary">{field.detail}</dd>
            </div>
          ))}
        </dl>
      </div>
    </div>
  );
}

function ProductHighlights() {
  return (
    <Section id="product" title={product.title} description={product.description}>
      <div className="space-y-20 sm:space-y-28">
        <HighlightRow highlight={product.search} visual={<SourcesVisual />} />
        <HighlightRow highlight={product.governance} visual={<GovernanceVisual />} reversed />
      </div>
    </Section>
  );
}

export { ProductHighlights };
```

`landing/src/sections/capabilities.tsx`:

```tsx
import { Section } from "@/components/section";
import { capabilities, type CapabilitySize } from "@/content";
import { cn } from "@/lib/utils";

// Two columns from sm and six from lg: two wide cells, six standard cells, one full-width cell.
const spanBySize: Record<CapabilitySize, string> = {
  wide: "sm:col-span-2 lg:col-span-3",
  standard: "lg:col-span-2",
  full: "sm:col-span-2 lg:col-span-6",
};

function Capabilities() {
  return (
    <Section id="capabilities" title={capabilities.title} description={capabilities.description}>
      <ul className="grid gap-px overflow-hidden rounded-2xl border border-border-subtle bg-border-subtle sm:grid-cols-2 lg:grid-cols-6">
        {capabilities.items.map(({ title, description, icon: Icon, size }) => (
          <li key={title} className={cn("bg-surface-raised p-6 sm:p-8", spanBySize[size])}>
            <Icon aria-hidden="true" className="size-5 text-content-primary" />
            <h3 className="mt-5 font-heading-h3 text-content-primary">{title}</h3>
            <p className="mt-2 max-w-prose font-main-content-body text-content-secondary">
              {description}
            </p>
          </li>
        ))}
      </ul>
    </Section>
  );
}

export { Capabilities };
```

- [x] **Step 6: Create how it works and deployment**

`landing/src/sections/how-it-works.tsx`:

```tsx
import { Section } from "@/components/section";
import { howItWorks } from "@/content";

function HowItWorks() {
  const { steps, request } = howItWorks;

  return (
    <Section id="how-it-works" title={howItWorks.title} description={howItWorks.description}>
      <ol className="grid gap-10 sm:grid-cols-2 lg:grid-cols-4 lg:gap-8">
        {steps.map((step, index) => (
          <li key={step.title} className="border-t-2 border-content-primary pt-5">
            <p className="font-main-ui-action text-content-muted">Step {index + 1}</p>
            <h3 className="mt-2 font-heading-h3 text-content-primary">{step.title}</h3>
            <p className="mt-2 font-main-content-body text-content-secondary">{step.description}</p>
          </li>
        ))}
      </ol>
      <div className="mt-16 rounded-2xl bg-surface-canvas p-6 sm:p-8">
        <h3 className="font-heading-h3 text-content-primary">{request.title}</h3>
        <ol className="mt-6 grid gap-4 md:grid-cols-3">
          {request.steps.map((step, index) => (
            <li
              key={step.title}
              className="rounded-xl border border-border-subtle bg-surface-raised p-5"
            >
              <span className="inline-flex size-6 items-center justify-center rounded-full bg-[var(--action-default-primary-surface)] font-secondary-action text-content-inverse">
                {index + 1}
              </span>
              <p className="mt-3 font-main-ui-action text-content-primary">{step.title}</p>
              <p className="mt-1 font-main-ui-body text-content-secondary">{step.description}</p>
            </li>
          ))}
        </ol>
      </div>
    </Section>
  );
}

export { HowItWorks };
```

`landing/src/sections/deployment.tsx`:

```tsx
import { ArrowRight } from "lucide-react";
import { Section } from "@/components/section";
import { deployment, type DeploymentHost } from "@/content";

type HostCardProps = {
  host: DeploymentHost;
};

function HostCard({ host }: HostCardProps) {
  return (
    <div className="rounded-lg border border-border-subtle bg-surface-raised p-4">
      <p className="font-main-ui-action text-content-primary">{host.title}</p>
      <p className="font-secondary-body text-content-muted">{host.platform}</p>
      <ul className="mt-3 space-y-1.5 font-main-ui-body text-content-secondary">
        {host.services.map((service) => (
          <li key={service}>{service}</li>
        ))}
      </ul>
    </div>
  );
}

function Deployment() {
  const { caption, people, account, network, application, data, model, sharedServices } =
    deployment;

  return (
    <Section id="deployment" title={deployment.title} description={deployment.description}>
      <figure className="rounded-2xl bg-surface-canvas p-4 sm:p-6 lg:p-8">
        <figcaption className="font-main-ui-action text-content-secondary">{caption}</figcaption>
        <div className="mt-5 grid items-center gap-3 lg:grid-cols-[11rem_auto_minmax(0,1fr)]">
          <div className="rounded-lg border border-border-subtle bg-surface-raised p-4">
            <p className="font-main-ui-action text-content-primary">{people.title}</p>
            <p className="mt-1 font-secondary-body text-content-muted">{people.description}</p>
          </div>
          <ArrowRight
            aria-hidden="true"
            className="mx-auto size-5 rotate-90 text-content-muted lg:rotate-0"
          />
          <div className="rounded-xl border border-border-default bg-surface-base p-4 sm:p-5">
            <p className="font-main-ui-action text-content-primary">{account}</p>
            <div className="mt-4 grid gap-3 xl:grid-cols-[minmax(0,1fr)_13rem]">
              <div className="rounded-lg border border-dashed border-border-strong p-3 sm:p-4">
                <p className="font-secondary-action text-content-muted">{network}</p>
                <div className="mt-3 grid gap-3 md:grid-cols-2">
                  <HostCard host={application} />
                  <HostCard host={data} />
                </div>
              </div>
              <HostCard host={model} />
            </div>
            <ul className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
              {sharedServices.map((service) => (
                <li
                  key={service.title}
                  className="rounded-lg border border-border-subtle bg-surface-raised px-3 py-2.5"
                >
                  <p className="font-main-ui-action text-content-primary">{service.title}</p>
                  <p className="font-secondary-body text-content-muted">{service.description}</p>
                </li>
              ))}
            </ul>
          </div>
        </div>
      </figure>
    </Section>
  );
}

export { Deployment };
```

- [x] **Step 7: Create the roadmap, FAQ and footer**

`landing/src/sections/roadmap.tsx`:

```tsx
import { Section } from "@/components/section";
import { roadmap } from "@/content";

function Roadmap() {
  return (
    <Section id="roadmap" title={roadmap.title} description={roadmap.description}>
      <ol className="grid gap-10 lg:grid-cols-4 lg:gap-8">
        {roadmap.milestones.map((milestone) => (
          <li
            key={milestone.dateTime}
            className="relative border-l border-border-default pl-6 lg:border-t lg:border-l-0 lg:pt-6 lg:pl-0"
          >
            <span
              aria-hidden="true"
              className="absolute top-1.5 -left-[5.5px] size-2.5 rounded-full bg-content-primary lg:-top-[5.5px] lg:left-0"
            />
            <time dateTime={milestone.dateTime} className="font-main-ui-action text-content-muted">
              {milestone.period}
            </time>
            <h3 className="mt-2 font-heading-h3 text-content-primary">{milestone.title}</h3>
            <p className="mt-2 font-main-content-body text-content-secondary">
              {milestone.description}
            </p>
          </li>
        ))}
      </ol>
      <div className="mt-20">
        <h3 className="font-heading-h2 text-content-primary">{roadmap.next.title}</h3>
        <ul className="mt-6 grid gap-px overflow-hidden rounded-2xl border border-border-subtle bg-border-subtle sm:grid-cols-2 lg:grid-cols-4">
          {roadmap.next.items.map((item) => (
            <li key={item.title} className="bg-surface-raised p-6">
              <p className="font-heading-h3 text-content-primary">{item.title}</p>
              <p className="mt-2 font-main-content-body text-content-secondary">
                {item.description}
              </p>
            </li>
          ))}
        </ul>
      </div>
    </Section>
  );
}

export { Roadmap };
```

`landing/src/sections/faq.tsx`:

```tsx
import { ChevronDown } from "lucide-react";
import { Section } from "@/components/section";
import { faq } from "@/content";

function Faq() {
  return (
    <Section id="faq" title={faq.title} description={faq.description}>
      <div className="divide-y divide-border-subtle border-y border-border-subtle">
        {faq.items.map((item) => (
          <details key={item.question} className="group">
            <summary className="flex cursor-pointer list-none items-center justify-between gap-6 rounded-md py-5 font-heading-h3 text-content-primary [&::-webkit-details-marker]:hidden">
              {item.question}
              <ChevronDown
                aria-hidden="true"
                className="size-5 shrink-0 text-content-muted transition-transform duration-150 group-open:rotate-180"
              />
            </summary>
            <p className="max-w-3xl pb-6 font-main-content-body text-content-secondary">
              {item.answer}
            </p>
          </details>
        ))}
      </div>
    </Section>
  );
}

export { Faq };
```

`landing/src/sections/footer.tsx`:

```tsx
import { ActionLink } from "@/components/action-link";
import { contact, footer } from "@/content";

function Footer() {
  const year = new Date().getFullYear();

  return (
    <footer className="border-t border-border-subtle bg-surface-raised px-[var(--page-gutter)]">
      <div className="mx-auto max-w-[var(--page-width-wide)]">
        <div className="flex flex-col gap-8 py-16 sm:flex-row sm:items-end sm:justify-between sm:py-20">
          <div className="max-w-xl">
            <h2 className="font-heading-section text-content-primary">{footer.title}</h2>
            <p className="mt-4 font-lead text-content-secondary">{footer.description}</p>
          </div>
          <ActionLink href={contact.href} size="lg" className="self-start sm:self-auto">
            {footer.action}
          </ActionLink>
        </div>
        <div className="flex flex-col gap-4 border-t border-border-subtle py-8 font-main-ui-body text-content-muted sm:flex-row sm:items-center sm:justify-between">
          <p>
            © {year} {footer.organization}
          </p>
          <ul className="flex flex-wrap gap-x-6 gap-y-2">
            {footer.links.map((link) => (
              <li key={link.href}>
                <a
                  href={link.href}
                  className="rounded-sm hover:text-content-primary"
                  {...(link.external ? { target: "_blank", rel: "noopener noreferrer" } : {})}
                >
                  {link.label}
                </a>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </footer>
  );
}

export { Footer };
```

- [x] **Step 8: Compose the page and mount it**

`landing/src/App.tsx`:

```tsx
import { Capabilities } from "@/sections/capabilities";
import { Deployment } from "@/sections/deployment";
import { Faq } from "@/sections/faq";
import { Footer } from "@/sections/footer";
import { Header } from "@/sections/header";
import { Hero } from "@/sections/hero";
import { HowItWorks } from "@/sections/how-it-works";
import { ProductHighlights } from "@/sections/product-highlights";
import { Roadmap } from "@/sections/roadmap";
import { TrustStrip } from "@/sections/trust-strip";

function App() {
  return (
    <>
      <Header />
      <main id="main">
        <Hero />
        <TrustStrip />
        <ProductHighlights />
        <Capabilities />
        <HowItWorks />
        <Deployment />
        <Roadmap />
        <Faq />
      </main>
      <Footer />
    </>
  );
}

export { App };
```

`landing/src/main.tsx`:

```tsx
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import { App } from "@/App";

const rootElement = document.getElementById("root");

if (!rootElement) {
  throw new Error("Landing page root element is missing");
}

createRoot(rootElement).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
```

- [x] **Step 9: Run the page contract to verify it passes**

Run: `pnpm --dir landing exec vitest run src/App.test.tsx`
Expected: PASS, 5 tests.

- [x] **Step 10: Run the package gate**

Run: `pnpm --dir landing format` then `pnpm --dir landing check`
Expected: lint, format check, 12 tests, build (`Verified N emitted WOFF2 assets`) and TypeScript all succeed.

- [x] **Step 11: Review the page in a browser and correct it**

Run in the background: `pnpm --dir landing dev --host 127.0.0.1 --port 5174 --strictPort`
Take full-page screenshots at 390, 768 and 1440 px (Chrome DevTools MCP `emulate` viewport + `take_screenshot`, in the Chrome instance the session is attached to). Check: no horizontal scroll; header fits at 390 px; the preview is the most prominent element; bento rows have no empty cells at sm and lg; the deployment diagram reads top-to-bottom on mobile; focus rings are visible when tabbing; the mobile menu closes after choosing a link. Fix what fails, rerun Step 10, stop the dev server.

- [x] **Step 12: Commit**

```bash
git add landing/src
git commit -m "feat(landing): build the MemoryOS landing page"
```

---

### Task 5A: Light and dark theme

Added after Task 5 at the product owner's request. It follows the web app's theme contract (`web/src/features/theme/theme-provider.tsx`): the `dark` class on `<html>` and the `memoryos-theme` storage key, where `light` or `dark` is an explicit choice and an absent key means the system preference.

**Files:**
- Modify: `landing/src/styles/tokens.css` (add the `.dark` block copied from `web/src/styles/tokens.css` for the tokens the landing uses, dark citation/approval values from web status-info/status-success, and `--brand-mark-surface`/`--brand-mark-content`, inverted in dark)
- Modify: `landing/src/components/brand-mark.tsx` (fill from the brand-mark tokens)
- Modify: `landing/src/sections/product-highlights.tsx` (`text-white/75` → `text-content-inverse/75`, which stays readable on the inverted primary surface)
- Create: `landing/public/theme-init.js` (classic same-origin script: read the key in `try`, fall back to `prefers-color-scheme`, toggle the `dark` class)
- Modify: `landing/index.html` (`<script src="/theme-init.js"></script>` directly after the viewport meta; `theme-color` `#fafafa` for light and `#19191e` for dark via `media`)
- Create: `landing/src/lib/theme.ts` (`useTheme()` returns `{ theme, toggleTheme }`; initial state from storage or system; a layout effect toggles the class; a `matchMedia` listener follows the system while no choice is stored; storage access is wrapped because browsers can block site data)
- Modify: `landing/src/sections/header.tsx` (icon button labelled "Switch to dark/light theme" before Contact from `md`; the same action as a row under the links in the mobile menu, which keeps the 390 px header to mark, Contact and menu)
- Test: `landing/src/lib/theme.test.ts`

- [x] **Step 1: Write the hook tests** — a stored `dark` applies the class; toggling twice applies and stores `dark`, then `light`. `afterEach` clears storage and the class.
- [x] **Step 2: Implement tokens, pre-paint script, hook and header controls** as listed above.
- [x] **Step 3: Run the package gate** — `pnpm --dir landing check`. Expected: 14 tests pass; the build keeps `/theme-init.js` as a classic script in `dist/index.html` and copies it to `dist/`.
- [x] **Step 4: Review both themes in the browser** — at 390 and 1440 px, with the system preference emulated as light and as dark and storage cleared: the page follows the system with no light flash on reload, the toggle overrides it and survives reload, the citation and approval accents and every primary action remain readable in dark.
- [x] **Step 5: Commit**

```bash
git add landing docs/increments/active/mem-82-landing-page
git commit -m "feat(landing): add light and dark themes"
```

---

### Task 5B: Navy and blue technical identity

Added after Task 5A at the product owner's request, with a Laravel Cloud screenshot as the visual reference ("trông khá là tech"). The theme contract of Task 5A is unchanged: first visits follow the system preference.

**Files:**
- Modify: `landing/src/styles/tokens.css` (cool light and navy dark values under the web token names; `--accent`, `--accent-surface`, `--accent-content`, `--glow-core`, `--glow-halo`; citation tokens aligned to the brand blue)
- Modify: `landing/src/styles/theme.css` (`accent`, `accent-surface`, `accent-content` colors)
- Modify: `landing/src/styles/base.css` (`.hero-glow` behind the preview — centred below the copy on small screens, beside it from `lg` — and the quieter `.cta-glow`)
- Modify: `landing/src/sections/hero.tsx`, `footer.tsx` (decorative `aria-hidden` glow layers behind `isolate` sections)
- Modify: `landing/src/sections/product-preview.tsx` (translucent frame that lets the glow through)
- Modify: `landing/src/sections/product-highlights.tsx`, `capabilities.tsx`, `how-it-works.tsx`, `roadmap.tsx` (brand blue for checks, capability icons, the index block, step markers and timeline dots)
- Modify: `landing/index.html` (`theme-color` `#f8f9fc` / `#070a13`)
- Modify: `landing/scripts/og-image.html`, `landing/public/og-image.png` (navy card with the same glow)

- [x] **Step 1: Replace the palette and add the glow layers** as listed above.
- [x] **Step 2: Run the package gate** — `pnpm --dir landing check`. Expected: 14 tests pass.
- [x] **Step 3: Review both themes in the browser** at 1440 and 390 px: text over the glow keeps its contrast, the glow sits behind the preview at every width, and the accents stay readable in light and dark.
- [x] **Step 4: Re-render the Open Graph image** with headless Chrome at 1200 × 630 and confirm the metadata test still passes.
- [x] **Step 5: Commit**

```bash
git add landing docs/increments/active/mem-82-landing-page
git commit -m "feat(landing): adopt a navy and blue technical identity"
```

---

### Task 5C: Motion and partner logos

Added after Task 5B at the product owner's request: a theme-switch transition, motion while scrolling up and down, and the Tasco and GenAI Fund logos (supplied as `D:\Capstone Project\tasco-logo.png` and `genai-logo-black.png`).

**Files:**
- Create: `landing/src/assets/logos/tasco.png`, `genai-fund.png` (single-colour alpha masks: dark-on-light and light-on-dark sources mapped to alpha by luminance, normalised to the darkest/lightest ink, cropped to the mark)
- Modify: `landing/src/content.ts` (`trustSignals[].logo` with `src` and `label`), `landing/src/sections/trust-strip.tsx` (`role="img"` mask in a fixed `contain` box, tinted `bg-content-primary`; the second logo reveals slightly later)
- Modify: `landing/src/lib/theme.ts` (`toggleTheme(origin?)`: inside `document.startViewTransition`, `flushSync` applies the theme, then the new root view grows as a `clip-path` circle from the origin; without the API, without an origin, or with reduced motion it switches directly), `landing/src/sections/header.tsx` (origin = the toggle's centre)
- Modify: `landing/src/styles/base.css` (view-transition root reset; `.enter` load sequence; scroll-driven `.reveal`, `.progress-fill`, `.timeline-fill` — vertical below `lg` — and the `.hero-glow` drift; keyframes; everything behind `prefers-reduced-motion: no-preference` and `@supports (animation-timeline: view())`)
- Modify: `landing/src/components/section.tsx`, `landing/src/sections/hero.tsx`, `product-highlights.tsx`, `capabilities.tsx`, `how-it-works.tsx`, `deployment.tsx`, `roadmap.tsx`, `faq.tsx`, `footer.tsx` (motion classes; in hairline grids the cell content moves, not the cell; the how-it-works rules and roadmap timeline become a track plus a filling accent line)

- [x] **Step 1: Build the logo masks and the trust strip.**
- [x] **Step 2: Add the theme reveal and the motion layer** as listed above.
- [x] **Step 3: Run the package gate** — `pnpm --dir landing check`. Expected: 14 tests pass.
- [x] **Step 4: Review in the browser** — reveal on scroll down and reversal on scroll up; rules and timeline fill; glow drift; the circular theme reveal from both toggles; with reduced motion emulated, the page is static and fully visible; no console errors; logos legible in both themes.
- [x] **Step 5: Commit**

```bash
git add landing docs/increments/active/mem-82-landing-page
git commit -m "feat(landing): add motion, a theme reveal and partner logos"
```

---

### Task 5D: Landing page 6.0 motion redesign

Added after Task 10 at the product owner's request, on branch `nhuxuanviet/mem-82-landing-page-6.0` so pull request #95 stays the rollback point. Scope and decisions: the landing page 6.0 entry under [accepted decisions](design.md#accepted-decisions-2026-09-11), then the Page and Visual direction sections. GSAP ScrollTrigger replaces the CSS scroll-driven motion of Task 5C; the theme reveal and the logos stay.

**Files:**
- Modify: `landing/package.json`, `landing/pnpm-lock.yaml` (`gsap` 3.15.0, `@gsap/react` 2.1.2, exact)
- Create: `landing/src/motion/motion.ts` (ScrollTrigger registration, `useMotion`, `pinStart`, `offsetTo`)
- Modify: `landing/src/test/setup.ts` (jsdom `matchMedia` that matches nothing; canvas `getContext` returning `null`)
- Modify: `landing/src/content.ts` (one-sentence copy; ingestion stages and access gate; capability mock keys; deployment link labels; milestone deliverables; condensed next steps; FAQ answers that carry the moved detail; footer without links)
- Modify: `landing/src/styles/tokens.css`, `landing/src/styles/base.css` (particle, second-accent and frame tokens; gradient frame and typed caret; `ramp` utilities, ingestion loops and the hero's pre-frame rule; scroll-driven rules removed)
- Create: `landing/src/components/particle-field.tsx`, `landing/src/lib/illustration.ts`
- Modify: `landing/src/App.tsx` (how it works before capabilities), `landing/src/sections/hero.tsx`, `product-preview.tsx`, `footer.tsx`, `section.tsx`, `faq.tsx`, `trust-strip.tsx`, `product-highlights.tsx`
- Replace: `landing/src/sections/how-it-works.tsx` with `how-it-works/index.tsx`, `ingestion-story.tsx`, `access-gate.tsx`
- Replace: `landing/src/sections/capabilities.tsx` with `capabilities/index.tsx`, `animate-mock.ts`, `mocks/*.tsx`
- Rewrite: `landing/src/sections/deployment.tsx`, `landing/src/sections/roadmap.tsx`
- Test: `landing/src/App.test.tsx`, `landing/src/content.test.ts`

- [x] **Step 1: Add the page and copy contracts** listed under Verification in the design: no repository or notices links, named or hidden graphics including canvas, the typed statement as one sentence, section order, a heading and visible sentence per stage and capability, the gate's two lists, no inline motion styles without motion, and at most 12 words per short line.
- [x] **Step 2: Add GSAP and the motion foundation**, with the jsdom stubs.
- [x] **Step 3: Rewrite the copy** and move technical detail into the FAQ.
- [x] **Step 4: Hero and footer** — typed statement, particle fields, footer without links.
- [x] **Step 5: How it works** — the ingestion beam: six stations with line drawings driven by one `--p` per station through the `ramp` utilities, pinned and snapping on roomy desktops; quiet loops once a station finishes, while the list is on screen; then the access gate. The first boxed scene design was replaced at the product owner's request.
- [x] **Step 6: Capability explorer** — pinned list, crossfading panels and nine component-only mocks whose entrances are `data-enter` attributes (`react/only-export-components` stays clean).
- [x] **Step 7: Deployment diagram and roadmap.**
- [x] **Step 8: Keep the first interaction fast** — Lighthouse mobile fell to 0.78 (TBT 750 ms): setting up every section's motion while the page mounted laid out the whole page inside that task. `useMotion` now sets up after the first frame, one task per section; the hero hides its incoming parts with `data-intro` until then; capability mocks build on first use and grid panels play through an IntersectionObserver. Result: 0.96, TBT 130 ms, CLS 0.
- [x] **Step 9: Run the package gate** — `pnpm --dir landing check`. Expected: 5 files, 50 tests pass, build and `tsc -b` pass.
- [ ] **Step 10: Review in the Orca browser** (`orca tab`, `orca eval`, `orca screenshot`) — 390, 768, 1024 and 1440 px in both themes; every pinned and scrubbed scene in both directions; the ingestion loops; reduced motion shows the static page; no console errors. Done: see [verification](verification.md#landing-60). Remaining: a visual pass over the capabilities, deployment and roadmap scenes while scrolling up. Orca cannot emulate reduced motion, so the jsdom test covers that case.
- [ ] **Step 11: Commit in concern clusters and push the branch.** Committed as `48ed13e`–`1bf0000` plus the verification record; push is waiting for confirmation.

---

### Task 6: Production image and smoke contract

**Files:**
- Create: `landing/.dockerignore`, `landing/nginx.conf`, `landing/Dockerfile`, `landing/scripts/smoke-image.sh`

- [x] **Step 1: Write the smoke contract first**

`landing/scripts/smoke-image.sh`:

```bash
#!/usr/bin/env bash
# Exercise a landing image as production runs it: read-only root, no capabilities, UID 101.
set -euo pipefail

image=${1:?Usage: smoke-image.sh <image>}
port=${LANDING_SMOKE_PORT:-18090}
origin="http://127.0.0.1:$port"
csp="default-src 'self'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'none'"

fail() {
  echo "landing smoke: $*" >&2
  exit 1
}

container=$(docker run --detach --read-only --tmpfs /tmp:size=16m \
  --cap-drop ALL --security-opt no-new-privileges:true \
  --publish "127.0.0.1:$port:8080" "$image")

cleanup() {
  local status=$?
  if ((status != 0)); then
    docker logs "$container" >&2 || true
  fi
  docker rm --force "$container" >/dev/null
}
trap cleanup EXIT

for attempt in {1..20}; do
  if curl --silent --fail "$origin/healthz" >/dev/null; then
    break
  fi
  ((attempt == 20)) && fail "no answer on /healthz"
  sleep 0.5
done

status_of() {
  curl --silent --output /dev/null --write-out '%{http_code}' "$origin$1"
}

expect_header() {
  grep --quiet --ignore-case --fixed-strings "$2" <<<"$1" || fail "missing header: $2"
}

[[ $(docker inspect --format '{{.Config.User}}' "$container") == 101:101 ]] ||
  fail "image does not run as 101:101"

page_headers=$(curl --silent --show-error --fail --dump-header - --output /dev/null "$origin/")
expect_header "$page_headers" "Content-Security-Policy: $csp"
expect_header "$page_headers" "X-Content-Type-Options: nosniff"
expect_header "$page_headers" "X-Frame-Options: DENY"
expect_header "$page_headers" "Referrer-Policy: strict-origin-when-cross-origin"
expect_header "$page_headers" "Permissions-Policy: camera=()"
expect_header "$page_headers" "Cache-Control: no-cache"

page=$(curl --silent --show-error --fail "$origin/")
[[ $page =~ (/assets/[^\"]+\.js) ]] || fail "page references no hashed script"
asset_headers=$(curl --silent --show-error --fail --dump-header - --output /dev/null \
  "$origin${BASH_REMATCH[1]}")
expect_header "$asset_headers" "Cache-Control: public, max-age=31536000, immutable"

for path in /theme-init.js /robots.txt /sitemap.xml /favicon.svg /og-image.png /THIRD_PARTY_NOTICES.txt; do
  [[ $(status_of "$path") == 200 ]] || fail "$path is not served"
done
[[ $(status_of /missing) == 404 ]] || fail "unknown paths must return 404"

echo "landing smoke: $image passed"
```

Mark it executable in Git: `git add landing/scripts/smoke-image.sh` then `git update-index --chmod=+x landing/scripts/smoke-image.sh`.

- [x] **Step 2: Create `landing/.dockerignore`**

```gitignore
node_modules
dist
coverage
reports
*.local
```

- [x] **Step 3: Create `landing/nginx.conf`**

```nginx
worker_processes auto;
pid /tmp/nginx.pid;
error_log /dev/stderr warn;

events {
    worker_connections 1024;
}

http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;
    charset utf-8;
    server_tokens off;
    access_log /dev/stdout;

    sendfile on;
    tcp_nopush on;
    keepalive_timeout 65;

    gzip on;
    gzip_vary on;
    gzip_min_length 1024;
    gzip_types text/plain text/css application/javascript application/json image/svg+xml application/xml;

    client_body_temp_path /tmp/client_temp;
    proxy_temp_path /tmp/proxy_temp;
    fastcgi_temp_path /tmp/fastcgi_temp;
    uwsgi_temp_path /tmp/uwsgi_temp;
    scgi_temp_path /tmp/scgi_temp;

    map $uri $landing_cache_control {
        ~^/assets/ "public, max-age=31536000, immutable";
        /favicon.svg "public, max-age=86400";
        /og-image.png "public, max-age=86400";
        default "no-cache";
    }

    server {
        listen 8080;
        server_name _;
        root /usr/share/nginx/html;

        # HSTS is added by Nginx Proxy Manager, which terminates TLS.
        add_header Cache-Control $landing_cache_control always;
        add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'none'" always;
        add_header Permissions-Policy "camera=(), geolocation=(), microphone=(), payment=(), usb=()" always;
        add_header Referrer-Policy "strict-origin-when-cross-origin" always;
        add_header X-Content-Type-Options "nosniff" always;
        add_header X-Frame-Options "DENY" always;

        location = / {
            try_files /index.html =404;
        }

        location = /healthz {
            access_log off;
            default_type text/plain;
            return 200 "ok\n";
        }

        location / {
            try_files $uri =404;
        }
    }
}
```

- [x] **Step 4: Create `landing/Dockerfile`**

```dockerfile
# syntax=docker/dockerfile:1.7

# Build context: landing/
FROM node:24-alpine@sha256:d32cdf619f63fe0471182d08996dd516c6275bb5fd31ae06e55a570bd9e1ad43 AS build

WORKDIR /workspace/landing

RUN corepack enable \
    && corepack install --global pnpm@11.22.0

COPY package.json pnpm-lock.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile

COPY . .
RUN pnpm build

FROM nginx:1.31-alpine@sha256:db35bfc6b2951e7f8a72db5db120288c127ffaeeb4a6d4b95a26fead017d5913

ARG VCS_REF=unknown
ARG BUILD_DATE=unknown

LABEL org.opencontainers.image.title="MemoryOS Landing" \
      org.opencontainers.image.source="https://github.com/kl3inIT/MemoryOS" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"

RUN rm -f /etc/nginx/conf.d/default.conf

COPY --chown=101:101 nginx.conf /etc/nginx/nginx.conf
COPY --from=build --chown=101:101 /workspace/landing/dist /usr/share/nginx/html

USER 101:101
EXPOSE 8080
ENTRYPOINT ["nginx", "-g", "daemon off;"]
```

- [x] **Step 5: Build the image and run the smoke contract**

Run: `docker build --tag memoryos-landing:local --build-arg VCS_REF="$(git rev-parse HEAD)" --build-arg BUILD_DATE="$(git show -s --format=%cI HEAD)" landing`
Expected: build succeeds; the build stage prints `Verified N emitted WOFF2 assets`.

Run: `bash landing/scripts/smoke-image.sh memoryos-landing:local`
Expected: `landing smoke: memoryos-landing:local passed`.

Negative check (proves the script can fail): run `LANDING_SMOKE_PORT=18091 bash landing/scripts/smoke-image.sh nginx:1.31-alpine@sha256:db35bfc6b2951e7f8a72db5db120288c127ffaeeb4a6d4b95a26fead017d5913`
Expected: non-zero exit with `landing smoke:` failure output (the stock image does not listen on 8080).

- [x] **Step 6: Commit**

```bash
git add landing/.dockerignore landing/nginx.conf landing/Dockerfile landing/scripts/smoke-image.sh
git commit -m "build(landing): serve the landing page from a hardened nginx image"
```

---

### Task 7: Operator Compose definition

**Files:**
- Create: `infrastructure/deployment/compose.landing.yaml`

- [x] **Step 1: Create the Compose file**

```yaml
# Public vanda.app landing page. Operated separately from the application stack; see
# docs/runbooks/landing.md.
name: memoryos-landing

services:
  landing:
    image: ${MEMORYOS_LANDING_IMAGE:?Set MEMORYOS_LANDING_IMAGE}
    container_name: memoryos-landing
    restart: unless-stopped
    init: true
    read_only: true
    tmpfs:
      - /tmp:size=16m,mode=1777
    security_opt:
      - no-new-privileges:true
    cap_drop:
      - ALL
    networks:
      proxy:
        aliases:
          - memoryos-landing
    healthcheck:
      test:
        - CMD-SHELL
        - wget -q -O /dev/null http://127.0.0.1:8080/healthz
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 5s
    stop_grace_period: 10s
    logging:
      driver: json-file
      options:
        max-size: 10m
        max-file: "3"
    deploy:
      resources:
        limits:
          cpus: "0.25"
          memory: 64m
        reservations:
          memory: 16m

networks:
  proxy:
    name: ${MEMORYOS_PROXY_NETWORK:-proxy-network}
    external: true
```

- [x] **Step 2: Validate and exercise it locally**

Run: `MEMORYOS_LANDING_IMAGE=memoryos-landing:local docker compose --file infrastructure/deployment/compose.landing.yaml config --quiet`
Expected: exit 0.

Run: `docker network create proxy-network` (skip if it already exists), then `MEMORYOS_LANDING_IMAGE=memoryos-landing:local docker compose --file infrastructure/deployment/compose.landing.yaml up --detach --wait`
Expected: `memoryos-landing` reported Healthy.

Run: `docker exec memoryos-landing wget -q -O - http://127.0.0.1:8080/healthz`
Expected: `ok`.

Run: `MEMORYOS_LANDING_IMAGE=memoryos-landing:local docker compose --file infrastructure/deployment/compose.landing.yaml down`, then remove `proxy-network` only if Step 2 created it.

- [x] **Step 3: Commit**

```bash
git add infrastructure/deployment/compose.landing.yaml
git commit -m "build(deploy): add the landing page Compose definition"
```

---

### Task 8: CI gate and independent publication

**Files:**
- Modify: `.github/workflows/ci.yml` (`check` job validation step; new `landing` job after `frontend-image`; `gate` needs and jq keys; new `publish-landing` job at the end)

- [x] **Step 1: ShellCheck the smoke script with the workflow validation**

Replace the `Validate workflows and staging script` run block:

```yaml
      - name: Validate workflows and shell scripts
        run: |
          go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12
          shellcheck infrastructure/deployment/deploy-staging.sh landing/scripts/smoke-image.sh
```

- [x] **Step 2: Add the `landing` job after `frontend-image`**

```yaml
  landing:
    runs-on: ubuntu-24.04
    timeout-minutes: 15
    steps:
      - name: Check out repository
        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
        with:
          persist-credentials: false

      - name: Set up Node.js 24
        uses: actions/setup-node@820762786026740c76f36085b0efc47a31fe5020 # v7
        with:
          node-version: '24'

      - name: Enable pnpm 11.22.0
        run: |
          corepack enable
          corepack install --global pnpm@11.22.0

      - name: Install landing dependencies
        run: pnpm --dir landing install --frozen-lockfile

      - name: Verify landing page
        run: pnpm --dir landing check

      - name: Build and exercise the landing image
        run: |
          docker build --tag "memoryos-landing:sha-$GITHUB_SHA" \
            --build-arg "VCS_REF=$GITHUB_SHA" \
            --build-arg BUILD_DATE="$(git show -s --format=%cI HEAD)" landing
          bash landing/scripts/smoke-image.sh "memoryos-landing:sha-$GITHUB_SHA"
          MEMORYOS_LANDING_IMAGE="memoryos-landing:sha-$GITHUB_SHA" \
            docker compose --file infrastructure/deployment/compose.landing.yaml config --quiet
          if [[ "$GITHUB_EVENT_NAME" == push ]]; then
            mkdir -p candidate
            docker save --output candidate/landing.tar "memoryos-landing:sha-$GITHUB_SHA"
          fi

      - name: Upload landing test reports
        if: ${{ !cancelled() }}
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7
        with:
          name: landing-tests
          path: landing/reports/
          if-no-files-found: warn
          retention-days: 7

      # Named outside candidate-* so the application publication never downloads it.
      - name: Preserve verified landing image
        if: ${{ github.event_name == 'push' && github.ref == 'refs/heads/main' }}
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7
        with:
          name: landing-image
          path: candidate/landing.tar
          if-no-files-found: error
          compression-level: 1
          retention-days: 7
```

- [x] **Step 3: Require it in `CI Gate`**

```yaml
    needs: [check, frontend, frontend-image, backend-images, landing, secrets]
```

```yaml
          jq --exit-status '
            keys == ["backend-images", "check", "frontend", "frontend-image", "landing", "secrets"]
            and all(.[]; .result == "success")
          ' <<< "$RESULTS"
```

- [x] **Step 4: Append the `publish-landing` job**

```yaml
  publish-landing:
    name: Publish landing
    if: ${{ github.event_name == 'push' && github.ref == 'refs/heads/main' }}
    needs: gate
    runs-on: ubuntu-24.04
    timeout-minutes: 10
    permissions:
      contents: read # Required by the workflow token; no repository content is read.
      packages: write # Publish the preserved landing image to GHCR after CI Gate succeeds.
    steps:
      - uses: actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c # v8
        with:
          name: landing-image
          path: candidate
      - name: Authenticate to GHCR for this job
        env:
          REGISTRY_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: printf '%s' "$REGISTRY_TOKEN" | docker login ghcr.io --username "$GITHUB_ACTOR" --password-stdin
      - name: Publish the preserved landing image and record its digest
        run: |
          docker load --input candidate/landing.tar
          image="memoryos-landing:sha-$GITHUB_SHA"
          docker image inspect "$image" | jq --exit-status --arg sha "$GITHUB_SHA" '
            .[0].Config.Labels | .["org.opencontainers.image.revision"] == $sha
            and .["org.opencontainers.image.source"] == "https://github.com/kl3inIT/MemoryOS"
          '
          repository="ghcr.io/kl3init/memoryos-landing"
          tag="$repository:sha-$GITHUB_SHA-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
          docker tag "$image" "$tag"
          docker push "$tag"
          digest=$(docker image inspect "$tag" | jq --exit-status --raw-output --arg repo "$repository@" '
            [.[0].RepoDigests[] | select(startswith($repo))] | if length == 1 then .[0] else error("Ambiguous digest") end
          ')
          [[ "$digest" =~ ^ghcr\.io/kl3init/memoryos-landing@sha256:[0-9a-f]{64}$ ]]
          mkdir landing-release
          printf 'MEMORYOS_LANDING_IMAGE=%s\n' "$digest" > landing-release/landing.env
          # shellcheck disable=SC2016 # The backticks are Markdown code formatting, not a substitution.
          printf '### Landing image\n\n`%s`\n' "$digest" >> "$GITHUB_STEP_SUMMARY"
      - name: Preserve the landing release reference
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7
        with:
          name: landing-release-${{ github.sha }}-${{ github.run_attempt }}
          path: landing-release/landing.env
          if-no-files-found: error
          retention-days: 90
      - name: Remove registry credentials
        if: ${{ always() }}
        run: docker logout ghcr.io
```

- [x] **Step 5: Lint the workflow and script locally**

Run: `docker run --rm -v "$(cygpath -w "$PWD"):/repo" -w /repo rhysd/actionlint:1.7.12 -color`
Expected: no findings.

Run: `docker run --rm -v "$(cygpath -w "$PWD"):/mnt" -w /mnt koalaman/shellcheck:stable infrastructure/deployment/deploy-staging.sh landing/scripts/smoke-image.sh`
Expected: no findings.

- [x] **Step 6: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: gate and publish the landing image independently"
```

---

### Task 9: Runbook, browser verification and evidence

**Files:**
- Create: `docs/runbooks/landing.md`, `docs/increments/active/mem-82-landing-page/verification.md`

- [x] **Step 1: Create `docs/runbooks/landing.md`**

````markdown
# Landing page delivery

The public site at `https://vanda.app` is the static [`landing/`](../../landing) package. CI verifies it, builds one nginx image and, on main, publishes that image by digest. An operator deploys it on the staging VPS as the separate Compose project `memoryos-landing` behind Nginx Proxy Manager. The application deployment script never starts, stops or rolls it back. Design and decisions: [MEM-82](../increments/active/mem-82-landing-page/design.md).

## Release identity

`CI Gate` requires the `landing` job: lint, format, component and metadata tests, the production build with its font-asset assertion, TypeScript, the image build, [`smoke-image.sh`](../../landing/scripts/smoke-image.sh) and Compose validation. After the gate succeeds on main, `Publish landing` pushes the preserved image as `ghcr.io/kl3init/memoryos-landing:sha-<sha>-<run>-<attempt>` and records its digest in the job summary and in the `landing-release-<sha>-<attempt>` artifact (`landing.env`). Deploy only such a digest; there is no mutable tag.

## Server layout

| Path | Content |
| --- | --- |
| `/apps/memoryos-landing/compose.landing.yaml` | `infrastructure/deployment/compose.landing.yaml` from the released revision |
| `/apps/memoryos-landing/landing.env` | `MEMORYOS_LANDING_IMAGE=<digest reference>` from the release artifact |
| `/apps/memoryos-landing/landing.env.previous` | The last accepted reference, kept for rollback |

Run the commands below on the VPS from `/apps/memoryos-landing`.

## Deploy or update the container

1. Keep the accepted reference: `cp landing.env landing.env.previous` (skip on the first deployment).
2. Write `landing.env` from the release artifact and copy `compose.landing.yaml` from the same revision.
3. Pull with a temporary GHCR credential that can read packages, remove the credential, then start:

   ```sh
   docker login ghcr.io --username <github-user>   # read:packages token, entered at the prompt
   docker compose --env-file landing.env --file compose.landing.yaml pull
   docker logout ghcr.io
   docker compose --env-file landing.env --file compose.landing.yaml up --detach --wait
   ```

4. Check the served headers from inside the container:

   ```sh
   docker exec memoryos-landing wget -q -S -O /dev/null http://127.0.0.1:8080/
   ```

## First publication of vanda.app

Before changing anything, record the current Cloudflare state for `vanda.app`: an export of the DNS records, the redirect rule to `roll-bits.com`, and the output of `nslookup -type=mx vanda.app`. Replacing the redirect was approved by the owner on 2026-09-11.

1. Deploy the container as above.
2. In Cloudflare for `vanda.app`:
   - Delete the rule that redirects `vanda.app` to `roll-bits.com`.
   - Set `vanda.app` `A` to the staging VPS public IPv4 `72.62.193.33`, proxy status **DNS only**.
   - Set `www.vanda.app` `A` to the same address, **DNS only**.
   - Leave MX, SPF/DKIM/DMARC TXT and verification records unchanged.
3. Wait until `nslookup vanda.app 1.1.1.1` and `nslookup www.vanda.app 1.1.1.1` return the VPS address.
4. In Nginx Proxy Manager:
   - Proxy host `vanda.app` → `http://memoryos-landing:8080`, Block Common Exploits on, WebSockets off. SSL: new Let's Encrypt certificate, Force SSL, HTTP/2, HSTS on without subdomains.
   - Redirection host `www.vanda.app` → `https://vanda.app`, HTTP 301, preserve path, its own Let's Encrypt certificate with Force SSL.
5. Verify from outside the server:

   ```sh
   curl -sSI https://vanda.app/                                          # 200
   curl -sSI https://www.vanda.app/                                      # 301, Location: https://vanda.app/
   curl -sS -o /dev/null -w '%{http_code}\n' https://vanda.app/missing   # 404
   nslookup -type=mx vanda.app                                           # equals the recorded MX set
   ```

   The 200 response carries `Strict-Transport-Security`, the Content Security Policy, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy` and `Permissions-Policy`.
6. Run Lighthouse (mobile) against `https://vanda.app/`. Record the scores, date and deployed digest in MEM-82.

## Roll back

Container: restore the previous reference and start it. Pull it first with a temporary credential if the image is no longer on the server.

```sh
cp landing.env.previous landing.env
docker compose --env-file landing.env --file compose.landing.yaml up --detach --wait
```

Publication: restore the Cloudflare records and redirect rule recorded before step 2. The Nginx Proxy Manager hosts can stay; without DNS they receive no traffic.

## Boundaries

- The site shares the staging VPS; its availability follows that server and Nginx Proxy Manager.
- The container publishes no host port; Nginx Proxy Manager reaches it over `proxy-network`.
- Never add `compose.landing.yaml` to the application deployment or its image to `images.env`.
````

- [x] **Step 2: Verify the served page in a browser**

Run the image as production does: `docker run --detach --name landing-review --read-only --tmpfs /tmp:size=16m --cap-drop ALL --security-opt no-new-privileges:true --publish 127.0.0.1:18092:8080 memoryos-landing:local`
With Chrome DevTools MCP, open `http://127.0.0.1:18092/`: no console errors (a CSP violation would appear there), screenshots at 390, 768 and 1440 px, keyboard pass through header, skip link, FAQ and footer. With Chrome DevTools MCP `lighthouse_audit` (mobile): record Performance, Accessibility, Best Practices and SEO; each must be ≥ 90, otherwise fix and repeat from Task 5 Step 10. Remove the container: `docker rm --force landing-review`.

- [x] **Step 3: Create `verification.md` with the observed evidence**

Record, with date and commit: `pnpm --dir landing check` counts; the smoke script result and the negative check; Compose validation and health; actionlint/ShellCheck; the four Lighthouse scores and viewport findings; and the gates still pending: CI on the pull request, the first `Publish landing` digest, the operator deployment, the deployed checks from the runbook, and Laura's content review. Do not mark a pending gate as passed.

- [x] **Step 4: Commit**

```bash
git add docs/runbooks/landing.md docs/increments/active/mem-82-landing-page/verification.md
git commit -m "docs(landing): add delivery runbook and verification record"
```

---

### Task 10: Consolidate durable documentation

**Files:**
- Modify: `ARCHITECTURE.md` (Deployment), `docs/tests/delivery.md`, `docs/runbooks/ci-cd.md`, `README.md`, `docs/increments/active/mem-82-landing-page/plan.md` (status)

- [x] **Step 1: `ARCHITECTURE.md` — append to the Deployment section, after the staging-origin paragraph**

```markdown
The public company site `https://vanda.app` is the separate static [`landing/`](landing) package, not part of the application. Its nginx image serves a client-rendered page read-only as UID 101 with a strict same-origin CSP; `CI Gate` requires its checks and image smoke, and a separate `Publish landing` job records its digest. Operators run it as Compose project `memoryos-landing` from [`compose.landing.yaml`](infrastructure/deployment/compose.landing.yaml) behind Nginx Proxy Manager, outside the application release bundle and deployment script. See the [landing runbook](docs/runbooks/landing.md).
```

- [x] **Step 2: `docs/tests/delivery.md` — add two rows after "Web base-image upgrades preserve the serving boundary"**

```markdown
| The public landing image keeps its serving boundary | `landing/scripts/smoke-image.sh` in the `landing` CI job: read-only, capability-free UID 101 container; `/`, `/healthz`, public files, 404 for unknown paths, CSP and security headers, immutable asset caching | Local container only; DNS, TLS, HSTS and the `www` redirect are checked on the deployed host per the [landing runbook](../runbooks/landing.md) |
| The landing release stays independent of the application release | `landing-image` artifact outside `candidate-*`; `Publish landing` after `CI Gate`; the application `images.env` keeps three images | Deployment is a manual operator step; the application script never manages `memoryos-landing` |
```

- [x] **Step 3: `docs/runbooks/ci-cd.md` — update the gate and publication statements**

Replace the first sentence of "Required verification and release identity" with:

```markdown
`CI Gate` requires successful backend/infrastructure checks, frontend checks/browser fixtures, all three production image builds, the landing page checks and image smoke, and a redacted Gitleaks history scan.
```

Append to the end of that section:

```markdown
The public landing page is released separately: `Publish landing` pushes its preserved image after the same gate and records the digest in its own `landing-release-<sha>-<attempt>` artifact. It is never part of `images.env`; operators deploy it with the [landing runbook](landing.md).
```

- [x] **Step 4: `README.md` — requirements and verification**

Change the Node requirement line to:

```markdown
- Node.js 24 with Corepack; `web/package.json` and `landing/package.json` pin pnpm.
```

After the frontend verification block, add:

````markdown
Public landing page (`https://vanda.app`, deployed separately; see the [landing runbook](docs/runbooks/landing.md)):

```powershell
pnpm --dir landing install --frozen-lockfile
pnpm --dir landing check
```
````

- [x] **Step 5: Mark plan status and commit**

Add a `## Status` section at the top of this plan naming completed tasks and the pending external gates from `verification.md`.

```bash
git add ARCHITECTURE.md docs/tests/delivery.md docs/runbooks/ci-cd.md README.md docs/increments/active/mem-82-landing-page/plan.md
git commit -m "docs: record the landing page delivery boundary"
```

- [x] **Step 6: Report to Linear**

Comment on MEM-82 with the branch, the verification summary and the pending gates (PR CI, publication digest, operator deployment, Laura's review). Move the increment to `completed/` only after the pull request merges.

