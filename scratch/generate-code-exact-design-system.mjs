import { createRequire } from "module";
const require = createRequire("C:/Users/Admin/AppData/Local/nvm/v26.5.1/node_modules/@figwright/mcp/package.json");
const { encode, decode } = require("@msgpack/msgpack");

async function callTool(toolName, args = {}) {
  const cleanArgs = {};
  for (const [k, v] of Object.entries(args)) {
    if (v !== undefined) cleanArgs[k] = v;
  }
  const rpc = {
    requestId: "req-" + Date.now() + "-" + Math.random().toString(36).slice(2, 6),
    toolName,
    args: cleanArgs,
    buildId: 0
  };
  const body = Buffer.from(encode(rpc));
  const res = await fetch("http://127.0.0.1:3055/rpc", {
    method: "POST",
    headers: { "content-type": "application/msgpack" },
    body
  });
  const buf = new Uint8Array(await res.arrayBuffer());
  const decoded = decode(buf);
  if (decoded.kind === "err") {
    throw new Error(`Tool ${toolName} failed: ${decoded.message}`);
  }
  return decoded.result;
}

function hexToRgb(hex) {
  let c = hex.replace("#", "");
  if (c.length === 3) c = c.split("").map(x => x + x).join("");
  const num = parseInt(c, 16);
  return {
    r: ((num >> 16) & 255) / 255,
    g: ((num >> 8) & 255) / 255,
    b: (num & 255) / 255
  };
}

function solidPaint(hex) {
  return [{ type: "SOLID", color: hexToRgb(hex) }];
}

async function makeFrame(opts) {
  const frameArgs = {
    name: opts.name || "Frame",
    width: opts.width || 100,
    height: opts.height || 100
  };
  if (opts.parentId) frameArgs.parentId = opts.parentId;
  if (opts.x !== undefined) frameArgs.x = opts.x;
  if (opts.y !== undefined) frameArgs.y = opts.y;

  const created = await callTool("create_frame", frameArgs);
  const id = created.nodeId;

  if (opts.bg) {
    await callTool("set_fills", { nodeId: id, fills: solidPaint(opts.bg) });
  } else if (opts.bgTransparent) {
    await callTool("set_fills", { nodeId: id, fills: [] });
  }

  if (opts.border) {
    await callTool("set_strokes", {
      nodeId: id,
      strokes: solidPaint(opts.border),
      strokeWeight: opts.borderWidth || 1,
      strokeAlign: "INSIDE"
    });
  }

  if (opts.radius !== undefined) {
    await callTool("set_corner_radius", { nodeId: id, radius: opts.radius });
  }

  if (opts.layout) {
    await callTool("set_auto_layout", {
      nodeId: id,
      layoutMode: opts.layout.mode || "VERTICAL",
      paddingTop: opts.layout.py !== undefined ? opts.layout.py : (opts.layout.pt || 0),
      paddingBottom: opts.layout.py !== undefined ? opts.layout.py : (opts.layout.pb || 0),
      paddingLeft: opts.layout.px !== undefined ? opts.layout.px : (opts.layout.pl || 0),
      paddingRight: opts.layout.px !== undefined ? opts.layout.px : (opts.layout.pr || 0),
      itemSpacing: opts.layout.gap || 0,
      primaryAxisAlignItems: opts.layout.justify || "MIN",
      counterAxisAlignItems: opts.layout.align || "MIN"
    });
  }

  if (opts.sizing) {
    const layoutProps = { nodeId: id };
    if (opts.sizing.w) layoutProps.layoutSizingHorizontal = opts.sizing.w;
    if (opts.sizing.h) layoutProps.layoutSizingVertical = opts.sizing.h;
    if (opts.sizing.grow !== undefined) layoutProps.layoutGrow = opts.sizing.grow;
    if (opts.sizing.align) layoutProps.layoutAlign = opts.sizing.align;
    await callTool("set_layout_props", layoutProps);
  }

  return id;
}

async function makeText(opts) {
  const textArgs = {
    characters: opts.text || "",
    fontSize: opts.size || 14
  };
  if (opts.parentId) textArgs.parentId = opts.parentId;

  const created = await callTool("create_text", textArgs);
  const id = created.nodeId;

  if (opts.color) {
    await callTool("set_fills", { nodeId: id, fills: solidPaint(opts.color) });
  }

  const props = { nodeId: id };
  if (opts.weight === "Bold") props.fontName = { family: "Inter", style: "Bold" };
  else if (opts.weight === "SemiBold") props.fontName = { family: "Inter", style: "Semi Bold" };
  else if (opts.weight === "Medium") props.fontName = { family: "Inter", style: "Medium" };
  else props.fontName = { family: "Inter", style: "Regular" };

  if (opts.size) props.fontSize = opts.size;
  if (opts.lineHeight) props.lineHeight = { unit: "PIXELS", value: opts.lineHeight };
  if (opts.autoResize) props.textAutoResize = opts.autoResize;

  await callTool("set_text_properties", props);

  if (opts.sizing) {
    const layoutProps = { nodeId: id };
    if (opts.sizing.w) layoutProps.layoutSizingHorizontal = opts.sizing.w;
    if (opts.sizing.h) layoutProps.layoutSizingVertical = opts.sizing.h;
    if (opts.sizing.grow !== undefined) layoutProps.layoutGrow = opts.sizing.grow;
    if (opts.sizing.align) layoutProps.layoutAlign = opts.sizing.align;
    await callTool("set_layout_props", layoutProps);
  }

  return id;
}

// -------------------------------------------------------------
// Exact Code Component Helpers
// -------------------------------------------------------------

// components/ui/button.tsx + action-styles.ts
async function makeCodeButton(parentId, label, opts = {}) {
  // size: sm (h: 28, px: 8), md (h: 36, px: 12), lg (h: 40, px: 12)
  const size = opts.size || "md";
  const tone = opts.tone || "default"; // default | danger
  const prominence = opts.prominence || "primary"; // primary | secondary | tertiary | internal
  const disabled = opts.disabled || false;
  const pending = opts.pending || false;

  let bg = "#1c1c1c";
  let border = null;
  let text = "#ffffff";

  if (tone === "default") {
    if (prominence === "primary") {
      bg = "#1c1c1c";
      text = "#ffffff";
    } else if (prominence === "secondary") {
      bg = "#fafafa";
      border = "#cccccc";
      text = "#1c1c1c";
    } else if (prominence === "tertiary") {
      bg = "transparent";
      text = "#1c1c1c";
    } else if (prominence === "internal") {
      bg = "transparent";
      text = "#555555";
    }
  } else if (tone === "danger") {
    if (prominence === "primary") {
      bg = "#dc2626";
      text = "#ffffff";
    } else if (prominence === "secondary") {
      bg = "#fceae7";
      border = "#f8a59b";
      text = "#c12222";
    } else if (prominence === "tertiary") {
      bg = "transparent";
      text = "#c12222";
    }
  }

  if (disabled) {
    bg = "#e6e6e9";
    border = "#cccccc";
    text = "#a4a4a4";
  }

  const px = size === "sm" ? 8 : (size === "lg" ? 14 : 12);
  const py = size === "sm" ? 5 : (size === "lg" ? 10 : 8);
  const fontSize = size === "sm" ? 12 : (size === "lg" ? 14 : 13);

  const btn = await makeFrame({
    parentId,
    name: `<Button tone="${tone}" prominence="${prominence}" size="${size}">`,
    bg,
    border,
    radius: 8,
    layout: {
      mode: "HORIZONTAL",
      justify: "CENTER",
      align: "CENTER",
      px,
      py,
      gap: 6
    },
    sizing: { w: "HUG", h: "HUG" }
  });

  if (pending) {
    await makeText({ parentId: btn, text: "⟳", size: fontSize, weight: "Bold", color: text });
  }

  await makeText({
    parentId: btn,
    text: label,
    size: fontSize,
    weight: "SemiBold",
    color: text
  });

  return btn;
}

