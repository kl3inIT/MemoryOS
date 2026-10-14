import { createRequire } from "module";
const require = createRequire("C:/Users/Admin/AppData/Local/nvm/v26.5.1/node_modules/@figwright/mcp/package.json");
const { encode, decode } = require("@msgpack/msgpack");

async function callTool(toolName, args = {}) {
  // Clean undefined properties from args
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

async function makeButton(parentId, label, opts = {}) {
  const bg = opts.bg || "#1c1c1c";
  const textColor = opts.color || "#ffffff";
  const border = opts.border || null;
  const radius = opts.radius || 8;
  const px = opts.px || 14;

  const btn = await makeFrame({
    parentId,
    name: label || "Button",
    bg,
    border,
    radius,
    layout: {
      mode: "HORIZONTAL",
      justify: "CENTER",
      align: "CENTER",
      px,
      py: 7,
      gap: 8
    },
    sizing: { w: "HUG", h: "HUG" }
  });

  await makeText({
    parentId: btn,
    text: label,
    size: opts.fontSize || 13,
    weight: "SemiBold",
    color: textColor
  });

  return btn;
}

async function makeBadge(parentId, label, opts = {}) {
  const bg = opts.bg || "#1c1c1c";
  const textColor = opts.color || "#ffffff";
  const border = opts.border || null;
  const radius = opts.pill ? 11 : 6;

  const badge = await makeFrame({
    parentId,
    name: label || "Badge",
    bg,
    border,
    radius,
    layout: {
      mode: "HORIZONTAL",
      justify: "CENTER",
      align: "CENTER",
      px: 8,
      py: 3,
      gap: 6
    },
    sizing: { w: "HUG", h: "HUG" }
  });

  if (opts.dotColor) {
    await makeFrame({
      parentId: badge,
      name: "dot",
      width: 6,
      height: 6,
      bg: opts.dotColor,
      radius: 3,
      sizing: { w: "FIXED", h: "FIXED" }
    });
  }

  await makeText({
    parentId: badge,
    text: label,
    size: 11,
    weight: "SemiBold",
    color: textColor
  });

  return badge;
}

async function makeSectionCard(parentId, title, desc) {
  const sec = await makeFrame({
    parentId,
    name: title,
    width: 1360,
    bg: "#ffffff",
    border: "#e6e6e6",
    radius: 16,
    layout: {
      mode: "VERTICAL",
      px: 32,
      py: 32,
      gap: 20
    },
    sizing: { w: "FIXED", h: "HUG" }
  });

  const header = await makeFrame({
    parentId: sec,
    name: "Section Header",
    layout: { mode: "VERTICAL", gap: 4 },
    sizing: { w: "FILL", h: "HUG" }
  });

  await makeText({
    parentId: header,
    text: title,
    size: 20,
    weight: "Bold",
    color: "#1c1c1c"
  });

  if (desc) {
    await makeText({
      parentId: header,
      text: desc,
      size: 13,
      weight: "Regular",
      color: "#808080"
    });
  }

  return sec;
}

async function build() {
  console.log("1. Cleaning up existing page nodes...");
  const scanned = await callTool("scan_nodes_by_types", { types: ["FRAME", "SECTION"] });
  if (Array.isArray(scanned) && scanned.length > 0) {
    const topLevelIds = scanned.filter(n => n.parentId === "1:2").map(n => n.id);
    if (topLevelIds.length > 0) {
      await callTool("delete_nodes", { nodeIds: topLevelIds });
      console.log(`Deleted ${topLevelIds.length} top-level nodes.`);
    }
  }

  console.log("2. Creating Master Canvas Container...");
  const master = await makeFrame({
    name: "MemoryOS — UI Component & Element Library",
    x: 100,
    y: 100,
    width: 1440,
    bg: "#f0f0f1",
    radius: 24,
    layout: {
      mode: "VERTICAL",
      px: 40,
      py: 40,
      gap: 32,
      align: "CENTER"
    },
    sizing: { w: "FIXED", h: "HUG" }
  });

  // -------------------------------------------------------------
  // 00. HEADER BANNER
  // -------------------------------------------------------------
  console.log("3. Building Header Banner...");
  const banner = await makeFrame({
    parentId: master,
    name: "00. Header Banner",
    width: 1360,
    bg: "#1c1c1c",
    radius: 16,
    layout: {
      mode: "VERTICAL",
      px: 32,
      py: 28,
      gap: 12
    },
    sizing: { w: "FILL", h: "HUG" }
  });

  const bannerTags = await makeFrame({
    parentId: banner,
    name: "Tags",
    layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" },
    sizing: { w: "HUG", h: "HUG" }
  });
  await makeBadge(bannerTags, "MemoryOS v2.0", { bg: "#286df8", color: "#ffffff", pill: true });
  await makeBadge(bannerTags, "Design Tokens & Components Spec", { bg: "#333333", color: "#cccccc", pill: true });

  await makeText({
    parentId: banner,
    text: "MemoryOS UI Component & Element Library",
    size: 26,
    weight: "Bold",
    color: "#ffffff"
  });

  await makeText({
    parentId: banner,
    text: "Official pixel-perfect UI component and element specs extracted directly from the web codebase (Hanken Grotesk / Inter, Onyx Token Matrix, Radix/Shadcn Base).",
    size: 14,
    weight: "Regular",
    color: "#a4a4a4"
  });

  // -------------------------------------------------------------
  // 01. COLOR TOKENS
  // -------------------------------------------------------------
  console.log("4. Building Section 01: Colors...");
  const secColors = await makeSectionCard(
    master,
    "01. Foundations — Color Tokens System",
    "Semantic role swatches mapped 1:1 to CSS custom properties in tokens.css and theme.css"
  );

  const swatchGrid = await makeFrame({
    parentId: secColors,
    name: "Swatch Grid (5 Columns)",
    layout: { mode: "HORIZONTAL", gap: 20, align: "MIN" },
    sizing: { w: "FILL", h: "HUG" }
  });

  async function makeColorGroup(parentId, groupTitle, items) {
    const col = await makeFrame({
      parentId,
      name: groupTitle,
      layout: { mode: "VERTICAL", gap: 8 },
      sizing: { w: "FILL", h: "HUG", grow: 1 }
    });

    await makeText({ parentId: col, text: groupTitle, size: 13, weight: "SemiBold", color: "#1c1c1c" });

    for (const item of items) {
      const row = await makeFrame({
        parentId: col,
        name: item.name,
        bg: "#fafafa",
        border: "#e6e6e6",
        radius: 6,
        layout: { mode: "HORIZONTAL", px: 8, py: 6, gap: 10, align: "CENTER" },
        sizing: { w: "FILL", h: "HUG" }
      });

      await makeFrame({
        parentId: row,
        name: "color-box",
        width: 24,
        height: 24,
        bg: item.hex,
        border: item.stroke || "#cccccc",
        radius: 4,
        sizing: { w: "FIXED", h: "FIXED" }
      });

      const meta = await makeFrame({
        parentId: row,
        name: "meta",
        layout: { mode: "VERTICAL", gap: 2 },
        sizing: { w: "FILL", h: "HUG", grow: 1 }
      });

      await makeText({ parentId: meta, text: item.name, size: 11, weight: "SemiBold", color: "#1c1c1c" });
      await makeText({ parentId: meta, text: item.hex, size: 10, weight: "Regular", color: "#808080" });
    }
  }

  await makeColorGroup(swatchGrid, "Surfaces", [
    { name: "surface-canvas", hex: "#f0f0f1" },
    { name: "surface-base", hex: "#fafafa" },
    { name: "surface-raised", hex: "#ffffff" },
    { name: "surface-sunken", hex: "#e6e6e9" },
    { name: "surface-strong", hex: "#cccccf" }
  ]);

  await makeColorGroup(swatchGrid, "Content & Borders", [
    { name: "content-primary", hex: "#1c1c1c" },
    { name: "content-secondary", hex: "#555555" },
    { name: "content-muted", hex: "#808080" },
    { name: "border-subtle", hex: "#e6e6e6" },
    { name: "border-default", hex: "#cccccc" }
  ]);

  await makeColorGroup(swatchGrid, "Actions", [
    { name: "action-primary", hex: "#1c1c1c" },
    { name: "action-secondary", hex: "#fafafa" },
    { name: "action-selection", hex: "#286df8" },
    { name: "focus-ring", hex: "#000000" }
  ]);

  await makeColorGroup(swatchGrid, "Status Roles", [
    { name: "success-strong", hex: "#00a43f" },
    { name: "danger-strong", hex: "#dc2626" },
    { name: "warning-strong", hex: "#ec5b13" },
    { name: "info-strong", hex: "#286df8" }
  ]);

  await makeColorGroup(swatchGrid, "Chart Series", [
    { name: "chart-1 (Blue)", hex: "#286df8" },
    { name: "chart-2 (Orange)", hex: "#ce4b05" },
    { name: "chart-3 (Teal)", hex: "#009a99" },
    { name: "chart-6 (Green)", hex: "#008933" },
    { name: "chart-8 (Red)", hex: "#dc2626" }
  ]);

  // -------------------------------------------------------------
  // 02. TYPOGRAPHY SCALE
  // -------------------------------------------------------------
  console.log("5. Building Section 02: Typography...");
  const secTypo = await makeSectionCard(
    master,
    "02. Foundations — Typography Scale",
    "Font hierarchy matching Tailwind utilities font-heading-*, font-main-*, font-secondary-*"
  );

  const typoList = await makeFrame({
    parentId: secTypo,
    name: "Typography List",
    layout: { mode: "VERTICAL", gap: 8 },
    sizing: { w: "FILL", h: "HUG" }
  });

  const typoItems = [
    { label: "Heading H1", spec: "48px / 64px • SemiBold (-0.01em)", sample: "The intelligence substrate for personal memory", size: 28, weight: "SemiBold" },
    { label: "Heading H2", spec: "24px / 36px • SemiBold (-0.01em)", sample: "Knowledge Connectors & Retrieval Vectors", size: 22, weight: "SemiBold" },
    { label: "Heading H3", spec: "18px / 28px • SemiBold (-0.01em)", sample: "Active Context Stream & Ingestion Pipeline", size: 18, weight: "SemiBold" },
    { label: "Main Content Body", spec: "16px / 24px • Regular (450)", sample: "MemoryOS indexes local and remote data streams into high-dimensional semantic spaces.", size: 15, weight: "Regular" },
    { label: "Main UI Body / Action", spec: "14px / 20px • Medium / SemiBold", sample: "Configure pipeline parameters and provider API endpoints.", size: 14, weight: "Medium" },
    { label: "Secondary Body", spec: "12px / 16px • Regular / SemiBold", sample: "Last synchronized 2 minutes ago • 1,420 vectors processed", size: 12, weight: "Regular" },
    { label: "Figure / Micro Label", spec: "10px / 12px • SemiBold", sample: "STATUS: SYNCED • MODEL: GEMINI-PRO", size: 10, weight: "SemiBold" }
  ];

  for (let i = 0; i < typoItems.length; i++) {
    const t = typoItems[i];
    const row = await makeFrame({
      parentId: typoList,
      name: t.label,
      bg: i % 2 === 0 ? "#fafafa" : "#ffffff",
      border: "#f0f0f1",
      radius: 6,
      layout: { mode: "HORIZONTAL", px: 16, py: 10, gap: 24, align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });

    const meta = await makeFrame({
      parentId: row,
      name: "meta",
      width: 240,
      layout: { mode: "VERTICAL", gap: 2 },
      sizing: { w: "FIXED", h: "HUG" }
    });
    await makeText({ parentId: meta, text: t.label, size: 12, weight: "SemiBold", color: "#1c1c1c" });
    await makeText({ parentId: meta, text: t.spec, size: 10, weight: "Regular", color: "#808080" });

    await makeText({
      parentId: row,
      text: t.sample,
      size: t.size,
      weight: t.weight,
      color: "#1c1c1c",
      sizing: { w: "FILL", h: "HUG", grow: 1 }
    });
  }

  // -------------------------------------------------------------
  // 03. BUTTONS & ACTIONS
  // -------------------------------------------------------------
  console.log("6. Building Section 03: Buttons...");
  const secButtons = await makeSectionCard(
    master,
    "03. Components — Buttons & Actions Matrix",
    "Ma trận tone (default, danger) × prominence (primary, secondary, tertiary) × size (sm 28px, md 36px, lg 40px)"
  );

  await makeText({ parentId: secButtons, text: "Prominence × Tone Matrix (Size MD: 36px)", size: 13, weight: "SemiBold", color: "#555555" });

  const btnRow1 = await makeFrame({
    parentId: secButtons,
    name: "Prominence Row",
    layout: { mode: "HORIZONTAL", gap: 12, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });

  await makeButton(btnRow1, "Primary Default", { bg: "#1c1c1c", color: "#ffffff" });
  await makeButton(btnRow1, "Secondary Default", { bg: "#fafafa", border: "#cccccc", color: "#1c1c1c" });
  await makeButton(btnRow1, "Tertiary (Ghost)", { bg: "transparent", color: "#1c1c1c" });
  await makeButton(btnRow1, "Danger Primary", { bg: "#dc2626", color: "#ffffff" });
  await makeButton(btnRow1, "Danger Secondary", { bg: "#fceae7", border: "#f8a59b", color: "#c12222" });
  await makeButton(btnRow1, "Disabled", { bg: "#e6e6e9", border: "#cccccc", color: "#a4a4a4" });

  await makeText({ parentId: secButtons, text: "Control Sizes & Specialized States", size: 13, weight: "SemiBold", color: "#555555" });

  const btnRow2 = await makeFrame({
    parentId: secButtons,
    name: "Sizes & States Row",
    layout: { mode: "HORIZONTAL", gap: 12, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });

  await makeButton(btnRow2, "Small (28px)", { fontSize: 12, px: 10, bg: "#1c1c1c", color: "#ffffff" });
  await makeButton(btnRow2, "Medium (36px)", { fontSize: 13, px: 14, bg: "#1c1c1c", color: "#ffffff" });
  await makeButton(btnRow2, "Large (40px)", { fontSize: 14, px: 16, bg: "#1c1c1c", color: "#ffffff" });
  await makeButton(btnRow2, "Selection Accent", { bg: "#286df8", color: "#ffffff" });
  await makeButton(btnRow2, "Search Icon", { bg: "#fafafa", border: "#cccccc", color: "#1c1c1c" });

  // -------------------------------------------------------------
  // 04. BADGES & STATUS
  // -------------------------------------------------------------
  console.log("7. Building Section 04: Badges...");
  const secBadges = await makeSectionCard(
    master,
    "04. Components — Badges & Status Indicators",
    "Core badge variants and Onyx status roles with soft tints and emphasis pills"
  );

  await makeText({ parentId: secBadges, text: "Core Badge Variants", size: 13, weight: "SemiBold", color: "#555555" });
  const badgeRow1 = await makeFrame({
    parentId: secBadges,
    name: "Core Badges Row",
    layout: { mode: "HORIZONTAL", gap: 10, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });

  await makeBadge(badgeRow1, "Default", { bg: "#1c1c1c", color: "#ffffff" });
  await makeBadge(badgeRow1, "Secondary", { bg: "#f0f0f1", color: "#1c1c1c" });
  await makeBadge(badgeRow1, "Outline", { bg: "#ffffff", border: "#cccccc", color: "#1c1c1c" });
  await makeBadge(badgeRow1, "Destructive", { bg: "#fceae7", border: "#f8a59b", color: "#c12222" });
  await makeBadge(badgeRow1, "Ghost", { bg: "transparent", color: "#555555" });
  await makeBadge(badgeRow1, "Count: 42", { bg: "#286df8", color: "#ffffff", pill: true });

  await makeText({ parentId: secBadges, text: "Status Badges (Soft Tints & Solid Emphasis)", size: 13, weight: "SemiBold", color: "#555555" });
  const badgeRow2 = await makeFrame({
    parentId: secBadges,
    name: "Status Badges Row",
    layout: { mode: "HORIZONTAL", gap: 10, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });

  await makeBadge(badgeRow2, "Active / Online", { bg: "#e6f2e7", border: "#91d099", color: "#007a2d", dotColor: "#00a43f" });
  await makeBadge(badgeRow2, "Pending Sync", { bg: "#fbeae4", border: "#f5a88b", color: "#b54204", dotColor: "#ec5b13" });
  await makeBadge(badgeRow2, "Sync Failed", { bg: "#fceae7", border: "#f8a59b", color: "#c12222", dotColor: "#dc2626" });
  await makeBadge(badgeRow2, "Processing", { bg: "#e7effc", border: "#9bbeff", color: "#245fda", dotColor: "#286df8" });
  await makeBadge(badgeRow2, "Completed", { bg: "#007a2d", color: "#ffffff", pill: true });
  await makeBadge(badgeRow2, "Error", { bg: "#c12222", color: "#ffffff", pill: true });

  // -------------------------------------------------------------
  // 05. FORM CONTROLS & INPUTS
  // -------------------------------------------------------------
  console.log("8. Building Section 05: Form Controls...");
  const secForms = await makeSectionCard(
    master,
    "05. Components — Form Controls & Inputs",
    "Inputs, Textareas, Switches, Checkboxes, and Radio groups (Exact 3-Column Grid)"
  );

  const formGrid = await makeFrame({
    parentId: secForms,
    name: "Form 3-Column Grid",
    layout: { mode: "HORIZONTAL", gap: 24, align: "MIN" },
    sizing: { w: "FILL", h: "HUG" }
  });

  // Col 1: Text Inputs
  const formCol1 = await makeFrame({
    parentId: formGrid,
    name: "Column 1: Inputs",
    layout: { mode: "VERTICAL", gap: 14 },
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });

  async function makeInputField(parentId, label, val, opts = {}) {
    const group = await makeFrame({
      parentId,
      name: label,
      layout: { mode: "VERTICAL", gap: 6 },
      sizing: { w: "FILL", h: "HUG" }
    });
    await makeText({ parentId: group, text: label, size: 12, weight: "SemiBold", color: "#1c1c1c" });

    const box = await makeFrame({
      parentId: group,
      name: "input-box",
      bg: opts.disabled ? "#f0f0f1" : "#ffffff",
      border: opts.error ? "#dc2626" : (opts.focused ? "#1c1c1c" : "#cccccc"),
      borderWidth: opts.focused ? 1.5 : 1,
      radius: 8,
      layout: { mode: "HORIZONTAL", px: 12, py: 8, align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });

    await makeText({
      parentId: box,
      text: val,
      size: 13,
      weight: "Regular",
      color: opts.placeholder ? "#808080" : "#1c1c1c",
      sizing: { w: "FILL", h: "HUG", grow: 1 }
    });

    if (opts.helper) {
      await makeText({
        parentId: group,
        text: opts.helper,
        size: 11,
        weight: "Regular",
        color: opts.error ? "#dc2626" : "#808080"
      });
    }
  }

  await makeInputField(formCol1, "Connector Name", "Local Obsidian Vault");
  await makeInputField(formCol1, "Search Query", "Search vectors, docs, conversations...", { placeholder: true });
  await makeInputField(formCol1, "API Secret Token", "••••••••••••••••••••", { error: true, helper: "Invalid token format." });
  await makeInputField(formCol1, "Embedding Model", "text-embedding-3-large (Selected)");

  // Col 2: Textarea & Switches
  const formCol2 = await makeFrame({
    parentId: formGrid,
    name: "Column 2: Textarea & Switches",
    layout: { mode: "VERTICAL", gap: 14 },
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });

  const taGroup = await makeFrame({
    parentId: formCol2,
    name: "Textarea Group",
    layout: { mode: "VERTICAL", gap: 6 },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeText({ parentId: taGroup, text: "System Prompt Context", size: 12, weight: "SemiBold", color: "#1c1c1c" });

  const taBox = await makeFrame({
    parentId: taGroup,
    name: "textarea-box",
    bg: "#ffffff",
    border: "#cccccc",
    radius: 8,
    layout: { mode: "VERTICAL", px: 12, py: 10 },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeText({
    parentId: taBox,
    text: "You are MemoryOS Assistant. Leverage connected episodic and semantic memory to synthesize deep answers with citations...",
    size: 12,
    weight: "Regular",
    color: "#1c1c1c",
    lineHeight: 18,
    sizing: { w: "FILL", h: "HUG" }
  });

  async function makeSwitchRow(parentId, label, desc, isOn) {
    const row = await makeFrame({
      parentId,
      name: label,
      bg: "#fafafa",
      border: "#f0f0f1",
      radius: 8,
      layout: { mode: "HORIZONTAL", px: 12, py: 8, justify: "SPACE_BETWEEN", align: "CENTER", gap: 12 },
      sizing: { w: "FILL", h: "HUG" }
    });

    const meta = await makeFrame({
      parentId: row,
      name: "meta",
      layout: { mode: "VERTICAL", gap: 2 },
      sizing: { w: "FILL", h: "HUG", grow: 1 }
    });
    await makeText({ parentId: meta, text: label, size: 12, weight: "SemiBold", color: "#1c1c1c" });
    await makeText({ parentId: meta, text: desc, size: 10, weight: "Regular", color: "#808080" });

    const sw = await makeFrame({
      parentId: row,
      name: "switch",
      width: 44,
      height: 24,
      bg: isOn ? "#1c1c1c" : "#e6e6e9",
      radius: 12,
      layout: {
        mode: "HORIZONTAL",
        pl: isOn ? 22 : 2,
        pr: isOn ? 2 : 22,
        align: "CENTER"
      },
      sizing: { w: "FIXED", h: "FIXED" }
    });
    await makeFrame({
      parentId: sw,
      name: "thumb",
      width: 20,
      height: 20,
      bg: "#ffffff",
      radius: 10,
      sizing: { w: "FIXED", h: "FIXED" }
    });
  }

  await makeSwitchRow(formCol2, "Real-time Vector Sync", "Poll modified files and update embeddings", true);
  await makeSwitchRow(formCol2, "Episodic Reranking", "Boost recent context in hybrid search retrieval", false);

  // Col 3: Checkboxes & Radio
  const formCol3 = await makeFrame({
    parentId: formGrid,
    name: "Column 3: Selection Controls",
    layout: { mode: "VERTICAL", gap: 10 },
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });

  await makeText({ parentId: formCol3, text: "Checkboxes & Selection Controls", size: 12, weight: "SemiBold", color: "#1c1c1c" });

  async function makeCheckbox(parentId, label, isChecked) {
    const row = await makeFrame({
      parentId,
      name: label,
      bg: "#fafafa",
      border: "#f0f0f1",
      radius: 6,
      layout: { mode: "HORIZONTAL", px: 10, py: 8, gap: 10, align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });

    const box = await makeFrame({
      parentId: row,
      name: "check-box",
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

    await makeText({ parentId: row, text: label, size: 12, weight: "Regular", color: "#1c1c1c", sizing: { w: "FILL", h: "HUG", grow: 1 } });
  }

  await makeCheckbox(formCol3, "Include PDF attachments in vector extraction", true);
  await makeCheckbox(formCol3, "Index Markdown code snippets and inline code", true);
  await makeCheckbox(formCol3, "Automatic provider fallback on rate limits", false);

  await makeText({ parentId: formCol3, text: "Retrieval Strategy (Radio)", size: 12, weight: "SemiBold", color: "#1c1c1c" });

  async function makeRadio(parentId, label, isSelected) {
    const row = await makeFrame({
      parentId,
      name: label,
      bg: "#fafafa",
      border: "#f0f0f1",
      radius: 6,
      layout: { mode: "HORIZONTAL", px: 10, py: 8, gap: 10, align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });

    const circle = await makeFrame({
      parentId: row,
      name: "radio-circle",
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

    await makeText({ parentId: row, text: label, size: 12, weight: "Regular", color: "#1c1c1c", sizing: { w: "FILL", h: "HUG", grow: 1 } });
  }

  await makeRadio(formCol3, "Hybrid (Dense Vector + BM25 Lexical)", true);
  await makeRadio(formCol3, "Dense Vector Only (HNSW Cosine)", false);

  // -------------------------------------------------------------
  // 06. CARDS & COMPOSITES
  // -------------------------------------------------------------
  console.log("9. Building Section 06: Cards & Composites...");
  const secCards = await makeSectionCard(
    master,
    "06. Components — Cards & Composites",
    "Stat tiles, settings cards, and destructive alert cards (Symmetric 3-Column Grid)"
  );

  const cardGrid = await makeFrame({
    parentId: secCards,
    name: "Cards 3-Column Grid",
    layout: { mode: "HORIZONTAL", gap: 24, align: "MIN" },
    sizing: { w: "FILL", h: "HUG" }
  });

  // Card 1: Stat Tile
  const statCard = await makeFrame({
    parentId: cardGrid,
    name: "Stat Tile Card",
    bg: "#ffffff",
    border: "#e6e6e6",
    radius: 12,
    layout: { mode: "VERTICAL", px: 20, py: 20, gap: 14 },
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });

  const statTop = await makeFrame({
    parentId: statCard,
    name: "top",
    layout: { mode: "HORIZONTAL", justify: "SPACE_BETWEEN", align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeText({ parentId: statTop, text: "Indexed Embeddings", size: 13, weight: "Medium", color: "#555555" });
  await makeBadge(statTop, "DB", { bg: "#e7effc", color: "#245fda", pill: true });

  await makeText({ parentId: statCard, text: "1,248,930", size: 28, weight: "Bold", color: "#1c1c1c" });

  const statBottom = await makeFrame({
    parentId: statCard,
    name: "bottom",
    layout: { mode: "HORIZONTAL", gap: 6, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeBadge(statBottom, "+14.2% ↑", { bg: "#e6f2e7", color: "#007a2d" });
  await makeText({ parentId: statBottom, text: "vs. previous sync cycle", size: 11, weight: "Regular", color: "#808080" });

  // Card 2: Setting / Connector Card
  const connCard = await makeFrame({
    parentId: cardGrid,
    name: "Connector Setting Card",
    bg: "#fafafa",
    border: "#e6e6e6",
    radius: 12,
    layout: { mode: "VERTICAL", px: 20, py: 20, gap: 14 },
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });

  const connHead = await makeFrame({
    parentId: connCard,
    name: "head",
    layout: { mode: "HORIZONTAL", justify: "SPACE_BETWEEN", align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });

  const connLeft = await makeFrame({
    parentId: connHead,
    name: "left",
    layout: { mode: "VERTICAL", gap: 2 },
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });
  await makeText({ parentId: connLeft, text: "Markdown Vault", size: 14, weight: "SemiBold", color: "#1c1c1c" });
  await makeText({ parentId: connLeft, text: "D:/Notes/Personal (8,432 files)", size: 11, weight: "Regular", color: "#808080" });

  await makeBadge(connHead, "Live Sync", { bg: "#e6f2e7", border: "#91d099", color: "#007a2d", dotColor: "#00a43f" });

  await makeText({
    parentId: connCard,
    text: "Indexes all .md notes with frontmatter metadata tags. Real-time file watcher monitors write events and updates embeddings.",
    size: 12,
    weight: "Regular",
    color: "#555555",
    lineHeight: 18,
    sizing: { w: "FILL", h: "HUG" }
  });

  const connFoot = await makeFrame({
    parentId: connCard,
    name: "foot",
    layout: { mode: "HORIZONTAL", justify: "SPACE_BETWEEN", align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeText({ parentId: connFoot, text: "Last scan: 3m ago", size: 11, weight: "Regular", color: "#808080" });
  await makeButton(connFoot, "Resync Now", { height: 28, fontSize: 11, px: 10, bg: "#1c1c1c", color: "#ffffff" });

  // Card 3: Danger Zone Card
  const dangerCard = await makeFrame({
    parentId: cardGrid,
    name: "Danger Zone Card",
    bg: "#ffffff",
    border: "#f8a59b",
    radius: 12,
    layout: { mode: "VERTICAL", px: 20, py: 20, gap: 14 },
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });

  const dzTitle = await makeFrame({
    parentId: dangerCard,
    name: "title-row",
    layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeBadge(dzTitle, "⚠ DANGER", { bg: "#fceae7", border: "#f8a59b", color: "#c12222" });
  await makeText({ parentId: dzTitle, text: "Purge Vector Index", size: 13, weight: "Bold", color: "#dc2626" });

  await makeText({
    parentId: dangerCard,
    text: "Purging index drops all pre-computed cosine embeddings and resets knowledge partitions. This action is irreversible.",
    size: 12,
    weight: "Regular",
    color: "#555555",
    lineHeight: 18,
    sizing: { w: "FILL", h: "HUG" }
  });

  const dzActions = await makeFrame({
    parentId: dangerCard,
    name: "actions",
    layout: { mode: "HORIZONTAL", justify: "MIN", align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeButton(dzActions, "Purge Vector Index", { height: 32, fontSize: 11, px: 12, bg: "#dc2626", color: "#ffffff" });

  // -------------------------------------------------------------
  // 07. DATA TABLE & FEEDBACK OVERLAYS
  // -------------------------------------------------------------
  console.log("10. Building Section 07: Data Table & Alerts...");
  const secTable = await makeSectionCard(
    master,
    "07. Components — Data Table & Feedback Overlays",
    "Alert banners and full data table with sortable columns, row statuses, and pagination"
  );

  const alertRow = await makeFrame({
    parentId: secTable,
    name: "Alert Banners Grid",
    layout: { mode: "HORIZONTAL", gap: 16, align: "MIN" },
    sizing: { w: "FILL", h: "HUG" }
  });

  async function makeAlert(parentId, title, desc, tone) {
    const tones = {
      info: { bg: "#e7effc", border: "#9bbeff", text: "#245fda", label: "INFO" },
      success: { bg: "#e6f2e7", border: "#91d099", text: "#007a2d", label: "SUCCESS" },
      warning: { bg: "#fbeae4", border: "#f5a88b", text: "#b54204", label: "WARNING" },
      danger: { bg: "#fceae7", border: "#f8a59b", text: "#c12222", label: "ERROR" }
    };
    const t = tones[tone];
    const a = await makeFrame({
      parentId,
      name: `Alert ${tone}`,
      bg: t.bg,
      border: t.border,
      radius: 8,
      layout: { mode: "VERTICAL", px: 14, py: 12, gap: 4 },
      sizing: { w: "FILL", h: "HUG", grow: 1 }
    });
    await makeText({ parentId: a, text: `[${t.label}] ${title}`, size: 12, weight: "SemiBold", color: t.text });
    await makeText({ parentId: a, text: desc, size: 11, weight: "Regular", color: t.text, lineHeight: 16 });
  }

  await makeAlert(alertRow, "Model Upgraded", "Active model set to Gemini 1.5 Pro.", "info");
  await makeAlert(alertRow, "Sync Completed", "2,410 vectors updated successfully.", "success");
  await makeAlert(alertRow, "High Token Usage", "Current chat exceeded 80k prompt tokens.", "warning");
  await makeAlert(alertRow, "Provider Timeout", "API endpoint failed after 3 retries.", "danger");

  // Data Table Frame
  const tableCard = await makeFrame({
    parentId: secTable,
    name: "Data Table",
    bg: "#ffffff",
    border: "#e6e6e6",
    radius: 8,
    layout: { mode: "VERTICAL" },
    sizing: { w: "FILL", h: "HUG" }
  });

  const tHead = await makeFrame({
    parentId: tableCard,
    name: "Table Header",
    bg: "#f0f0f1",
    layout: { mode: "HORIZONTAL", px: 16, py: 10, gap: 16, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeText({ parentId: tHead, text: "DOCUMENT / SOURCE", size: 11, weight: "Bold", color: "#555555", sizing: { w: "FILL", h: "HUG", grow: 2 } });
  await makeText({ parentId: tHead, text: "STATUS", size: 11, weight: "Bold", color: "#555555", sizing: { w: "FILL", h: "HUG", grow: 1 } });
  await makeText({ parentId: tHead, text: "EMBEDDING MODEL", size: 11, weight: "Bold", color: "#555555", sizing: { w: "FILL", h: "HUG", grow: 1.5 } });
  await makeText({ parentId: tHead, text: "CHUNKS", size: 11, weight: "Bold", color: "#555555", sizing: { w: "FILL", h: "HUG", grow: 1 } });
  await makeText({ parentId: tHead, text: "ACTIONS", size: 11, weight: "Bold", color: "#555555", sizing: { w: "FILL", h: "HUG", grow: 1 } });

  async function makeTableRow(parentId, docName, docMeta, statusLabel, statusTone, model, chunks) {
    const r = await makeFrame({
      parentId,
      name: docName,
      bg: "#ffffff",
      border: "#f0f0f1",
      layout: { mode: "HORIZONTAL", px: 16, py: 12, gap: 16, align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });

    const docCell = await makeFrame({
      parentId: r,
      name: "doc-cell",
      layout: { mode: "VERTICAL", gap: 2 },
      sizing: { w: "FILL", h: "HUG", grow: 2 }
    });
    await makeText({ parentId: docCell, text: docName, size: 13, weight: "SemiBold", color: "#1c1c1c" });
    await makeText({ parentId: docCell, text: docMeta, size: 11, weight: "Regular", color: "#808080" });

    const stCell = await makeFrame({
      parentId: r,
      name: "status-cell",
      layout: { mode: "HORIZONTAL", align: "CENTER" },
      sizing: { w: "FILL", h: "HUG", grow: 1 }
    });
    const colors = {
      success: { bg: "#e6f2e7", border: "#91d099", color: "#007a2d", dot: "#00a43f" },
      warning: { bg: "#fbeae4", border: "#f5a88b", color: "#b54204", dot: "#ec5b13" }
    };
    const c = colors[statusTone];
    await makeBadge(stCell, statusLabel, { bg: c.bg, border: c.border, color: c.color, dotColor: c.dot });

    await makeText({ parentId: r, text: model, size: 13, weight: "Regular", color: "#1c1c1c", sizing: { w: "FILL", h: "HUG", grow: 1.5 } });
    await makeText({ parentId: r, text: chunks, size: 13, weight: "Regular", color: "#555555", sizing: { w: "FILL", h: "HUG", grow: 1 } });

    const actCell = await makeFrame({
      parentId: r,
      name: "act-cell",
      layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" },
      sizing: { w: "FILL", h: "HUG", grow: 1 }
    });
    await makeButton(actCell, "View", { height: 26, fontSize: 11, px: 8, bg: "#fafafa", border: "#cccccc", color: "#1c1c1c" });
    await makeButton(actCell, "Resync", { height: 26, fontSize: 11, px: 8, bg: "#fafafa", border: "#cccccc", color: "#1c1c1c" });
  }

  await makeTableRow(tableCard, "Quarterly_Financial_Report_2026.pdf", "PDF Document • 4.2 MB", "Indexed", "success", "text-embedding-3-large", "482 chunks");
  await makeTableRow(tableCard, "Architecture_Decision_Records.md", "Markdown • 128 KB", "Indexed", "success", "text-embedding-3-large", "94 chunks");
  await makeTableRow(tableCard, "Customer_Support_Transcripts.csv", "CSV Sheet • 18.5 MB", "Syncing...", "warning", "text-embedding-3-large", "1,840 chunks");

  const tFoot = await makeFrame({
    parentId: tableCard,
    name: "Table Footer",
    bg: "#fafafa",
    border: "#e6e6e6",
    layout: { mode: "HORIZONTAL", px: 16, py: 10, justify: "SPACE_BETWEEN", align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeText({ parentId: tFoot, text: "Showing 1-3 of 24 indexed documents", size: 12, weight: "Regular", color: "#808080" });
  const pgBtns = await makeFrame({ parentId: tFoot, name: "pg-btns", layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" }, sizing: { w: "HUG", h: "HUG" } });
  await makeButton(pgBtns, "Previous", { height: 28, fontSize: 11, px: 10, bg: "#ffffff", border: "#cccccc", color: "#1c1c1c" });
  await makeButton(pgBtns, "Next Page", { height: 28, fontSize: 11, px: 10, bg: "#1c1c1c", color: "#ffffff" });

  // -------------------------------------------------------------
  // 08. NAVIGATION & APP SHELL
  // -------------------------------------------------------------
  console.log("11. Building Section 08: Navigation...");
  const secNav = await makeSectionCard(
    master,
    "08. Navigation & App Shell",
    "Sidebar navigation links with count badges, app header topbar, and underline tabs"
  );

  const navGrid = await makeFrame({
    parentId: secNav,
    name: "Nav Layout Grid",
    layout: { mode: "HORIZONTAL", gap: 32, align: "MIN" },
    sizing: { w: "FILL", h: "HUG" }
  });

  const sidebar = await makeFrame({
    parentId: navGrid,
    name: "Sidebar (240px)",
    width: 260,
    bg: "#f0f0f1",
    border: "#e6e6e6",
    radius: 12,
    layout: { mode: "VERTICAL", px: 16, py: 16, gap: 16 },
    sizing: { w: "FIXED", h: "HUG" }
  });

  const brand = await makeFrame({ parentId: sidebar, name: "brand", layout: { mode: "HORIZONTAL", gap: 10, align: "CENTER" }, sizing: { w: "FILL", h: "HUG" } });
  await makeBadge(brand, "M", { bg: "#1c1c1c", color: "#ffffff", pill: true });
  await makeText({ parentId: brand, text: "MemoryOS", size: 15, weight: "Bold", color: "#1c1c1c" });

  async function makeSidebarLink(parentId, label, isActive, count) {
    const link = await makeFrame({
      parentId,
      name: label,
      bg: isActive ? "#ffffff" : "transparent",
      border: isActive ? "#e6e6e6" : null,
      radius: 6,
      layout: { mode: "HORIZONTAL", px: 10, py: 8, justify: "SPACE_BETWEEN", align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });
    await makeText({ parentId: link, text: label, size: 13, weight: isActive ? "SemiBold" : "Medium", color: isActive ? "#1c1c1c" : "#555555" });
    if (count) {
      await makeBadge(link, count, { bg: "#e6e6e9", color: "#555555", pill: true });
    }
  }

  const sbSec1 = await makeFrame({ parentId: sidebar, name: "group1", layout: { mode: "VERTICAL", gap: 4 }, sizing: { w: "FILL", h: "HUG" } });
  await makeText({ parentId: sbSec1, text: "INTELLIGENCE", size: 10, weight: "Bold", color: "#808080" });
  await makeSidebarLink(sbSec1, "Assistant & Chat", true);
  await makeSidebarLink(sbSec1, "Context Streams", false, "3");
  await makeSidebarLink(sbSec1, "Search & Vectors", false);

  const sbSec2 = await makeFrame({ parentId: sidebar, name: "group2", layout: { mode: "VERTICAL", gap: 4 }, sizing: { w: "FILL", h: "HUG" } });
  await makeText({ parentId: sbSec2, text: "KNOWLEDGE", size: 10, weight: "Bold", color: "#808080" });
  await makeSidebarLink(sbSec2, "Connectors", false, "12");
  await makeSidebarLink(sbSec2, "Documents", false);
  await makeSidebarLink(sbSec2, "Settings", false);

  // Right Side: Topbar & Tabs
  const navRight = await makeFrame({
    parentId: navGrid,
    name: "Right Side",
    layout: { mode: "VERTICAL", gap: 20 },
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });

  const topHeader = await makeFrame({
    parentId: navRight,
    name: "App Topbar Header",
    bg: "#ffffff",
    border: "#e6e6e6",
    radius: 8,
    layout: { mode: "HORIZONTAL", px: 20, py: 12, justify: "SPACE_BETWEEN", align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeText({ parentId: topHeader, text: "Workspace / Personal Memory / Settings", size: 13, weight: "Medium", color: "#555555" });
  const headerRight = await makeFrame({ parentId: topHeader, name: "right", layout: { mode: "HORIZONTAL", gap: 10, align: "CENTER" }, sizing: { w: "HUG", h: "HUG" } });
  await makeBadge(headerRight, "Gemini 1.5 Pro ▼", { bg: "#fafafa", border: "#cccccc", color: "#1c1c1c" });
  await makeBadge(headerRight, "MO", { bg: "#1c1c1c", color: "#ffffff", pill: true });

  const tabList = await makeFrame({
    parentId: navRight,
    name: "Tabs Underline Bar",
    border: "#e6e6e6",
    layout: { mode: "HORIZONTAL", gap: 28, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });

  async function makeTab(parentId, label, isActive) {
    const tab = await makeFrame({
      parentId,
      name: label,
      layout: { mode: "VERTICAL", pt: 8, pb: 8, gap: 6, align: "CENTER" },
      sizing: { w: "HUG", h: "HUG" }
    });
    await makeText({ parentId: tab, text: label, size: 13, weight: isActive ? "SemiBold" : "Medium", color: isActive ? "#1c1c1c" : "#808080" });
    await makeFrame({
      parentId: tab,
      name: "indicator",
      width: 60,
      height: 2,
      bg: isActive ? "#1c1c1c" : "transparent",
      sizing: { w: "FIXED", h: "FIXED" }
    });
  }

  await makeTab(tabList, "Overview", true);
  await makeTab(tabList, "Vector Pipeline", false);
  await makeTab(tabList, "Retrieval Benchmark", false);
  await makeTab(tabList, "Audit Logs", false);

  // -------------------------------------------------------------
  // 09. ASSISTANT & CHAT UI
  // -------------------------------------------------------------
  console.log("12. Building Section 09: Assistant UI...");
  const secChat = await makeSectionCard(
    master,
    "09. Components — Assistant & Chat UI",
    "Conversation thread bubbles, thinking state indicator, and citation chips"
  );

  const chatGrid = await makeFrame({
    parentId: secChat,
    name: "Chat Grid",
    layout: { mode: "HORIZONTAL", gap: 24, align: "MIN" },
    sizing: { w: "FILL", h: "HUG" }
  });

  // Left Thread
  const chatThread = await makeFrame({
    parentId: chatGrid,
    name: "Thread View",
    bg: "#fafafa",
    border: "#e6e6e6",
    radius: 12,
    layout: { mode: "VERTICAL", px: 20, py: 20, gap: 16 },
    sizing: { w: "FILL", h: "HUG", grow: 2 }
  });

  const userMsgRow = await makeFrame({
    parentId: chatThread,
    name: "User Row",
    layout: { mode: "HORIZONTAL", justify: "MAX" },
    sizing: { w: "FILL", h: "HUG" }
  });
  const userBubble = await makeFrame({
    parentId: userMsgRow,
    name: "User Bubble",
    bg: "#1c1c1c",
    radius: 10,
    layout: { mode: "VERTICAL", px: 14, py: 10 },
    sizing: { w: "HUG", h: "HUG" }
  });
  await makeText({ parentId: userBubble, text: "What are the active data ingestion policies in MemoryOS?", size: 13, weight: "Regular", color: "#ffffff" });

  const aiMsgRow = await makeFrame({
    parentId: chatThread,
    name: "AI Row",
    layout: { mode: "HORIZONTAL", gap: 12, align: "MIN" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeBadge(aiMsgRow, "✨", { bg: "#286df8", color: "#ffffff", pill: true });

  const aiBody = await makeFrame({
    parentId: aiMsgRow,
    name: "AI Body",
    layout: { mode: "VERTICAL", gap: 10 },
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });

  const thinkBox = await makeFrame({
    parentId: aiBody,
    name: "Thinking Indicator",
    bg: "#f0f0f1",
    border: "#cccccc",
    radius: 6,
    layout: { mode: "HORIZONTAL", px: 10, py: 6, gap: 8, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeText({ parentId: thinkBox, text: "⚡ Synthesized 2 memory vectors from policy docs (took 0.35s)", size: 11, weight: "Medium", color: "#555555" });

  await makeText({
    parentId: aiBody,
    text: "MemoryOS enforces a zero-speculative ingestion policy. All document chunks are hashed, tokenized, and embedded via text-embedding-3-large with cosine metric distance.",
    size: 13,
    weight: "Regular",
    color: "#1c1c1c",
    lineHeight: 18,
    sizing: { w: "FILL", h: "HUG" }
  });

  const citeRow = await makeFrame({
    parentId: aiBody,
    name: "Citations",
    layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeBadge(citeRow, "📄 ADR-0002.md", { bg: "#e7effc", border: "#9bbeff", color: "#245fda" });
  await makeBadge(citeRow, "📄 backend_policy.md", { bg: "#e7effc", border: "#9bbeff", color: "#245fda" });

  // Right Reference Card
  const chatRight = await makeFrame({
    parentId: chatGrid,
    name: "Context Reference",
    layout: { mode: "VERTICAL", gap: 14 },
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });

  const srcCard = await makeFrame({
    parentId: chatRight,
    name: "Source Card",
    bg: "#ffffff",
    border: "#e6e6e6",
    radius: 10,
    layout: { mode: "VERTICAL", px: 16, py: 14, gap: 8 },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeText({ parentId: srcCard, text: "Connected Source Reference", size: 12, weight: "Bold", color: "#808080" });
  await makeText({ parentId: srcCard, text: "retrieval_router.go", size: 13, weight: "SemiBold", color: "#1c1c1c" });
  await makeText({ parentId: srcCard, text: "MemoryOS / core / retrieval • 342 lines indexed", size: 11, weight: "Regular", color: "#808080" });

  // -------------------------------------------------------------
  // 10. DIALOGS & OVERLAYS
  // -------------------------------------------------------------
  console.log("13. Building Section 10: Dialogs & Overlays...");
  const secDialogs = await makeSectionCard(
    master,
    "10. Components — Dialogs & Overlays",
    "Standard form dialogs, destructive confirmation modals, and dropdown popovers"
  );

  const diagGrid = await makeFrame({
    parentId: secDialogs,
    name: "Dialogs Grid",
    layout: { mode: "HORIZONTAL", gap: 24, align: "MIN" },
    sizing: { w: "FILL", h: "HUG" }
  });

  // Modal 1: Form Dialog
  const diag1 = await makeFrame({
    parentId: diagGrid,
    name: "Form Dialog",
    bg: "#ffffff",
    border: "#cccccc",
    radius: 12,
    layout: { mode: "VERTICAL", px: 24, py: 20, gap: 14 },
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });
  await makeText({ parentId: diag1, text: "Add Knowledge Connector", size: 16, weight: "Bold", color: "#1c1c1c" });
  await makeText({ parentId: diag1, text: "Configure a new sync source to continuously feed knowledge vectors into your private space.", size: 12, weight: "Regular", color: "#555555", lineHeight: 16 });
  await makeInputField(diag1, "Endpoint URL", "https://api.github.com/repos/...");
  const d1Foot = await makeFrame({ parentId: diag1, name: "foot", layout: { mode: "HORIZONTAL", justify: "MAX", gap: 8, align: "CENTER" }, sizing: { w: "FILL", h: "HUG" } });
  await makeButton(d1Foot, "Cancel", { fontSize: 12, px: 12, bg: "#fafafa", border: "#cccccc", color: "#1c1c1c" });
  await makeButton(d1Foot, "Connect Source", { fontSize: 12, px: 12, bg: "#1c1c1c", color: "#ffffff" });

  // Modal 2: Destructive Confirm Dialog
  const diag2 = await makeFrame({
    parentId: diagGrid,
    name: "Confirm Dialog",
    bg: "#ffffff",
    border: "#f8a59b",
    radius: 12,
    layout: { mode: "VERTICAL", px: 24, py: 20, gap: 14 },
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });
  const d2Head = await makeFrame({ parentId: diag2, name: "head", layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" }, sizing: { w: "FILL", h: "HUG" } });
  await makeBadge(d2Head, "⚠", { bg: "#fceae7", color: "#dc2626" });
  await makeText({ parentId: d2Head, text: "Delete Knowledge Partition?", size: 15, weight: "Bold", color: "#dc2626" });
  await makeText({ parentId: diag2, text: "Are you sure you want to delete this partition? This will permanently delete 14,200 indexed embeddings and cannot be undone.", size: 12, weight: "Regular", color: "#555555", lineHeight: 16 });
  const d2Foot = await makeFrame({ parentId: diag2, name: "foot", layout: { mode: "HORIZONTAL", justify: "MAX", gap: 8, align: "CENTER" }, sizing: { w: "FILL", h: "HUG" } });
  await makeButton(d2Foot, "Cancel", { fontSize: 12, px: 12, bg: "#fafafa", border: "#cccccc", color: "#1c1c1c" });
  await makeButton(d2Foot, "Delete Permanently", { fontSize: 12, px: 12, bg: "#dc2626", color: "#ffffff" });

  // Modal 3: Dropdown Menu Popover
  const diag3 = await makeFrame({
    parentId: diagGrid,
    name: "Dropdown Menu",
    bg: "#ffffff",
    border: "#cccccc",
    radius: 8,
    layout: { mode: "VERTICAL", px: 8, py: 8, gap: 4 },
    sizing: { w: "FILL", h: "HUG", grow: 0.8 }
  });

  async function makeMenuItem(parentId, label, isDanger) {
    const item = await makeFrame({
      parentId,
      name: label,
      bg: isDanger ? "#fceae7" : "#fafafa",
      radius: 4,
      layout: { mode: "HORIZONTAL", px: 10, py: 8, align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });
    await makeText({ parentId: item, text: label, size: 12, weight: "Medium", color: isDanger ? "#dc2626" : "#1c1c1c" });
  }

  await makeMenuItem(diag3, "✏️ Edit Connector", false);
  await makeMenuItem(diag3, "🔄 Force Resync", false);
  await makeMenuItem(diag3, "📋 Duplicate Config", false);
  await makeMenuItem(diag3, "🗑️ Remove Source", true);

  console.log("✅ All 10 sections built successfully in Master Canvas!");
}

build().catch(err => {
  console.error("Build error:", err);
  process.exit(1);
});
