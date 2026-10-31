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

async function makeButton(parentId, label, opts = {}) {
  const size = opts.size || "md";
  const prominence = opts.prominence || "primary";
  const tone = opts.tone || "default";

  let bg = "#1c1c1c";
  let border = null;
  let text = "#ffffff";

  if (prominence === "secondary") {
    bg = "#ffffff";
    border = "#cccccc";
    text = "#1c1c1c";
  } else if (prominence === "tertiary") {
    bg = "transparent";
    text = "#1c1c1c";
  } else if (prominence === "internal") {
    bg = "transparent";
    text = "#555555";
  }

  if (tone === "danger") {
    bg = "#dc2626";
    text = "#ffffff";
  }

  const px = size === "sm" ? 8 : (size === "lg" ? 14 : 12);
  const py = size === "sm" ? 5 : (size === "lg" ? 9 : 7);
  const fontSize = size === "sm" ? 12 : (size === "lg" ? 14 : 13);

  const btn = await makeFrame({
    parentId,
    name: `<Button prominence="${prominence}">`,
    bg,
    border,
    radius: 8,
    layout: { mode: "HORIZONTAL", justify: "CENTER", align: "CENTER", px, py, gap: 6 },
    sizing: { w: "HUG", h: "HUG" }
  });

  if (opts.icon) {
    await makeText({ parentId: btn, text: opts.icon, size: fontSize, weight: "Medium", color: text });
  }

  if (label) {
    await makeText({ parentId: btn, text: label, size: fontSize, weight: "SemiBold", color: text });
  }

  return btn;
}

async function makeBadge(parentId, label, opts = {}) {
  const tone = opts.tone || "neutral";
  const isPill = opts.pill || false;

  const tones = {
    success: { bg: "#e6f2e7", border: "#91d099", text: "#007a2d", dot: "#00a43f", pillBg: "#00a43f" },
    warning: { bg: "#fbeae4", border: "#f5a88b", text: "#b54204", dot: "#ec5b13", pillBg: "#ec5b13" },
    danger: { bg: "#fceae7", border: "#f8a59b", text: "#c12222", dot: "#dc2626", pillBg: "#dc2626" },
    info: { bg: "#e7effc", border: "#9bbeff", text: "#245fda", dot: "#286df8", pillBg: "#286df8" },
    neutral: { bg: "#f0f0f1", border: "#cccccc", text: "#555555", dot: "#808080", pillBg: "#555555" }
  };
  const t = tones[tone] || tones.neutral;

  const badge = await makeFrame({
    parentId,
    name: `<StatusBadge tone="${tone}">`,
    bg: isPill ? t.pillBg : t.bg,
    border: isPill ? null : t.border,
    radius: isPill ? 12 : 6,
    layout: { mode: "HORIZONTAL", justify: "CENTER", align: "CENTER", px: 8, py: 2.5, gap: 5 },
    sizing: { w: "HUG", h: "HUG" }
  });

  if (opts.dot && !isPill) {
    await makeFrame({ parentId: badge, name: "dot", width: 6, height: 6, bg: t.dot, radius: 3, sizing: { w: "FIXED", h: "FIXED" } });
  }

  await makeText({ parentId: badge, text: label, size: 11, weight: isPill ? "SemiBold" : "Medium", color: isPill ? "#ffffff" : t.text });
  return badge;
}