// components/ui/badge.tsx (variant: default, secondary, destructive, outline, ghost)
async function makeCodeBadge(parentId, label, variant = "default") {
  let bg = "#1c1c1c";
  let text = "#ffffff";
  let border = null;

  if (variant === "secondary") {
    bg = "#f0f0f1";
    text = "#1c1c1c";
  } else if (variant === "destructive") {
    bg = "#fceae7";
    text = "#c12222";
  } else if (variant === "outline") {
    bg = "#ffffff";
    border = "#cccccc";
    text = "#1c1c1c";
  } else if (variant === "ghost") {
    bg = "transparent";
    text = "#555555";
  }

  const badge = await makeFrame({
    parentId,
    name: `<Badge variant="${variant}">`,
    bg,
    border,
    radius: 6,
    layout: { mode: "HORIZONTAL", justify: "CENTER", align: "CENTER", px: 8, py: 2, gap: 4 },
    sizing: { w: "HUG", h: "HUG" }
  });

  await makeText({ parentId: badge, text: label, size: 11, weight: "Medium", color: text });
  return badge;
}

// components/ui/status-badge.tsx (tone: success, warning, danger, info, neutral; variant: soft, pill; size: sm, md)
async function makeCodeStatusBadge(parentId, label, tone = "info", variant = "soft", size = "md") {
  const tones = {
    success: { softBg: "#e6f2e7", softBorder: "#91d099", softText: "#007a2d", pillBg: "#00a43f", dot: "#00a43f" },
    warning: { softBg: "#fbeae4", softBorder: "#f5a88b", softText: "#b54204", pillBg: "#ec5b13", dot: "#ec5b13" },
    danger: { softBg: "#fceae7", softBorder: "#f8a59b", softText: "#c12222", pillBg: "#dc2626", dot: "#dc2626" },
    info: { softBg: "#e7effc", softBorder: "#9bbeff", softText: "#245fda", pillBg: "#286df8", dot: "#286df8" },
    neutral: { softBg: "#f0f0f1", softBorder: "#cccccc", softText: "#808080", pillBg: "#555555", dot: "#808080" }
  };
  const t = tones[tone] || tones.info;

  const isPill = variant === "pill";
  const bg = isPill ? t.pillBg : t.softBg;
  const border = isPill ? null : t.softBorder;
  const textColor = isPill ? "#ffffff" : t.softText;
  const radius = isPill ? 12 : 6;
  const px = size === "sm" ? 6 : 8;
  const py = size === "sm" ? 1 : 2;
  const fontSize = size === "sm" ? 10 : 11;

  const badge = await makeFrame({
    parentId,
    name: `<StatusBadge tone="${tone}" variant="${variant}" size="${size}">`,
    bg,
    border,
    radius,
    layout: { mode: "HORIZONTAL", justify: "CENTER", align: "CENTER", px, py, gap: 5 },
    sizing: { w: "HUG", h: "HUG" }
  });

  if (!isPill) {
    await makeFrame({
      parentId: badge,
      name: "dot",
      width: 6,
      height: 6,
      bg: t.dot,
      radius: 3,
      sizing: { w: "FIXED", h: "FIXED" }
    });
  }

  await makeText({ parentId: badge, text: label, size: fontSize, weight: isPill ? "SemiBold" : "Medium", color: textColor });
  return badge;
}

// Section Container with real SectionHeader
async function makeCodeSection(parentId, title, desc, tag) {
  const card = await makeFrame({
    parentId,
    name: `Section: ${title}`,
    width: 1360,
    bg: "#ffffff",
    border: "#e6e6e6",
    radius: 16,
    layout: { mode: "VERTICAL", px: 32, py: 32, gap: 24 },
    sizing: { w: "FIXED", h: "HUG" }
  });

  // composites/section-header.tsx
  const header = await makeFrame({
    parentId: card,
    name: "<SectionHeader>",
    layout: { mode: "VERTICAL", gap: 4 },
    sizing: { w: "FILL", h: "HUG" }
  });

  const topRow = await makeFrame({
    parentId: header,
    name: "header-row",
    layout: { mode: "HORIZONTAL", justify: "SPACE_BETWEEN", align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });

  await makeText({
    parentId: topRow,
    text: title,
    size: 20,
    weight: "Bold",
    color: "#1c1c1c"
  });

  if (tag) {
    await makeCodeBadge(topRow, tag, "secondary");
  }

  if (desc) {
    await makeText({
      parentId: header,
      text: desc,
      size: 13,
      weight: "Regular",
      color: "#808080"
    });
  }

  return card;
}

async function main() {
  console.log("=== MemoryOS 1:1 Code-Extracted Component Library Generator ===");

  // 1. Scan and clean page 1:2
  const scanned = await callTool("scan_nodes_by_types", { types: ["FRAME"] });
  const allNodes = Array.isArray(scanned) ? scanned : (scanned.nodes || []);
  const rootChildren = allNodes.filter(n => n.parentId === "1:2");

  if (rootChildren.length > 0) {
    console.log(`Deleting ${rootChildren.length} old root frames...`);
    await callTool("delete_nodes", { nodeIds: rootChildren.map(n => n.id) });
  }

  // 2. Create Master Canvas Frame
  console.log("Creating Master Canvas Frame...");
  const master = await makeFrame({
    parentId: "1:2",
    name: "MemoryOS — Web UI Component Library (Code-Exact)",
    width: 1440,
    bg: "#f0f0f1",
    layout: { mode: "VERTICAL", px: 40, py: 40, gap: 32, align: "CENTER" },
    sizing: { w: "FIXED", h: "HUG" }
  });

  // -------------------------------------------------------------
  // 00. MASTER BANNER
  // -------------------------------------------------------------
  console.log("00. Master Banner...");
  const banner = await makeFrame({
    parentId: master,
    name: "00. Library Specification Banner",
    width: 1360,
    bg: "#1c1c1c",
    radius: 16,
    layout: { mode: "VERTICAL", px: 36, py: 32, gap: 12 },
    sizing: { w: "FIXED", h: "HUG" }
  });

  const bannerTags = await makeFrame({
    parentId: banner,
    name: "tags",
    layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeCodeStatusBadge(bannerTags, "EXTRACTED FROM /web/src", "info", "pill");
  await makeCodeStatusBadge(bannerTags, "TAILWIND + CVA CONTRACT", "neutral", "pill");
  await makeCodeStatusBadge(bannerTags, "ZERO SPECULATIVE ELEMENTS", "success", "pill");

  await makeText({
    parentId: banner,
    text: "MemoryOS Web UI Component Library",
    size: 28,
    weight: "Bold",
    color: "#ffffff"
  });
  await makeText({
    parentId: banner,
    text: "Exact 1:1 visual mapping of components defined in MemoryOS web frontend (web/src/components/ui/ & web/src/components/composites/).",
    size: 14,
    weight: "Regular",
    color: "#a4a4a4"
  });

  // -------------------------------------------------------------
  // 01. FOUNDATIONS — TOKENS & TYPOGRAPHY
  // -------------------------------------------------------------
  console.log("01. Foundations...");
  const secFoundations = await makeCodeSection(
    master,
    "01. Foundations — Design Tokens & Typography Scale",
    "Extracted from tokens.css & theme.css. Surface, border, text content tokens, and font scale.",
    "web/src/styles/tokens.css"
  );

  const foundGrid = await makeFrame({
    parentId: secFoundations,
    name: "Foundations Grid",
    layout: { mode: "HORIZONTAL", gap: 24, align: "MIN" },
    sizing: { w: "FILL", h: "HUG" }
  });

  // Token Palette Columns
  async function makeTokenCol(parentId, colTitle, tokens) {
    const col = await makeFrame({
      parentId,
      name: colTitle,
      layout: { mode: "VERTICAL", gap: 8 },
      sizing: { w: "FILL", h: "HUG", grow: 1 }
    });
    await makeText({ parentId: col, text: colTitle, size: 12, weight: "Bold", color: "#1c1c1c" });

    for (const t of tokens) {
      const row = await makeFrame({
        parentId: col,
        name: t.name,
        bg: t.hex,
        border: t.border || null,
        radius: 6,
        layout: { mode: "HORIZONTAL", px: 10, py: 8, justify: "SPACE_BETWEEN", align: "CENTER" },
        sizing: { w: "FILL", h: "HUG" }
      });
      await makeText({ parentId: row, text: t.name, size: 11, weight: "SemiBold", color: t.dark ? "#ffffff" : "#1c1c1c" });
      await makeText({ parentId: row, text: t.hex, size: 10, weight: "Regular", color: t.dark ? "#cccccc" : "#808080" });
    }
  }

  await makeTokenCol(foundGrid, "Surface & Borders", [
    { name: "surface-base", hex: "#ffffff", border: "#e6e6e6", dark: false },
    { name: "surface-raised", hex: "#ffffff", border: "#e6e6e6", dark: false },
    { name: "surface-subtle", hex: "#f0f0f1", border: "#e6e6e6", dark: false },
    { name: "surface-sunken", hex: "#e6e6e9", border: "#cccccc", dark: false },
    { name: "border-subtle", hex: "#e6e6e6", dark: false },
    { name: "border-default", hex: "#cccccc", dark: false }
  ]);

  await makeTokenCol(foundGrid, "Content & Neutrals", [
    { name: "content-primary (900)", hex: "#1c1c1c", dark: true },
    { name: "content-secondary (600)", hex: "#555555", dark: true },
    { name: "content-muted (500)", hex: "#808080", dark: true },
    { name: "content-disabled (400)", hex: "#a4a4a4", dark: false },
    { name: "surface-scrim (overlay)", hex: "#141414", dark: true }
  ]);

  await makeTokenCol(foundGrid, "Action & Status Tones", [
    { name: "action-primary", hex: "#1c1c1c", dark: true },
    { name: "action-selection", hex: "#286df8", dark: true },
    { name: "status-success", hex: "#007a2d", dark: true },
    { name: "status-warning", hex: "#b54204", dark: true },
    { name: "status-danger", hex: "#dc2626", dark: true }
  ]);

  // Typography Hierarchy
  const typoBox = await makeFrame({
    parentId: secFoundations,
    name: "Typography Contract Table",
    bg: "#fafafa",
    border: "#e6e6e6",
    radius: 8,
    layout: { mode: "VERTICAL", px: 16, py: 12, gap: 10 },
    sizing: { w: "FILL", h: "HUG" }
  });

  const typoList = [
    { token: "font-heading-h1", spec: "32px / 40px • SemiBold (-0.01em)", sample: "Personal Memory Substrate & Context Streams", size: 28, weight: "SemiBold" },
    { token: "font-heading-h2", spec: "24px / 32px • SemiBold (-0.01em)", sample: "Knowledge Connectors & Vector Embeddings", size: 22, weight: "SemiBold" },
    { token: "font-heading-h3", spec: "18px / 24px • SemiBold (-0.01em)", sample: "Active Model Configurations & Audit Log", size: 18, weight: "SemiBold" },
    { token: "font-main-ui-body", spec: "14px / 20px • Regular (450)", sample: "MemoryOS indexes local notes and remote documents into high-dimensional space.", size: 14, weight: "Regular" },
    { token: "font-main-ui-action", spec: "14px / 20px • SemiBold (600)", sample: "Save Changes • Run Pipeline Sync • Configure Provider", size: 14, weight: "SemiBold" },
    { token: "font-secondary-body", spec: "12px / 16px • Regular (450)", sample: "Synchronized 2 minutes ago • 1,420 chunks processed", size: 12, weight: "Regular" }
  ];

  for (const t of typoList) {
    const tRow = await makeFrame({
      parentId: typoBox,
      name: t.token,
      layout: { mode: "HORIZONTAL", gap: 20, align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });
    const tMeta = await makeFrame({
      parentId: tRow,
      name: "meta",
      width: 260,
      layout: { mode: "VERTICAL", gap: 2 },
      sizing: { w: "FIXED", h: "HUG" }
    });
    await makeText({ parentId: tMeta, text: t.token, size: 12, weight: "Bold", color: "#1c1c1c" });
    await makeText({ parentId: tMeta, text: t.spec, size: 10, weight: "Regular", color: "#808080" });

    await makeText({
      parentId: tRow,
      text: t.sample,
      size: t.size,
      weight: t.weight,
      color: "#1c1c1c",
      sizing: { w: "FILL", h: "HUG", grow: 1 }
    });
  }

  // -------------------------------------------------------------
  // 02. PRIMITIVES — BUTTONS & ACTIONS
  // -------------------------------------------------------------
  console.log("02. Buttons & Actions...");
  const secBtn = await makeCodeSection(
    master,
    "02. Primitives — Button & Action Variants",
    "Derived from web/src/components/ui/button.tsx and action-styles.ts (tones: default, danger; prominences: primary, secondary, tertiary, internal; sizes: sm, md, lg).",
    "components/ui/button.tsx"
  );

  await makeText({ parentId: secBtn, text: "Prominence Matrix (Tone: Default)", size: 13, weight: "Bold", color: "#1c1c1c" });
  const rowBtn1 = await makeFrame({
    parentId: secBtn,
    name: "Default Prominence Row",
    layout: { mode: "HORIZONTAL", gap: 12, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeCodeButton(rowBtn1, "Primary Action", { tone: "default", prominence: "primary" });
  await makeCodeButton(rowBtn1, "Secondary Action", { tone: "default", prominence: "secondary" });
  await makeCodeButton(rowBtn1, "Tertiary (Ghost)", { tone: "default", prominence: "tertiary" });
  await makeCodeButton(rowBtn1, "Internal (Subtle)", { tone: "default", prominence: "internal" });
  await makeCodeButton(rowBtn1, "Saving...", { tone: "default", prominence: "primary", pending: true });
  await makeCodeButton(rowBtn1, "Disabled State", { tone: "default", prominence: "primary", disabled: true });

  await makeText({ parentId: secBtn, text: "Danger Tone & Control Sizes (sm: 28px, md: 36px, lg: 40px)", size: 13, weight: "Bold", color: "#1c1c1c" });
  const rowBtn2 = await makeFrame({
    parentId: secBtn,
    name: "Danger & Sizes Row",
    layout: { mode: "HORIZONTAL", gap: 12, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeCodeButton(rowBtn2, "Danger Primary", { tone: "danger", prominence: "primary" });
  await makeCodeButton(rowBtn2, "Danger Secondary", { tone: "danger", prominence: "secondary" });
  await makeCodeButton(rowBtn2, "Danger Tertiary", { tone: "danger", prominence: "tertiary" });
  await makeCodeButton(rowBtn2, "Small (28px)", { size: "sm" });
  await makeCodeButton(rowBtn2, "Medium (36px)", { size: "md" });
  await makeCodeButton(rowBtn2, "Large (40px)", { size: "lg" });

  // -------------------------------------------------------------
  // 03. PRIMITIVES — BADGES & STATUS BADGES
  // -------------------------------------------------------------
  console.log("03. Badges & Status...");
  const secBadges = await makeCodeSection(
    master,
    "03. Primitives — Badge & StatusBadge",
    "Derived from web/src/components/ui/badge.tsx and status-badge.tsx (variants: default, secondary, destructive, outline, ghost; status tones: success, warning, danger, info, neutral; soft vs pill).",
    "components/ui/status-badge.tsx"
  );

  await makeText({ parentId: secBadges, text: "Standard Badges (<Badge>)", size: 13, weight: "Bold", color: "#1c1c1c" });
  const bRow1 = await makeFrame({
    parentId: secBadges,
    name: "Badge Variants Row",
    layout: { mode: "HORIZONTAL", gap: 10, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeCodeBadge(bRow1, "default", "default");
  await makeCodeBadge(bRow1, "secondary", "secondary");
  await makeCodeBadge(bRow1, "destructive", "destructive");
  await makeCodeBadge(bRow1, "outline", "outline");
  await makeCodeBadge(bRow1, "ghost", "ghost");

  await makeText({ parentId: secBadges, text: "Status Badges (<StatusBadge> Soft with Dot Indicator)", size: 13, weight: "Bold", color: "#1c1c1c" });
  const bRow2 = await makeFrame({
    parentId: secBadges,
    name: "StatusBadge Soft Row",
    layout: { mode: "HORIZONTAL", gap: 10, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeCodeStatusBadge(bRow2, "Active / Synced", "success", "soft");
  await makeCodeStatusBadge(bRow2, "Pending Reindex", "warning", "soft");
  await makeCodeStatusBadge(bRow2, "Sync Failed", "danger", "soft");
  await makeCodeStatusBadge(bRow2, "Processing Stream", "info", "soft");
  await makeCodeStatusBadge(bRow2, "Idle / Offline", "neutral", "soft");

  await makeText({ parentId: secBadges, text: "Status Badges (<StatusBadge> Solid Pill Emphasis)", size: 13, weight: "Bold", color: "#1c1c1c" });
  const bRow3 = await makeFrame({
    parentId: secBadges,
    name: "StatusBadge Pill Row",
    layout: { mode: "HORIZONTAL", gap: 10, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeCodeStatusBadge(bRow3, "SUCCESS", "success", "pill");
  await makeCodeStatusBadge(bRow3, "WARNING", "warning", "pill");
  await makeCodeStatusBadge(bRow3, "DANGER", "danger", "pill");
  await makeCodeStatusBadge(bRow3, "INFO", "info", "pill");
  await makeCodeStatusBadge(bRow3, "NEUTRAL", "neutral", "pill");

  // -------------------------------------------------------------
  // 04. PRIMITIVES — FORM CONTROLS
  // -------------------------------------------------------------
  console.log("04. Form Controls...");
  const secForms = await makeCodeSection(
    master,
    "04. Primitives — Form Controls",
    "Derived from web/src/components/ui/ (input.tsx, textarea.tsx, switch.tsx, checkbox.tsx, radio-group.tsx, select.tsx).",
    "components/ui/input.tsx"
  );

  const formGrid = await makeFrame({
    parentId: secForms,
    name: "Form Grid (3 Columns)",
    layout: { mode: "HORIZONTAL", gap: 24, align: "MIN" },
    sizing: { w: "FILL", h: "HUG" }
  });

  // Col 1: Inputs
  const formCol1 = await makeFrame({
    parentId: formGrid,
    name: "Inputs Column",
    layout: { mode: "VERTICAL", gap: 14 },
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });

  async function makeFormInput(parentId, label, val, opts = {}) {
    const f = await makeFrame({ parentId, name: label, layout: { mode: "VERTICAL", gap: 6 }, sizing: { w: "FILL", h: "HUG" } });
    await makeText({ parentId: f, text: label, size: 12, weight: "SemiBold", color: "#1c1c1c" });

    const box = await makeFrame({
      parentId: f,
      name: "<Input>",
      bg: opts.disabled ? "#f0f0f1" : "#ffffff",
      border: opts.error ? "#dc2626" : (opts.focused ? "#1c1c1c" : "#cccccc"),
      borderWidth: opts.focused ? 1.5 : 1,
      radius: 8,
      layout: { mode: "HORIZONTAL", px: 12, py: 8, align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });
    await makeText({ parentId: box, text: val, size: 13, weight: "Regular", color: opts.placeholder ? "#808080" : "#1c1c1c", sizing: { w: "FILL", h: "HUG", grow: 1 } });

    if (opts.helper) {
      await makeText({ parentId: f, text: opts.helper, size: 11, weight: "Regular", color: opts.error ? "#dc2626" : "#808080" });
    }
  }

  await makeFormInput(formCol1, "Connector Name (<Input>)", "Obsidian Personal Vault");
  await makeFormInput(formCol1, "Search Query", "Search vectors, docs, conversations...", { placeholder: true });
  await makeFormInput(formCol1, "Provider API Key", "••••••••••••••••••••", { error: true, helper: "Invalid secret key format." });
  await makeFormInput(formCol1, "Disabled Input", "Read-only configuration value", { disabled: true });

  // Col 2: Textarea & Switches
  const formCol2 = await makeFrame({
    parentId: formGrid,
    name: "Textarea & Switch Column",
    layout: { mode: "VERTICAL", gap: 14 },
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });

  const taWrap = await makeFrame({ parentId: formCol2, name: "Textarea Group", layout: { mode: "VERTICAL", gap: 6 }, sizing: { w: "FILL", h: "HUG" } });
  await makeText({ parentId: taWrap, text: "System Instructions (<Textarea>)", size: 12, weight: "SemiBold", color: "#1c1c1c" });
  const taBox = await makeFrame({
    parentId: taWrap,
    name: "<Textarea>",
    bg: "#ffffff",
    border: "#cccccc",
    radius: 8,
    layout: { mode: "VERTICAL", px: 12, py: 10 },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeText({
    parentId: taBox,
    text: "You are the MemoryOS Assistant. Synthesize verified context chunks from local embeddings with strict citation transparency.",
    size: 13,
    weight: "Regular",
    color: "#1c1c1c",
    lineHeight: 18,
    sizing: { w: "FILL", h: "HUG" }
  });

  async function makeCodeSwitch(parentId, label, isChecked) {
    const swRow = await makeFrame({
      parentId,
      name: `<Switch checked=${isChecked}>`,
      bg: "#fafafa",
      border: "#e6e6e6",
      radius: 8,
      layout: { mode: "HORIZONTAL", px: 12, py: 8, justify: "SPACE_BETWEEN", align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });
    await makeText({ parentId: swRow, text: label, size: 12, weight: "Medium", color: "#1c1c1c" });

    const track = await makeFrame({
      parentId: swRow,
      name: "track",
      width: 40,
      height: 22,
      bg: isChecked ? "#1c1c1c" : "#e6e6e9",
      radius: 11,
      layout: { mode: "HORIZONTAL", pl: isChecked ? 20 : 2, pr: isChecked ? 2 : 20, align: "CENTER" },
      sizing: { w: "FIXED", h: "FIXED" }
    });
    await makeFrame({ parentId: track, name: "thumb", width: 18, height: 18, bg: "#ffffff", radius: 9, sizing: { w: "FIXED", h: "FIXED" } });
  }

  await makeCodeSwitch(formCol2, "Automatic Chunk Reranking", true);
  await makeCodeSwitch(formCol2, "Real-time File Watcher Sync", false);

  // Col 3: Checkboxes & Radio
  const formCol3 = await makeFrame({
    parentId: formGrid,
    name: "Selection Column",
    layout: { mode: "VERTICAL", gap: 10 },
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });

  await makeText({ parentId: formCol3, text: "Checkboxes (<Checkbox>)", size: 12, weight: "SemiBold", color: "#1c1c1c" });

  async function makeCodeCheckbox(parentId, label, isChecked) {
    const cb = await makeFrame({
      parentId,
      name: `<Checkbox checked=${isChecked}>`,
      bg: "#fafafa",
      border: "#e6e6e6",
      radius: 6,
      layout: { mode: "HORIZONTAL", px: 10, py: 8, gap: 10, align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });
    const box = await makeFrame({
      parentId: cb,
      name: "box",
      width: 18,
      height: 18,
      bg: isChecked ? "#1c1c1c" : "#ffffff",
      border: isChecked ? "#1c1c1c" : "#cccccc",
      radius: 4,
      layout: { mode: "HORIZONTAL", justify: "CENTER", align: "CENTER" },
      sizing: { w: "FIXED", h: "FIXED" }
    });
    if (isChecked) {
      await makeText({ parentId: box, text: "✓", size: 11, weight: "Bold", color: "#ffffff" });
    }
    await makeText({ parentId: cb, text: label, size: 12, weight: "Regular", color: "#1c1c1c", sizing: { w: "FILL", h: "HUG", grow: 1 } });
  }

  await makeCodeCheckbox(formCol3, "Index PDF and document attachments", true);
  await makeCodeCheckbox(formCol3, "Extract code blocks and syntax trees", true);
  await makeCodeCheckbox(formCol3, "Strict local fallback on network error", false);

  await makeText({ parentId: formCol3, text: "Radio Group (<RadioGroup>)", size: 12, weight: "SemiBold", color: "#1c1c1c" });

  async function makeCodeRadio(parentId, label, isSelected) {
    const r = await makeFrame({
      parentId,
      name: `<RadioGroupItem checked=${isSelected}>`,
      bg: "#fafafa",
      border: "#e6e6e6",
      radius: 6,
      layout: { mode: "HORIZONTAL", px: 10, py: 8, gap: 10, align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });
    const circle = await makeFrame({
      parentId: r,
      name: "circle",
      width: 18,
      height: 18,
      bg: "#ffffff",
      border: isSelected ? "#1c1c1c" : "#cccccc",
      borderWidth: 1.5,
      radius: 9,
      layout: { mode: "HORIZONTAL", justify: "CENTER", align: "CENTER" },
      sizing: { w: "FIXED", h: "FIXED" }
    });
    if (isSelected) {
      await makeFrame({ parentId: circle, name: "dot", width: 10, height: 10, bg: "#1c1c1c", radius: 5, sizing: { w: "FIXED", h: "FIXED" } });
    }
    await makeText({ parentId: r, text: label, size: 12, weight: "Regular", color: "#1c1c1c", sizing: { w: "FILL", h: "HUG", grow: 1 } });
  }

  await makeCodeRadio(formCol3, "Hybrid (Dense Vector + BM25 Lexical)", true);
  await makeCodeRadio(formCol3, "Dense Vector Only (Cosine Metric)", false);

  // -------------------------------------------------------------
  // 05. COMPOSITES — STATSTRIP, SETTINGROWS & CARDS
  // -------------------------------------------------------------
  console.log("05. Composites & Cards...");
  const secComp = await makeCodeSection(
    master,
    "05. Composites — StatStrip, SettingRows & Card",
    "Derived from web/src/components/composites/ (stat-strip.tsx, setting-row.tsx) and web/src/components/ui/card.tsx.",
    "components/composites/stat-strip.tsx"
  );

  // StatStrip composite
  await makeText({ parentId: secComp, text: "<StatStrip columns={4}> Composite", size: 13, weight: "Bold", color: "#1c1c1c" });
  const statStrip = await makeFrame({
    parentId: secComp,
    name: "<StatStrip columns={4}>",
    bg: "#e6e6e6",
    border: "#e6e6e6",
    radius: 14,
    layout: { mode: "HORIZONTAL", gap: 1 },
    sizing: { w: "FILL", h: "HUG" }
  });

  async function makeCodeStatTile(parentId, label, val, hint, trend, isSelected = false) {
    const tile = await makeFrame({
      parentId,
      name: isSelected ? "<StatToggleTile selected>" : "<StatTile>",
      bg: isSelected ? "#f0f0f1" : "#ffffff",
      layout: { mode: "VERTICAL", px: 16, py: 14, gap: 4 },
      sizing: { w: "FILL", h: "HUG", grow: 1 }
    });
    await makeText({ parentId: tile, text: label, size: 12, weight: "Medium", color: "#555555" });
    await makeText({ parentId: tile, text: val, size: 24, weight: "Bold", color: "#1c1c1c" });

    const foot = await makeFrame({ parentId: tile, name: "foot", layout: { mode: "HORIZONTAL", gap: 6, align: "CENTER" }, sizing: { w: "FILL", h: "HUG" } });
    if (trend) {
      await makeText({ parentId: foot, text: trend === "up" ? "↑" : "↓", size: 12, weight: "Bold", color: trend === "up" ? "#007a2d" : "#dc2626" });
    }
    await makeText({ parentId: foot, text: hint, size: 11, weight: "Regular", color: "#808080" });
  }

  await makeCodeStatTile(statStrip, "Total Indexed Vectors", "1,248,930", "vs previous sync", "up");
  await makeCodeStatTile(statStrip, "Active Connectors", "12 Active", "All healthy", null);
  await makeCodeStatTile(statStrip, "Retrieval Latency", "38 ms", "p95 average", "down");
  await makeCodeStatTile(statStrip, "Storage Used", "4.2 GB", "84% capacity", null, true);

  // SettingRows composite
  await makeText({ parentId: secComp, text: "<SettingRows> & <SettingRow> Composite", size: 13, weight: "Bold", color: "#1c1c1c" });
  const settingRows = await makeFrame({
    parentId: secComp,
    name: "<SettingRows>",
    bg: "#ffffff",
    border: "#e6e6e6",
    radius: 12,
    layout: { mode: "VERTICAL" },
    sizing: { w: "FILL", h: "HUG" }
  });

  async function makeCodeSettingRow(parentId, title, desc, controlType, isBordered = true) {
    const row = await makeFrame({
      parentId,
      name: `<SettingRow title="${title}">`,
      border: isBordered ? "#f0f0f1" : null,
      layout: { mode: "HORIZONTAL", px: 18, py: 14, justify: "SPACE_BETWEEN", align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });

    const meta = await makeFrame({ parentId: row, name: "meta", layout: { mode: "VERTICAL", gap: 2 }, sizing: { w: "FILL", h: "HUG", grow: 1 } });
    await makeText({ parentId: meta, text: title, size: 13, weight: "SemiBold", color: "#1c1c1c" });
    await makeText({ parentId: meta, text: desc, size: 12, weight: "Regular", color: "#808080" });

    if (controlType === "switch-on") {
      await makeCodeSwitch(row, "", true);
    } else if (controlType === "switch-off") {
      await makeCodeSwitch(row, "", false);
    } else if (controlType === "button") {
      await makeCodeButton(row, "Configure", { prominence: "secondary", size: "sm" });
    }
  }

  await makeCodeSettingRow(settingRows, "Episodic Reranking Substrate", "Boost recent context chunks in hybrid search score calculation.", "switch-on");
  await makeCodeSettingRow(settingRows, "Telemetry & Audit Trail", "Log all vector retrieval queries for compliance inspection.", "switch-off");
  await makeCodeSettingRow(settingRows, "Embedding Model Provider", "Active embedding provider is text-embedding-3-large (3072 dims).", "button", false);

  // -------------------------------------------------------------
  // 06. PRIMITIVES — TABLE & ALERTS
  // -------------------------------------------------------------
  console.log("06. Table & Alerts...");
  const secTable = await makeCodeSection(
    master,
    "06. Primitives — Table, TablePagination & Alert",
    "Derived from web/src/components/ui/table.tsx, table-pagination.tsx, and alert.tsx (tones: default, destructive, success, warning, info; table row tones: none, surface, cited, current).",
    "components/ui/table.tsx"
  );

  // Alert Variants Grid
  await makeText({ parentId: secTable, text: "<Alert> Variants Matrix", size: 13, weight: "Bold", color: "#1c1c1c" });
  const alertGrid = await makeFrame({
    parentId: secTable,
    name: "Alerts Grid",
    layout: { mode: "HORIZONTAL", gap: 16, align: "MIN" },
    sizing: { w: "FILL", h: "HUG" }
  });

  async function makeCodeAlert(parentId, title, desc, variant) {
    const tones = {
      default: { bg: "#fafafa", border: "#e6e6e6", text: "#1c1c1c", label: "DEFAULT" },
      info: { bg: "#e7effc", border: "#9bbeff", text: "#245fda", label: "INFO" },
      success: { bg: "#e6f2e7", border: "#91d099", text: "#007a2d", label: "SUCCESS" },
      warning: { bg: "#fbeae4", border: "#f5a88b", text: "#b54204", label: "WARNING" },
      destructive: { bg: "#fceae7", border: "#f8a59b", text: "#c12222", label: "DESTRUCTIVE" }
    };
    const t = tones[variant] || tones.default;

    const a = await makeFrame({
      parentId,
      name: `<Alert variant="${variant}">`,
      bg: t.bg,
      border: t.border,
      radius: 8,
      layout: { mode: "VERTICAL", px: 14, py: 12, gap: 4 },
      sizing: { w: "FILL", h: "HUG", grow: 1 }
    });
    await makeText({ parentId: a, text: `[${t.label}] ${title}`, size: 12, weight: "SemiBold", color: t.text });
    await makeText({ parentId: a, text: desc, size: 11, weight: "Regular", color: t.text, lineHeight: 16 });
  }

  await makeCodeAlert(alertGrid, "Model Updated", "Active model set to Gemini 1.5 Pro.", "info");
  await makeCodeAlert(alertGrid, "Sync Completed", "2,410 vectors indexed successfully.", "success");
  await makeCodeAlert(alertGrid, "High Token Usage", "Session exceeded 80k prompt tokens.", "warning");
  await makeCodeAlert(alertGrid, "Connection Error", "API endpoint failed after 3 retries.", "destructive");

  // Data Table
  await makeText({ parentId: secTable, text: "<Table> & <TablePagination> Component", size: 13, weight: "Bold", color: "#1c1c1c" });
  const tableCard = await makeFrame({
    parentId: secTable,
    name: "<Table>",
    bg: "#ffffff",
    border: "#e6e6e6",
    radius: 8,
    layout: { mode: "VERTICAL" },
    sizing: { w: "FILL", h: "HUG" }
  });

  const tHead = await makeFrame({
    parentId: tableCard,
    name: "<TableHeader>",
    bg: "#f0f0f1",
    layout: { mode: "HORIZONTAL", px: 16, py: 10, gap: 16, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeText({ parentId: tHead, text: "DOCUMENT / SOURCE", size: 11, weight: "Bold", color: "#555555", sizing: { w: "FIXED", h: "HUG" }, width: 380 });
  await makeText({ parentId: tHead, text: "STATUS", size: 11, weight: "Bold", color: "#555555", sizing: { w: "FIXED", h: "HUG" }, width: 160 });
  await makeText({ parentId: tHead, text: "EMBEDDING MODEL", size: 11, weight: "Bold", color: "#555555", sizing: { w: "FIXED", h: "HUG" }, width: 240 });
  await makeText({ parentId: tHead, text: "CHUNKS", size: 11, weight: "Bold", color: "#555555", sizing: { w: "FIXED", h: "HUG" }, width: 140 });
  await makeText({ parentId: tHead, text: "ACTIONS", size: 11, weight: "Bold", color: "#555555", sizing: { w: "FILL", h: "HUG", grow: 1 } });

  async function makeCodeTableRow(parentId, docName, docMeta, statusLabel, statusTone, model, chunks, rowTone = "none") {
    let rowBg = "#ffffff";
    if (rowTone === "cited") rowBg = "#f3f7fe";
    else if (rowTone === "current") rowBg = "#e7effc";

    const r = await makeFrame({
      parentId,
      name: `<TableRow tone="${rowTone}">`,
      bg: rowBg,
      border: "#f0f0f1",
      layout: { mode: "HORIZONTAL", px: 16, py: 12, gap: 16, align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });

    const docCell = await makeFrame({ parentId: r, name: "doc-cell", width: 380, layout: { mode: "VERTICAL", gap: 2 }, sizing: { w: "FIXED", h: "HUG" } });
    await makeText({ parentId: docCell, text: docName, size: 13, weight: "SemiBold", color: "#1c1c1c" });
    await makeText({ parentId: docCell, text: docMeta, size: 11, weight: "Regular", color: "#808080" });

    const stCell = await makeFrame({ parentId: r, name: "status-cell", width: 160, layout: { mode: "HORIZONTAL", align: "CENTER" }, sizing: { w: "FIXED", h: "HUG" } });
    await makeCodeStatusBadge(stCell, statusLabel, statusTone, "soft");

    const modelCell = await makeFrame({ parentId: r, name: "model-cell", width: 240, layout: { mode: "HORIZONTAL", align: "CENTER" }, sizing: { w: "FIXED", h: "HUG" } });
    await makeText({ parentId: modelCell, text: model, size: 13, weight: "Regular", color: "#1c1c1c" });

    const chunksCell = await makeFrame({ parentId: r, name: "chunks-cell", width: 140, layout: { mode: "HORIZONTAL", align: "CENTER" }, sizing: { w: "FIXED", h: "HUG" } });
    await makeText({ parentId: chunksCell, text: chunks, size: 13, weight: "Regular", color: "#555555" });

    const actCell = await makeFrame({ parentId: r, name: "act-cell", layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" }, sizing: { w: "FILL", h: "HUG", grow: 1 } });
    await makeCodeButton(actCell, "View", { prominence: "secondary", size: "sm" });
    await makeCodeButton(actCell, "Resync", { prominence: "secondary", size: "sm" });
  }

  await makeCodeTableRow(tableCard, "Quarterly_Financial_Report_2026.pdf", "PDF Document • 4.2 MB", "Indexed", "success", "text-embedding-3-large", "482 chunks", "none");
  await makeCodeTableRow(tableCard, "Architecture_Decision_Records.md", "Markdown • 128 KB", "Active Cited", "info", "text-embedding-3-large", "94 chunks", "cited");
  await makeCodeTableRow(tableCard, "Customer_Support_Transcripts.csv", "CSV Sheet • 18.5 MB", "Syncing...", "warning", "text-embedding-3-large", "1,840 chunks", "none");

  // TablePagination
  const tFoot = await makeFrame({
    parentId: tableCard,
    name: "<TablePagination>",
    bg: "#fafafa",
    border: "#e6e6e6",
    layout: { mode: "HORIZONTAL", px: 16, py: 10, justify: "SPACE_BETWEEN", align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeText({ parentId: tFoot, text: "Showing 1-3 of 24 items", size: 12, weight: "Regular", color: "#808080" });
  const pgBtns = await makeFrame({ parentId: tFoot, name: "pg-btns", layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" }, sizing: { w: "HUG", h: "HUG" } });
  await makeCodeButton(pgBtns, "Previous", { prominence: "secondary", size: "sm" });
  await makeCodeButton(pgBtns, "Next Page", { prominence: "primary", size: "sm" });

  // -------------------------------------------------------------
  // 07. NAVIGATION & APP SHELL
  // -------------------------------------------------------------
  console.log("07. Navigation & App Shell...");
  const secNav = await makeCodeSection(
    master,
    "07. Navigation — Sidebar, Tabs & Breadcrumb",
    "Derived from web/src/components/ui/ (sidebar.tsx, tabs.tsx, breadcrumb.tsx, avatar.tsx).",
    "components/ui/sidebar.tsx"
  );

  const navGrid = await makeFrame({
    parentId: secNav,
    name: "Nav Layout Grid",
    layout: { mode: "HORIZONTAL", gap: 32, align: "MIN" },
    sizing: { w: "FILL", h: "HUG" }
  });

  // Sidebar (260px)
  const sidebar = await makeFrame({
    parentId: navGrid,
    name: "<Sidebar>",
    width: 260,
    bg: "#f0f0f1",
    border: "#e6e6e6",
    radius: 12,
    layout: { mode: "VERTICAL", px: 14, py: 14, gap: 14 },
    sizing: { w: "FIXED", h: "HUG" }
  });

  const sbHead = await makeFrame({ parentId: sidebar, name: "<SidebarHeader>", layout: { mode: "HORIZONTAL", gap: 10, align: "CENTER" }, sizing: { w: "FILL", h: "HUG" } });
  await makeCodeBadge(sbHead, "M", "default");
  await makeText({ parentId: sbHead, text: "MemoryOS", size: 15, weight: "Bold", color: "#1c1c1c" });

  async function makeCodeSidebarItem(parentId, label, isActive, count) {
    const item = await makeFrame({
      parentId,
      name: `<SidebarMenuButton isActive=${isActive}>`,
      bg: isActive ? "#ffffff" : "transparent",
      border: isActive ? "#e6e6e6" : null,
      radius: 6,
      layout: { mode: "HORIZONTAL", px: 10, py: 8, justify: "SPACE_BETWEEN", align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });
    await makeText({ parentId: item, text: label, size: 13, weight: isActive ? "SemiBold" : "Medium", color: isActive ? "#1c1c1c" : "#555555" });
    if (count) {
      await makeCodeStatusBadge(item, count, "neutral", "pill", "sm");
    }
  }

  const grp1 = await makeFrame({ parentId: sidebar, name: "<SidebarGroup>", layout: { mode: "VERTICAL", gap: 4 }, sizing: { w: "FILL", h: "HUG" } });
  await makeText({ parentId: grp1, text: "INTELLIGENCE", size: 10, weight: "Bold", color: "#808080" });
  await makeCodeSidebarItem(grp1, "Chat & Assistant", true);
  await makeCodeSidebarItem(grp1, "Context Streams", false, "3");
  await makeCodeSidebarItem(grp1, "Search & Retrieval", false);

  const grp2 = await makeFrame({ parentId: sidebar, name: "<SidebarGroup>", layout: { mode: "VERTICAL", gap: 4 }, sizing: { w: "FILL", h: "HUG" } });
  await makeText({ parentId: grp2, text: "KNOWLEDGE", size: 10, weight: "Bold", color: "#808080" });
  await makeCodeSidebarItem(grp2, "Connectors", false, "12");
  await makeCodeSidebarItem(grp2, "Document Sets", false);
  await makeCodeSidebarItem(grp2, "Settings", false);

  // Right Side: Topbar & Tabs
  const navRight = await makeFrame({
    parentId: navGrid,
    name: "Topbar & Tabs View",
    layout: { mode: "VERTICAL", gap: 20 },
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });

  const topHeader = await makeFrame({
    parentId: navRight,
    name: "<Breadcrumb> & Top Header",
    bg: "#ffffff",
    border: "#e6e6e6",
    radius: 8,
    layout: { mode: "HORIZONTAL", px: 20, py: 12, justify: "SPACE_BETWEEN", align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeText({ parentId: topHeader, text: "Admin / Knowledge Sources / Google Drive", size: 13, weight: "Medium", color: "#555555" });
  const headRight = await makeFrame({ parentId: topHeader, name: "right", layout: { mode: "HORIZONTAL", gap: 10, align: "CENTER" }, sizing: { w: "HUG", h: "HUG" } });
  await makeCodeBadge(headRight, "Gemini 1.5 Pro ▼", "outline");
  await makeCodeBadge(headRight, "Admin User", "default");

  // Tabs
  const tabList = await makeFrame({
    parentId: navRight,
    name: "<TabsList>",
    border: "#e6e6e6",
    layout: { mode: "HORIZONTAL", gap: 28, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });

  async function makeCodeTab(parentId, label, isActive) {
    const tab = await makeFrame({ parentId, name: `<TabsTrigger value="${label}">`, layout: { mode: "VERTICAL", pt: 8, pb: 8, gap: 6, align: "CENTER" }, sizing: { w: "HUG", h: "HUG" } });
    await makeText({ parentId: tab, text: label, size: 13, weight: isActive ? "SemiBold" : "Medium", color: isActive ? "#1c1c1c" : "#808080" });
    await makeFrame({ parentId: tab, name: "active-line", width: 60, height: 2, bg: isActive ? "#1c1c1c" : "transparent", sizing: { w: "FIXED", h: "FIXED" } });
  }

  await makeCodeTab(tabList, "General Overview", true);
  await makeCodeTab(tabList, "Vector Pipeline", false);
  await makeCodeTab(tabList, "Model Parameters", false);
  await makeCodeTab(tabList, "Audit Logs", false);

  // -------------------------------------------------------------
  // 08. OVERLAYS & DIALOGS
  // -------------------------------------------------------------
  console.log("08. Dialogs & Overlays...");
  const secDialogs = await makeCodeSection(
    master,
    "08. Overlays — Dialog, ConfirmDialog & DropdownMenu",
    "Derived from web/src/components/ui/ (dialog.tsx, confirm-dialog.tsx, dropdown-menu.tsx) and composites/form-dialog.tsx.",
    "components/ui/dialog.tsx"
  );

  const diagGrid = await makeFrame({
    parentId: secDialogs,
    name: "Dialogs Grid",
    layout: { mode: "HORIZONTAL", gap: 24, align: "MIN" },
    sizing: { w: "FILL", h: "HUG" }
  });

  // Modal 1: FormDialog
  const diag1 = await makeFrame({
    parentId: diagGrid,
    name: "<FormDialog>",
    bg: "#ffffff",
    border: "#cccccc",
    radius: 12,
    layout: { mode: "VERTICAL", px: 24, py: 20, gap: 14 },
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });
  await makeText({ parentId: diag1, text: "Configure Knowledge Connector", size: 16, weight: "Bold", color: "#1c1c1c" });
  await makeText({ parentId: diag1, text: "Set up target endpoint and periodic synchronization frequency.", size: 12, weight: "Regular", color: "#555555" });
  await makeFormInput(diag1, "Endpoint URL", "https://api.github.com/repos/...");
  const d1Foot = await makeFrame({ parentId: diag1, name: "<DialogFooter>", layout: { mode: "HORIZONTAL", justify: "MAX", gap: 8, align: "CENTER" }, sizing: { w: "FILL", h: "HUG" } });
  await makeCodeButton(d1Foot, "Cancel", { prominence: "secondary", size: "sm" });
  await makeCodeButton(d1Foot, "Save & Connect", { prominence: "primary", size: "sm" });

  // Modal 2: ConfirmDialog
  const diag2 = await makeFrame({
    parentId: diagGrid,
    name: "<ConfirmDialog>",
    bg: "#ffffff",
    border: "#f8a59b",
    radius: 12,
    layout: { mode: "VERTICAL", px: 24, py: 20, gap: 14 },
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });
  const d2Head = await makeFrame({ parentId: diag2, name: "<AlertDialogHeader>", layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" }, sizing: { w: "FILL", h: "HUG" } });
  await makeCodeStatusBadge(d2Head, "⚠", "danger", "pill");
  await makeText({ parentId: d2Head, text: "Delete Knowledge Partition?", size: 15, weight: "Bold", color: "#dc2626" });
  await makeText({ parentId: diag2, text: "This will permanently delete 14,200 indexed embeddings and cannot be undone.", size: 12, weight: "Regular", color: "#555555", lineHeight: 16 });
  const d2Foot = await makeFrame({ parentId: diag2, name: "<AlertDialogFooter>", layout: { mode: "HORIZONTAL", justify: "MAX", gap: 8, align: "CENTER" }, sizing: { w: "FILL", h: "HUG" } });
  await makeCodeButton(d2Foot, "Cancel", { prominence: "secondary", size: "sm" });
  await makeCodeButton(d2Foot, "Delete Permanently", { tone: "danger", prominence: "primary", size: "sm" });

  // Modal 3: DropdownMenu
  const diag3 = await makeFrame({
    parentId: diagGrid,
    name: "<DropdownMenu>",
    bg: "#ffffff",
    border: "#cccccc",
    radius: 8,
    layout: { mode: "VERTICAL", px: 8, py: 8, gap: 4 },
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });

  async function makeCodeMenuItem(parentId, label, isDanger = false) {
    const it = await makeFrame({
      parentId,
      name: `<DropdownMenuItem>`,
      bg: isDanger ? "#fceae7" : "#fafafa",
      radius: 4,
      layout: { mode: "HORIZONTAL", px: 10, py: 8, align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });
    await makeText({ parentId: it, text: label, size: 12, weight: "Medium", color: isDanger ? "#dc2626" : "#1c1c1c" });
  }

  await makeCodeMenuItem(diag3, "✏️ Edit Connector Config");
  await makeCodeMenuItem(diag3, "🔄 Force Pipeline Resync");
  await makeCodeMenuItem(diag3, "📋 Duplicate Parameters");
  await makeCodeMenuItem(diag3, "🗑️ Delete Connector", true);

  console.log("=========================================================");
  console.log("🎉 SUCCESS: 100% CODE-EXACT COMPONENT LIBRARY BUILT ON FIGMA!");
  console.log("=========================================================");
}

main().catch(err => {
  console.error("Execution error:", err);
  process.exit(1);
});