// =========================================================================
// Exact App Shell Sidebar Builder (web/src/components/app-shell/app-shell.tsx & chat-navigation.tsx)
// =========================================================================
async function makeExactAppSidebar(parentId, activeRoute = "search") {
  const sb = await makeFrame({
    parentId,
    name: "<Sidebar>",
    width: 260,
    height: 900,
    bg: "#f0f0f1",
    border: "#e6e6e6",
    layout: { mode: "VERTICAL", px: 12, py: 14, justify: "SPACE_BETWEEN" },
    sizing: { w: "FIXED", h: "FILL" }
  });

  const scrollArea = await makeFrame({
    parentId: sb,
    name: "<SidebarContent>",
    layout: { mode: "VERTICAL", gap: 14 },
    sizing: { w: "FILL", h: "HUG" }
  });

  // 1. Sidebar Header (Brand + Icons)
  const header = await makeFrame({
    parentId: scrollArea,
    name: "<SidebarHeader>",
    layout: { mode: "HORIZONTAL", justify: "SPACE_BETWEEN", align: "CENTER", px: 4 },
    sizing: { w: "FILL", h: "HUG" }
  });

  const brand = await makeFrame({ parentId: header, name: "<Brand>", layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" }, sizing: { w: "HUG", h: "HUG" } });
  const logoSquare = await makeFrame({ parentId: brand, name: "logo", width: 26, height: 26, bg: "#1c1c1c", radius: 6, layout: { mode: "HORIZONTAL", justify: "CENTER", align: "CENTER" }, sizing: { w: "FIXED", h: "FIXED" } });
  await makeText({ parentId: logoSquare, text: "M", size: 13, weight: "Bold", color: "#ffffff" });
  await makeText({ parentId: brand, text: "MemoryOS", size: 15, weight: "Bold", color: "#1c1c1c" });

  const headIcons = await makeFrame({ parentId: header, name: "icons", layout: { mode: "HORIZONTAL", gap: 4, align: "CENTER" }, sizing: { w: "HUG", h: "HUG" } });
  await makeButton(headIcons, "🔍", { prominence: "tertiary", size: "sm" });
  await makeButton(headIcons, "◧", { prominence: "tertiary", size: "sm" });

  // 2. Primary Navigation Links (SidebarMenu)
  const menu1 = await makeFrame({ parentId: scrollArea, name: "<SidebarMenu>", layout: { mode: "VERTICAL", gap: 2 }, sizing: { w: "FILL", h: "HUG" } });

  async function makeSidebarRow(parentId, icon, label, routeKey, count = null) {
    const isActive = activeRoute === routeKey;
    const row = await makeFrame({
      parentId,
      name: `<SidebarLink to="/${routeKey}">`,
      bg: isActive ? "#ffffff" : "transparent",
      border: isActive ? "#e6e6e6" : null,
      radius: 8,
      layout: { mode: "HORIZONTAL", px: 10, py: 7, justify: "SPACE_BETWEEN", align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });

    const left = await makeFrame({ parentId: row, name: "left", layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" }, sizing: { w: "HUG", h: "HUG" } });
    await makeText({ parentId: left, text: icon, size: 13, weight: "Regular", color: isActive ? "#1c1c1c" : "#808080" });
    await makeText({ parentId: left, text: label, size: 13, weight: isActive ? "SemiBold" : "Medium", color: isActive ? "#1c1c1c" : "#555555" });

    if (count) {
      await makeBadge(row, count, { tone: "neutral", pill: true });
    }
  }

  await makeSidebarRow(menu1, "＋", "Hội thoại mới", "new-chat");
  await makeSidebarRow(menu1, "🔍", "Search documents", "search");
  await makeSidebarRow(menu1, "🤖", "Trợ lý (Agents)", "agents");
  await makeSidebarRow(menu1, "📅", "Meetings", "meetings");
  await makeSidebarRow(menu1, "📂", "Thư viện (Library)", "library");

  // 3. Pinned Agents Section (<PinnedAgents>)
  const pinSec = await makeFrame({ parentId: scrollArea, name: "<PinnedAgents>", layout: { mode: "VERTICAL", gap: 4 }, sizing: { w: "FILL", h: "HUG" } });
  await makeText({ parentId: pinSec, text: "TRỢ LÝ ĐÃ GHIM", size: 10, weight: "Bold", color: "#808080" });

  async function makePinnedAgentRow(parentId, name, icon) {
    const row = await makeFrame({
      parentId,
      name: name,
      bg: "transparent",
      radius: 6,
      layout: { mode: "HORIZONTAL", px: 8, py: 6, gap: 8, align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });
    await makeText({ parentId: row, text: "⋮⋮", size: 11, weight: "Regular", color: "#a4a4a4" });
    const av = await makeFrame({ parentId: row, name: "av", width: 20, height: 20, bg: "#e6e6e9", radius: 10, layout: { mode: "HORIZONTAL", justify: "CENTER", align: "CENTER" }, sizing: { w: "FIXED", h: "FIXED" } });
    await makeText({ parentId: av, text: icon, size: 11, weight: "Regular", color: "#1c1c1c" });
    await makeText({ parentId: row, text: name, size: 12, weight: "Medium", color: "#333333", sizing: { w: "FILL", h: "HUG", grow: 1 } });
    await makeText({ parentId: row, text: "📌", size: 10, weight: "Regular", color: "#808080" });
  }

  await makePinnedAgentRow(pinSec, "Research Substrate", "🔬");
  await makePinnedAgentRow(pinSec, "Backend Codebase Navigator", "⚡");

  // 4. Projects Section (<ProjectFolder>)
  const projSec = await makeFrame({ parentId: scrollArea, name: "<ProjectSection>", layout: { mode: "VERTICAL", gap: 4 }, sizing: { w: "FILL", h: "HUG" } });
  const projHead = await makeFrame({ parentId: projSec, name: "head", layout: { mode: "HORIZONTAL", justify: "SPACE_BETWEEN", align: "CENTER", px: 4 }, sizing: { w: "FILL", h: "HUG" } });
  await makeText({ parentId: projHead, text: "DỰ ÁN", size: 10, weight: "Bold", color: "#808080" });
  await makeText({ parentId: projHead, text: "+", size: 13, weight: "Bold", color: "#555555" });

  async function makeProjectItem(parentId, name) {
    const row = await makeFrame({ parentId, name: name, bg: "transparent", radius: 6, layout: { mode: "HORIZONTAL", px: 8, py: 5, gap: 8, align: "CENTER" }, sizing: { w: "FILL", h: "HUG" } });
    await makeText({ parentId: row, text: "📁", size: 12, weight: "Regular", color: "#808080" });
    await makeText({ parentId: row, text: name, size: 12, weight: "Medium", color: "#333333", sizing: { w: "FILL", h: "HUG", grow: 1 } });
  }

  await makeProjectItem(projSec, "MemoryOS Core Engine");
  await makeProjectItem(projSec, "Vector Pipeline Ingestion");

  // 5. Recent Conversations (<ThreadListConversations>)
  const recentSec = await makeFrame({ parentId: scrollArea, name: "<ThreadListConversations>", layout: { mode: "VERTICAL", gap: 3 }, sizing: { w: "FILL", h: "HUG" } });
  await makeText({ parentId: recentSec, text: "HỘI THOẠI GẦN ĐÂY", size: 10, weight: "Bold", color: "#808080" });

  async function makeThreadRow(parentId, title, isActive = false) {
    const row = await makeFrame({
      parentId,
      name: title,
      bg: isActive ? "#ffffff" : "transparent",
      border: isActive ? "#e6e6e6" : null,
      radius: 6,
      layout: { mode: "HORIZONTAL", px: 8, py: 6, justify: "SPACE_BETWEEN", align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });
    await makeText({ parentId: row, text: title, size: 12, weight: isActive ? "SemiBold" : "Regular", color: isActive ? "#1c1c1c" : "#555555", sizing: { w: "FILL", h: "HUG", grow: 1 } });
    await makeText({ parentId: row, text: "···", size: 12, weight: "Bold", color: "#808080" });
  }

  await makeThreadRow(recentSec, "Data Ingestion & Retrieval Pipeline", activeRoute === "chat");
  await makeThreadRow(recentSec, "Benchmark Vector Cosine Similarity");
  await makeThreadRow(recentSec, "Markdown Vault Indexing Setup");

  // 6. Sidebar Footer (<SidebarFooter>)
  const footer = await makeFrame({
    parentId: sb,
    name: "<SidebarFooter>",
    border: "#e6e6e6",
    layout: { mode: "VERTICAL", pt: 8, gap: 6 },
    sizing: { w: "FILL", h: "HUG" }
  });

  const adminLink = await makeFrame({ parentId: footer, name: "admin-link", layout: { mode: "HORIZONTAL", px: 8, py: 6, gap: 8, align: "CENTER" }, sizing: { w: "FILL", h: "HUG" } });
  await makeText({ parentId: adminLink, text: "⚙️", size: 13, weight: "Regular", color: "#808080" });
  await makeText({ parentId: adminLink, text: "Admin Panel", size: 12, weight: "Medium", color: "#555555" });

  const userAccount = await makeFrame({ parentId: footer, name: "<AccountMenu>", bg: "#ffffff", border: "#e6e6e6", radius: 8, layout: { mode: "HORIZONTAL", px: 8, py: 6, justify: "SPACE_BETWEEN", align: "CENTER" }, sizing: { w: "FILL", h: "HUG" } });
  const uLeft = await makeFrame({ parentId: userAccount, name: "u-left", layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" }, sizing: { w: "HUG", h: "HUG" } });
  const uAv = await makeFrame({ parentId: uLeft, name: "u-av", width: 24, height: 24, bg: "#1c1c1c", radius: 12, layout: { mode: "HORIZONTAL", justify: "CENTER", align: "CENTER" }, sizing: { w: "FIXED", h: "FIXED" } });
  await makeText({ parentId: uAv, text: "MO", size: 10, weight: "Bold", color: "#ffffff" });
  await makeText({ parentId: uLeft, text: "Admin User", size: 12, weight: "SemiBold", color: "#1c1c1c" });
  await makeText({ parentId: userAccount, text: "▾", size: 11, weight: "Regular", color: "#808080" });

  return sb;
}

// Topbar Header (<AppShellHeader>)
async function makeAppHeader(parentId, title, actions = []) {
  const header = await makeFrame({
    parentId,
    name: "<header role='banner' class='AppShellHeader'>",
    bg: "#ffffff",
    border: "#e6e6e6",
    layout: { mode: "HORIZONTAL", px: 28, py: 12, justify: "SPACE_BETWEEN", align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });

  await makeText({ parentId: header, text: title, size: 16, weight: "Bold", color: "#1c1c1c" });

  const actBox = await makeFrame({ parentId: header, name: "actions", layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" }, sizing: { w: "HUG", h: "HUG" } });
  for (const act of actions) {
    if (act.type === "badge") {
      await makeBadge(actBox, act.label, { tone: act.tone || "neutral", dot: act.dot, pill: act.pill });
    } else if (act.type === "button") {
      await makeButton(actBox, act.label, { prominence: act.prominence || "secondary", size: "sm", icon: act.icon });
    }
  }

  return header;
}

async function main() {
  console.log("=== Building 100% Code-Faithful Search & Chat Pages on Figma ===");

  const targetPage = "0:1";

  // Clean current canvas
  const scanned = await callTool("scan_nodes_by_types", { types: ["FRAME"] });
  const allNodes = Array.isArray(scanned) ? scanned : (scanned.nodes || []);
  const rootChildren = allNodes.filter(n => n.parentId === targetPage);
  if (rootChildren.length > 0) {
    console.log(`Clearing ${rootChildren.length} existing nodes on page memoryos-app...`);
    await callTool("delete_nodes", { nodeIds: rootChildren.map(n => n.id) });
  }

  // =========================================================================
  // 1. SEARCH PAGE (web/src/features/search/search-page.tsx)
  // =========================================================================
  console.log("Rendering 1. Search Page...");
  const searchApp = await makeFrame({
    parentId: targetPage,
    name: "MemoryOS — Search Screen (Code-Exact)",
    x: 0,
    y: 0,
    width: 1440,
    height: 900,
    bg: "#fafafa",
    layout: { mode: "HORIZONTAL" },
    sizing: { w: "FIXED", h: "FIXED" }
  });

  // Sidebar
  await makeExactAppSidebar(searchApp, "search");

  // Main Inset
  const searchMain = await makeFrame({
    parentId: searchApp,
    name: "<SidebarInset>",
    bg: "#fafafa",
    layout: { mode: "VERTICAL" },
    sizing: { w: "FILL", h: "FILL", grow: 1 }
  });

  // Header
  await makeAppHeader(searchMain, "Search documents", [
    { type: "badge", label: "24 documents indexed", tone: "success", dot: true },
    { type: "button", label: "Refresh Index", prominence: "secondary", icon: "🔄" }
  ]);

  // Scrollable Content
  const searchScroll = await makeFrame({
    parentId: searchMain,
    name: "<main id='main-content'>",
    layout: { mode: "VERTICAL", px: 40, py: 32, gap: 24 },
    sizing: { w: "FILL", h: "FILL", grow: 1 }
  });

  // SearchBox Card (<SearchBox>)
  const searchBox = await makeFrame({
    parentId: searchScroll,
    name: "<SearchBox>",
    bg: "#ffffff",
    border: "#cccccc",
    radius: 16,
    layout: { mode: "VERTICAL" },
    sizing: { w: "FILL", h: "HUG" }
  });

  const inputRow = await makeFrame({
    parentId: searchBox,
    name: "input-row",
    layout: { mode: "HORIZONTAL", px: 16, py: 12, gap: 10, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeText({ parentId: inputRow, text: "🔍", size: 16, weight: "Regular", color: "#808080" });
  await makeText({
    parentId: inputRow,
    text: "vector retrieval and memory substrate architecture",
    size: 15,
    weight: "Regular",
    color: "#1c1c1c",
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });
  await makeButton(inputRow, "✕", { prominence: "tertiary", size: "sm" });
  await makeButton(inputRow, "🎙️", { prominence: "tertiary", size: "sm" });
  await makeButton(inputRow, "Search", { prominence: "primary", size: "md" });

  // Filters inside SearchBox (<SearchFilters>)
  const filtersRow = await makeFrame({
    parentId: searchBox,
    name: "<SearchFilters>",
    border: "#f0f0f1",
    layout: { mode: "HORIZONTAL", px: 16, py: 10, justify: "SPACE_BETWEEN", align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });

  const fLeft = await makeFrame({ parentId: filtersRow, name: "filters-left", layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" }, sizing: { w: "HUG", h: "HUG" } });
  await makeText({ parentId: fLeft, text: "⚙️ Filters:", size: 12, weight: "Bold", color: "#808080" });
  await makeBadge(fLeft, "Updated: All time ▼", { tone: "neutral" });
  await makeBadge(fLeft, "File type: All types ▼", { tone: "neutral" });
  await makeBadge(fLeft, "Bộ tài liệu: Tất cả ▼", { tone: "neutral" });

  await makeButton(filtersRow, "Clear filters", { prominence: "tertiary", size: "sm" });

  // Search Body Grid (SearchSourceRail + SearchResults)
  const searchGrid = await makeFrame({
    parentId: searchScroll,
    name: "Search Layout (Rail + Results)",
    layout: { mode: "HORIZONTAL", gap: 28, align: "MIN" },
    sizing: { w: "FILL", h: "FILL", grow: 1 }
  });

  // Left Filter Rail (<SearchSourceRail>)
  const rail = await makeFrame({
    parentId: searchGrid,
    name: "<SearchSourceRail>",
    width: 220,
    bg: "#ffffff",
    border: "#e6e6e6",
    radius: 12,
    layout: { mode: "VERTICAL", px: 14, py: 14, gap: 6 },
    sizing: { w: "FIXED", h: "HUG" }
  });
  await makeText({ parentId: rail, text: "SOURCE FACETS", size: 10, weight: "Bold", color: "#808080" });

  async function makeSourceOption(parentId, label, count, isSelected) {
    const opt = await makeFrame({
      parentId,
      name: label,
      bg: isSelected ? "#f0f0f1" : "transparent",
      radius: 6,
      layout: { mode: "HORIZONTAL", px: 10, py: 7, justify: "SPACE_BETWEEN", align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });
    await makeText({ parentId: opt, text: label, size: 12, weight: isSelected ? "SemiBold" : "Medium", color: isSelected ? "#1c1c1c" : "#555555" });
    await makeBadge(opt, count, { tone: "neutral", pill: true });
  }

  await makeSourceOption(rail, "All sources", "24", true);
  await makeSourceOption(rail, "Google Drive", "10", false);
  await makeSourceOption(rail, "SharePoint", "8", false);
  await makeSourceOption(rail, "File Uploads", "4", false);
  await makeSourceOption(rail, "Web Search", "2", false);

  // Right Search Results (<SearchResults>)
  const resultsCol = await makeFrame({
    parentId: searchGrid,
    name: "<SearchResults>",
    layout: { mode: "VERTICAL", gap: 14 },
    sizing: { w: "FILL", h: "FILL", grow: 1 }
  });

  const resHeader = await makeFrame({
    parentId: resultsCol,
    name: "results-header",
    border: "#e6e6e6",
    layout: { mode: "HORIZONTAL", justify: "SPACE_BETWEEN", align: "CENTER", pb: 8 },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeText({ parentId: resHeader, text: "24 results for “vector retrieval and memory substrate architecture”", size: 13, weight: "Bold", color: "#1c1c1c" });

  async function makeExactResultCard(parentId, docTypeIcon, docTypeBg, title, metaLine, bestSnippet, subSnippet) {
    const card = await makeFrame({
      parentId,
      name: `<SearchResultCard title="${title}">`,
      bg: "#ffffff",
      border: "#e6e6e6",
      radius: 12,
      layout: { mode: "HORIZONTAL", px: 18, py: 16, gap: 14, align: "MIN" },
      sizing: { w: "FILL", h: "HUG" }
    });

    const iconTile = await makeFrame({ parentId: card, name: "doc-icon", width: 36, height: 36, bg: docTypeBg, radius: 8, layout: { mode: "HORIZONTAL", justify: "CENTER", align: "CENTER" }, sizing: { w: "FIXED", h: "FIXED" } });
    await makeText({ parentId: iconTile, text: docTypeIcon, size: 16, weight: "Regular", color: "#1c1c1c" });

    const body = await makeFrame({ parentId: card, name: "doc-body", layout: { mode: "VERTICAL", gap: 8 }, sizing: { w: "FILL", h: "HUG", grow: 1 } });
    await makeText({ parentId: body, text: title, size: 15, weight: "Bold", color: "#1c1c1c" });
    await makeText({ parentId: body, text: metaLine, size: 12, weight: "Regular", color: "#808080" });

    const snippetBox = await makeFrame({ parentId: body, name: "best-snippet", bg: "#fef9c3", radius: 6, layout: { mode: "VERTICAL", px: 12, py: 8 }, sizing: { w: "FILL", h: "HUG" } });
    await makeText({ parentId: snippetBox, text: bestSnippet, size: 13, weight: "Regular", color: "#1c1c1c", lineHeight: 18 });

    if (subSnippet) {
      const subBox = await makeFrame({ parentId: body, name: "sub-snippet", layout: { mode: "HORIZONTAL", gap: 6, align: "CENTER" }, sizing: { w: "FILL", h: "HUG" } });
      await makeText({ parentId: subBox, text: "↳", size: 12, weight: "Bold", color: "#808080" });
      await makeText({ parentId: subBox, text: subSnippet, size: 12, weight: "Regular", color: "#555555" });
    }
  }

  await makeExactResultCard(
    resultsCol,
    "📄",
    "#e7effc",
    "ADR-0002_Zero_Speculative_Operational_Surfaces.md",
    "Markdown Document · Google Drive · Admin User · Updated 2026-10-07",
    "...MemoryOS adheres strictly to dense vector retrieval with cosine distance scoring. Zero-speculative ingestion ensures all tokens are indexed into the memory substrate...",
    "Section 4.2: Embedding pipeline and cosine ranking criteria..."
  );

  await makeExactResultCard(
    resultsCol,
    "📑",
    "#fbeae4",
    "Quarterly_Ingestion_Policy_2026.pdf",
    "PDF Document · SharePoint · Admin User · Updated yesterday",
    "...Context chunking pipeline splits documents into 512-token segments with episodic reranking during vector retrieval...",
    "Section 2.1: Automated ingestion trigger thresholds..."
  );

  await makeExactResultCard(
    resultsCol,
    "📊",
    "#e6f2e7",
    "Retrieval_Benchmark_Evaluation.csv",
    "CSV Sheet · File Uploads · Updated 3 days ago",
    "...Benchmark evaluation indicates hybrid BM25 + dense retrieval outperforms pure semantic search by 18.4% on customer queries...",
    null
  );

  // Pagination (<TablePagination>)
  const pg = await makeFrame({ parentId: resultsCol, name: "<TablePagination>", bg: "#fafafa", border: "#e6e6e6", radius: 8, layout: { mode: "HORIZONTAL", px: 14, py: 8, justify: "SPACE_BETWEEN", align: "CENTER" }, sizing: { w: "FILL", h: "HUG" } });
  await makeText({ parentId: pg, text: "Showing 1–3 of 24 documents", size: 12, weight: "Regular", color: "#808080" });
  const pBtns = await makeFrame({ parentId: pg, name: "p-btns", layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" }, sizing: { w: "HUG", h: "HUG" } });
  await makeButton(pBtns, "Previous", { prominence: "secondary", size: "sm" });
  await makeButton(pBtns, "Next Page", { prominence: "primary", size: "sm" });

  // =========================================================================
  // 2. CHAT PAGE (web/src/features/chat/chat-page.tsx)
  // =========================================================================
  console.log("Rendering 2. Chat Page...");
  const chatApp = await makeFrame({
    parentId: targetPage,
    name: "MemoryOS — Chat Screen (Code-Exact)",
    x: 1540,
    y: 0,
    width: 1440,
    height: 900,
    bg: "#ffffff",
    layout: { mode: "HORIZONTAL" },
    sizing: { w: "FIXED", h: "FIXED" }
  });

  // Sidebar
  await makeExactAppSidebar(chatApp, "chat");

  // Main Inset
  const chatMain = await makeFrame({
    parentId: chatApp,
    name: "<SidebarInset>",
    bg: "#ffffff",
    layout: { mode: "VERTICAL" },
    sizing: { w: "FILL", h: "FILL", grow: 1 }
  });

  // Header (<AppShellHeader>)
  await makeAppHeader(chatMain, "Data Ingestion & Retrieval Pipeline", [
    { type: "badge", label: "Permanent Chat", tone: "info" },
    { type: "button", label: "🔍", prominence: "tertiary" },
    { type: "button", label: "⚙️ Settings", prominence: "secondary" }
  ]);

  // Viewport (<ChatThread Viewport>)
  const chatViewport = await makeFrame({
    parentId: chatMain,
    name: "<ChatThread Viewport>",
    layout: { mode: "VERTICAL", px: 48, py: 24, justify: "SPACE_BETWEEN" },
    sizing: { w: "FILL", h: "FILL", grow: 1 }
  });

  // Messages Area (Centered max-w-3xl)
  const threadArea = await makeFrame({
    parentId: chatViewport,
    name: "<ThreadPrimitive.Messages>",
    layout: { mode: "VERTICAL", gap: 24 },
    sizing: { w: "FILL", h: "HUG" }
  });

  // 1. User Message (<UserMessage>)
  const uMsgRow = await makeFrame({
    parentId: threadArea,
    name: "<UserMessage>",
    layout: { mode: "HORIZONTAL", justify: "MAX" },
    sizing: { w: "FILL", h: "HUG" }
  });
  const uBubble = await makeFrame({
    parentId: uMsgRow,
    name: "<ChatUserMessageContent>",
    bg: "#1c1c1c",
    radius: 14,
    layout: { mode: "VERTICAL", px: 16, py: 12 },
    sizing: { w: "HUG", h: "HUG" }
  });
  await makeText({
    parentId: uBubble,
    text: "How does MemoryOS handle chunking and hybrid retrieval for markdown vaults?",
    size: 14,
    weight: "Regular",
    color: "#ffffff",
    lineHeight: 20
  });

  // 2. Assistant Message (<AssistantMessage>)
  const aMsgRow = await makeFrame({
    parentId: threadArea,
    name: "<AssistantMessage>",
    layout: { mode: "HORIZONTAL", gap: 14, align: "MIN" },
    sizing: { w: "FILL", h: "HUG" }
  });

  const aAvatar = await makeFrame({ parentId: aMsgRow, name: "avatar", width: 32, height: 32, bg: "#286df8", radius: 16, layout: { mode: "HORIZONTAL", justify: "CENTER", align: "CENTER" }, sizing: { w: "FIXED", h: "FIXED" } });
  await makeText({ parentId: aAvatar, text: "✨", size: 14, weight: "Regular", color: "#ffffff" });

  const aContent = await makeFrame({ parentId: aMsgRow, name: "content", layout: { mode: "VERTICAL", gap: 12 }, sizing: { w: "FILL", h: "HUG", grow: 1 } });

  // Activity Group (<ChatActivityGroup>)
  const actPill = await makeFrame({
    parentId: aContent,
    name: "<ChatActivityGroup>",
    bg: "#f0f0f1",
    border: "#cccccc",
    radius: 8,
    layout: { mode: "HORIZONTAL", px: 12, py: 6, gap: 8, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeText({ parentId: actPill, text: "⚡ Synthesized 2 memory vectors from ADR-0002.md & retrieval_policy.md (0.32s)", size: 12, weight: "Medium", color: "#555555" });

  // Markdown Answer (<MarkdownText>)
  const mdText = await makeFrame({
    parentId: aContent,
    name: "<MarkdownText>",
    layout: { mode: "VERTICAL", gap: 8 },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeText({
    parentId: mdText,
    text: "MemoryOS processes connected sources through a deterministic three-stage pipeline:",
    size: 14,
    weight: "Regular",
    color: "#1c1c1c",
    lineHeight: 22
  });
  await makeText({
    parentId: mdText,
    text: "1. **HNSW Dense Vector Search**: All Markdown text chunks are embedded with 3072-dimensional cosine embeddings via text-embedding-3-large [1].\n2. **Lexical BM25 Fusion**: Reciprocal Rank Fusion (RRF) blends keyword exact-matches with dense semantic vectors.\n3. **Citation Provenance**: Every generated insight links directly to verifiable source chunks with exact line offsets [2].",
    size: 14,
    weight: "Regular",
    color: "#1c1c1c",
    lineHeight: 22
  });

  // Sources (<ChatSources>)
  const sourcesRow = await makeFrame({
    parentId: aContent,
    name: "<ChatSources>",
    layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeBadge(sourcesRow, "📄 [1] ADR-0002.md (lines 45-89)", { tone: "info" });
  await makeBadge(sourcesRow, "📄 [2] retrieval_policy.md (lines 12-40)", { tone: "info" });

  // Action Bar (<ActionBarPrimitive>)
  const bar = await makeFrame({
    parentId: aContent,
    name: "<ActionBarPrimitive.Root>",
    layout: { mode: "HORIZONTAL", gap: 12, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeButton(bar, "Copy", { prominence: "tertiary", size: "sm", icon: "📋" });
  await makeButton(bar, "Read Aloud", { prominence: "tertiary", size: "sm", icon: "🔊" });
  await makeText({ parentId: bar, text: "Generated in 1.4s • 342 tokens", size: 11, weight: "Regular", color: "#808080" });

  // Floating Composer (<ChatThreadComposer>)
  const composerBox = await makeFrame({
    parentId: chatViewport,
    name: "<ChatComposerRoot>",
    bg: "#ffffff",
    border: "#cccccc",
    radius: 16,
    layout: { mode: "VERTICAL", px: 16, py: 12, gap: 10 },
    sizing: { w: "FILL", h: "HUG" }
  });

  await makeText({
    parentId: composerBox,
    text: "Nhập câu hỏi… (type @ for agents, tools or specific sources)",
    size: 14,
    weight: "Regular",
    color: "#808080"
  });

  const cToolbar = await makeFrame({
    parentId: composerBox,
    name: "composer-toolbar",
    layout: { mode: "HORIZONTAL", justify: "SPACE_BETWEEN", align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });

  const cLeft = await makeFrame({ parentId: cToolbar, name: "<ChatComposerMenu>", layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" }, sizing: { w: "HUG", h: "HUG" } });
  await makeButton(cLeft, "＋", { prominence: "secondary", size: "sm" });
  await makeBadge(cLeft, "🌐 Web Search", { tone: "neutral" });
  await makeBadge(cLeft, "🔬 Deep Research", { tone: "neutral" });

  const cRight = await makeFrame({ parentId: cToolbar, name: "c-right", layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" }, sizing: { w: "HUG", h: "HUG" } });
  await makeBadge(cRight, "Gemini 1.5 Pro ▼", { tone: "neutral" });
  await makeButton(cRight, "🎙️", { prominence: "tertiary", size: "sm" });
  const sendBtn = await makeFrame({ parentId: cRight, name: "<ChatComposerSend>", width: 32, height: 32, bg: "#1c1c1c", radius: 16, layout: { mode: "HORIZONTAL", justify: "CENTER", align: "CENTER" }, sizing: { w: "FIXED", h: "FIXED" } });
  await makeText({ parentId: sendBtn, text: "↑", size: 14, weight: "Bold", color: "#ffffff" });

  console.log("=========================================================");
  console.log("🎉 SUCCESS: EXACT MEMORYOS APP SEARCH & CHAT SCREENS BUILT!");
  console.log("=========================================================");
}

main().catch(err => {
  console.error("Execution error:", err);
  process.exit(1);
});
