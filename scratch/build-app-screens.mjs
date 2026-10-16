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

// App Shell Sidebar Builder (260px)
async function makeAppSidebar(parentId, activeRoute = "search") {
  const sb = await makeFrame({
    parentId,
    name: "<Sidebar>",
    width: 260,
    height: 900,
    bg: "#f0f0f1",
    border: "#e6e6e6",
    layout: { mode: "VERTICAL", px: 14, py: 16, justify: "SPACE_BETWEEN" },
    sizing: { w: "FIXED", h: "FILL" }
  });

  const topArea = await makeFrame({
    parentId: sb,
    name: "top-area",
    layout: { mode: "VERTICAL", gap: 16 },
    sizing: { w: "FILL", h: "HUG" }
  });

  // Sidebar Header / Brand
  const brand = await makeFrame({
    parentId: topArea,
    name: "<SidebarHeader>",
    layout: { mode: "HORIZONTAL", gap: 10, align: "CENTER", px: 6 },
    sizing: { w: "FILL", h: "HUG" }
  });
  const logoBox = await makeFrame({ parentId: brand, name: "logo", width: 28, height: 28, bg: "#1c1c1c", radius: 6, layout: { mode: "HORIZONTAL", justify: "CENTER", align: "CENTER" }, sizing: { w: "FIXED", h: "FIXED" } });
  await makeText({ parentId: logoBox, text: "M", size: 14, weight: "Bold", color: "#ffffff" });
  await makeText({ parentId: brand, text: "MemoryOS", size: 15, weight: "Bold", color: "#1c1c1c" });

  async function makeSidebarItem(parentId, icon, label, routeKey, count) {
    const isActive = activeRoute === routeKey;
    const item = await makeFrame({
      parentId,
      name: `<SidebarMenuButton isActive=${isActive}>`,
      bg: isActive ? "#ffffff" : "transparent",
      border: isActive ? "#e6e6e6" : null,
      radius: 8,
      layout: { mode: "HORIZONTAL", px: 10, py: 8, justify: "SPACE_BETWEEN", align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });

    const left = await makeFrame({ parentId: item, name: "left", layout: { mode: "HORIZONTAL", gap: 10, align: "CENTER" }, sizing: { w: "HUG", h: "HUG" } });
    await makeText({ parentId: left, text: icon, size: 14, weight: "Regular", color: isActive ? "#1c1c1c" : "#808080" });
    await makeText({ parentId: left, text: label, size: 13, weight: isActive ? "SemiBold" : "Medium", color: isActive ? "#1c1c1c" : "#555555" });

    if (count) {
      await makeBadge(item, count, { tone: "neutral", pill: true });
    }
  }

  // Section 1: Intelligence
  const sec1 = await makeFrame({ parentId: topArea, name: "<SidebarGroup>", layout: { mode: "VERTICAL", gap: 4 }, sizing: { w: "FILL", h: "HUG" } });
  await makeText({ parentId: sec1, text: "INTELLIGENCE", size: 10, weight: "Bold", color: "#808080" });
  await makeSidebarItem(sec1, "💬", "Chat & Assistant", "chat");
  await makeSidebarItem(sec1, "🔍", "Search Workspace", "search");
  await makeSidebarItem(sec1, "⚡", "Context Streams", "streams", "3");

  // Section 2: Knowledge & Admin
  const sec2 = await makeFrame({ parentId: topArea, name: "<SidebarGroup>", layout: { mode: "VERTICAL", gap: 4 }, sizing: { w: "FILL", h: "HUG" } });
  await makeText({ parentId: sec2, text: "KNOWLEDGE", size: 10, weight: "Bold", color: "#808080" });
  await makeSidebarItem(sec2, "📚", "Document Library", "library", "24");
  await makeSidebarItem(sec2, "🔌", "Connectors", "connectors", "12");
  await makeSidebarItem(sec2, "⚙️", "Settings", "settings");

  // Bottom Footer Profile
  const footer = await makeFrame({
    parentId: sb,
    name: "<SidebarFooter>",
    border: "#e6e6e6",
    layout: { mode: "HORIZONTAL", px: 8, py: 10, gap: 10, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  const avatar = await makeFrame({ parentId: footer, name: "<PersonAvatar>", width: 32, height: 32, bg: "#1c1c1c", radius: 16, layout: { mode: "HORIZONTAL", justify: "CENTER", align: "CENTER" }, sizing: { w: "FIXED", h: "FIXED" } });
  await makeText({ parentId: avatar, text: "MO", size: 11, weight: "Bold", color: "#ffffff" });

  const userMeta = await makeFrame({ parentId: footer, name: "meta", layout: { mode: "VERTICAL", gap: 1 }, sizing: { w: "FILL", h: "HUG", grow: 1 } });
  await makeText({ parentId: userMeta, text: "Admin User", size: 12, weight: "SemiBold", color: "#1c1c1c" });
  await makeText({ parentId: userMeta, text: "admin@memoryos.dev", size: 10, weight: "Regular", color: "#808080" });

  return sb;
}

// Topbar Header Builder
async function makeTopHeader(parentId, title, rightActions) {
  const header = await makeFrame({
    parentId,
    name: "<AppShellHeader>",
    bg: "#ffffff",
    border: "#e6e6e6",
    layout: { mode: "HORIZONTAL", px: 28, py: 14, justify: "SPACE_BETWEEN", align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });

  await makeText({ parentId: header, text: title, size: 16, weight: "Bold", color: "#1c1c1c" });

  const right = await makeFrame({
    parentId: header,
    name: "header-actions",
    layout: { mode: "HORIZONTAL", gap: 10, align: "CENTER" },
    sizing: { w: "HUG", h: "HUG" }
  });

  if (rightActions) {
    for (const act of rightActions) {
      if (act.type === "badge") {
        await makeBadge(right, act.label, { tone: act.tone || "neutral", dot: act.dot });
      } else if (act.type === "button") {
        await makeButton(right, act.label, { prominence: act.prominence || "secondary", size: "sm" });
      }
    }
  }

  return header;
}

async function main() {
  console.log("=== Building MemoryOS Search & Chat Screens on page memoryos-app ===");

  const targetPage = "0:1";

  // Clean current page
  const scanned = await callTool("scan_nodes_by_types", { types: ["FRAME"] });
  const allNodes = Array.isArray(scanned) ? scanned : (scanned.nodes || []);
  const rootChildren = allNodes.filter(n => n.parentId === targetPage);
  if (rootChildren.length > 0) {
    console.log(`Cleaning ${rootChildren.length} existing nodes on page memoryos-app...`);
    await callTool("delete_nodes", { nodeIds: rootChildren.map(n => n.id) });
  }

  // =========================================================================
  // SCREEN 1: SEARCH PAGE (_authenticated.search.tsx / features/search/)
  // =========================================================================
  console.log("Building Screen 1: Search Workspace...");
  const searchScreen = await makeFrame({
    parentId: targetPage,
    name: "MemoryOS — Search Screen",
    x: 0,
    y: 0,
    width: 1440,
    height: 900,
    bg: "#fafafa",
    layout: { mode: "HORIZONTAL" },
    sizing: { w: "FIXED", h: "FIXED" }
  });

  // 1. Sidebar
  await makeAppSidebar(searchScreen, "search");

  // 2. Main Content
  const searchMain = await makeFrame({
    parentId: searchScreen,
    name: "Main Content",
    bg: "#fafafa",
    layout: { mode: "VERTICAL" },
    sizing: { w: "FILL", h: "FILL", grow: 1 }
  });

  // Topbar
  await makeTopHeader(searchMain, "Search Workspace", [
    { type: "badge", label: "Indexed: 24 docs", tone: "success", dot: true },
    { type: "button", label: "Filter Options", prominence: "secondary" }
  ]);

  // Search Body Viewport
  const searchBody = await makeFrame({
    parentId: searchMain,
    name: "Search Viewport",
    layout: { mode: "VERTICAL", px: 36, py: 28, gap: 24 },
    sizing: { w: "FILL", h: "FILL", grow: 1 }
  });

  // Big SearchBox Component
  const searchBoxCard = await makeFrame({
    parentId: searchBody,
    name: "<SearchBox>",
    bg: "#ffffff",
    border: "#cccccc",
    radius: 12,
    layout: { mode: "HORIZONTAL", px: 16, py: 12, gap: 12, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeText({ parentId: searchBoxCard, text: "🔍", size: 16, weight: "Regular", color: "#808080" });
  await makeText({
    parentId: searchBoxCard,
    text: "vector retrieval and memory substrate architecture",
    size: 14,
    weight: "Regular",
    color: "#1c1c1c",
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });
  await makeBadge(searchBoxCard, "All Sources ▼", { tone: "neutral" });
  await makeButton(searchBoxCard, "Search", { prominence: "primary", size: "sm" });

  // Results Layout (Left Rail + Results List)
  const searchGrid = await makeFrame({
    parentId: searchBody,
    name: "Search Layout (Rail + Results)",
    layout: { mode: "HORIZONTAL", gap: 24, align: "MIN" },
    sizing: { w: "FILL", h: "FILL", grow: 1 }
  });

  // Left Filter Rail (<SearchSourceRail>)
  const searchRail = await makeFrame({
    parentId: searchGrid,
    name: "<SearchSourceRail>",
    width: 220,
    bg: "#ffffff",
    border: "#e6e6e6",
    radius: 10,
    layout: { mode: "VERTICAL", px: 14, py: 14, gap: 8 },
    sizing: { w: "FIXED", h: "HUG" }
  });
  await makeText({ parentId: searchRail, text: "SOURCE FACETS", size: 10, weight: "Bold", color: "#808080" });

  async function makeRailOption(parentId, label, count, isSelected) {
    const opt = await makeFrame({
      parentId,
      name: label,
      bg: isSelected ? "#f0f0f1" : "transparent",
      radius: 6,
      layout: { mode: "HORIZONTAL", px: 10, py: 7, justify: "SPACE_BETWEEN", align: "CENTER" },
      sizing: { w: "FILL", h: "HUG" }
    });
    await makeText({ parentId: opt, text: label, size: 12, weight: isSelected ? "SemiBold" : "Regular", color: isSelected ? "#1c1c1c" : "#555555" });
    await makeBadge(opt, count, { tone: "neutral", pill: true });
  }

  await makeRailOption(searchRail, "All Sources", "24", true);
  await makeRailOption(searchRail, "Google Drive", "10", false);
  await makeRailOption(searchRail, "SharePoint", "8", false);
  await makeRailOption(searchRail, "File Uploads", "4", false);
  await makeRailOption(searchRail, "Web Search", "2", false);

  // Right Results List (<SearchResults>)
  const searchResults = await makeFrame({
    parentId: searchGrid,
    name: "<SearchResults>",
    layout: { mode: "VERTICAL", gap: 14 },
    sizing: { w: "FILL", h: "FILL", grow: 1 }
  });

  async function makeResultCard(parentId, title, badgeLabel, badgeTone, excerpt, meta) {
    const card = await makeFrame({
      parentId,
      name: `<SearchResultItem title="${title}">`,
      bg: "#ffffff",
      border: "#e6e6e6",
      radius: 10,
      layout: { mode: "VERTICAL", px: 18, py: 16, gap: 10 },
      sizing: { w: "FILL", h: "HUG" }
    });

    const head = await makeFrame({ parentId: card, name: "head", layout: { mode: "HORIZONTAL", justify: "SPACE_BETWEEN", align: "CENTER" }, sizing: { w: "FILL", h: "HUG" } });
    const titleRow = await makeFrame({ parentId: head, name: "title-row", layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" }, sizing: { w: "FILL", h: "HUG", grow: 1 } });
    await makeText({ parentId: titleRow, text: "📄", size: 14, weight: "Regular", color: "#1c1c1c" });
    await makeText({ parentId: titleRow, text: title, size: 14, weight: "SemiBold", color: "#1c1c1c" });

    await makeBadge(head, badgeLabel, { tone: badgeTone, dot: true });

    const snippetBox = await makeFrame({ parentId: card, name: "snippet", bg: "#f0f0f1", radius: 6, layout: { mode: "VERTICAL", px: 12, py: 8 }, sizing: { w: "FILL", h: "HUG" } });
    await makeText({ parentId: snippetBox, text: excerpt, size: 12, weight: "Regular", color: "#333333", lineHeight: 18 });

    const foot = await makeFrame({ parentId: card, name: "foot", layout: { mode: "HORIZONTAL", justify: "SPACE_BETWEEN", align: "CENTER" }, sizing: { w: "FILL", h: "HUG" } });
    await makeText({ parentId: foot, text: meta, size: 11, weight: "Regular", color: "#808080" });
    await makeButton(foot, "Open Document Preview", { prominence: "secondary", size: "sm" });
  }

  await makeResultCard(
    searchResults,
    "ADR-0002_Zero_Speculative_Operational_Surfaces.md",
    "Markdown Vault",
    "success",
    "...MemoryOS adheres strictly to dense vector retrieval with cosine distance scoring. Zero-speculative ingestion ensures all tokens are indexed deterministically into high-dimensional memory substrate...",
    "Markdown Document • Updated 2 hours ago • 94 chunks indexed"
  );

  await makeResultCard(
    searchResults,
    "Quarterly_Ingestion_Policy_2026.pdf",
    "Google Drive",
    "info",
    "...Context chunking pipeline splits documents into 512-token segments with 64-token overlap. Episodic reranking boosts active context during semantic search...",
    "PDF Document (4.2 MB) • Updated yesterday • 482 chunks indexed"
  );

  await makeResultCard(
    searchResults,
    "Retrieval_Benchmark_Evaluation.csv",
    "SharePoint",
    "warning",
    "...Benchmark evaluation indicates hybrid BM25 + dense retrieval outperforms pure semantic search by 18.4% on structured customer queries...",
    "CSV Sheet (18.5 MB) • Updated 3 days ago • 1,840 chunks indexed"
  );

  // =========================================================================
  // SCREEN 2: CHAT CONVERSATION PAGE (_authenticated._chat.chat.$sessionId.tsx)
  // =========================================================================
  console.log("Building Screen 2: Chat & Assistant...");
  const chatScreen = await makeFrame({
    parentId: targetPage,
    name: "MemoryOS — Chat Screen",
    x: 1540,
    y: 0,
    width: 1440,
    height: 900,
    bg: "#fafafa",
    layout: { mode: "HORIZONTAL" },
    sizing: { w: "FIXED", h: "FIXED" }
  });

  // 1. Sidebar
  await makeAppSidebar(chatScreen, "chat");

  // 2. Main Chat Area
  const chatMain = await makeFrame({
    parentId: chatScreen,
    name: "Main Chat Container",
    bg: "#ffffff",
    layout: { mode: "VERTICAL" },
    sizing: { w: "FILL", h: "FILL", grow: 1 }
  });

  // Chat Header
  await makeTopHeader(chatMain, "Data Ingestion & Retrieval Pipeline", [
    { type: "badge", label: "Gemini 1.5 Pro ▼", tone: "neutral" },
    { type: "badge", label: "Permanent Chat", tone: "info" },
    { type: "button", label: "Settings", prominence: "secondary" }
  ]);

  // Thread Viewport (<ChatThread>)
  const chatViewport = await makeFrame({
    parentId: chatMain,
    name: "<ChatThread Viewport>",
    layout: { mode: "VERTICAL", px: 48, py: 24, justify: "SPACE_BETWEEN" },
    sizing: { w: "FILL", h: "FILL", grow: 1 }
  });

  // Messages List
  const msgList = await makeFrame({
    parentId: chatViewport,
    name: "<ThreadPrimitive.Messages>",
    layout: { mode: "VERTICAL", gap: 20 },
    sizing: { w: "FILL", h: "HUG" }
  });

  // 1. User Message (<UserMessage>)
  const userMsgRow = await makeFrame({
    parentId: msgList,
    name: "<UserMessage>",
    layout: { mode: "HORIZONTAL", justify: "MAX" },
    sizing: { w: "FILL", h: "HUG" }
  });
  const userBubble = await makeFrame({
    parentId: userMsgRow,
    name: "User Bubble",
    bg: "#1c1c1c",
    radius: 12,
    layout: { mode: "VERTICAL", px: 16, py: 12 },
    sizing: { w: "HUG", h: "HUG" }
  });
  await makeText({
    parentId: userBubble,
    text: "Explain how MemoryOS performs hybrid retrieval and how citations are verified against indexed documents.",
    size: 13,
    weight: "Regular",
    color: "#ffffff"
  });

  // 2. Assistant Message (<AssistantMessage>)
  const aiMsgRow = await makeFrame({
    parentId: msgList,
    name: "<AssistantMessage>",
    layout: { mode: "HORIZONTAL", gap: 14, align: "MIN" },
    sizing: { w: "FILL", h: "HUG" }
  });

  const aiAvatar = await makeFrame({
    parentId: aiMsgRow,
    name: "Avatar",
    width: 32,
    height: 32,
    bg: "#286df8",
    radius: 16,
    layout: { mode: "HORIZONTAL", justify: "CENTER", align: "CENTER" },
    sizing: { w: "FIXED", h: "FIXED" }
  });
  await makeText({ parentId: aiAvatar, text: "✨", size: 14, weight: "Regular", color: "#ffffff" });

  const aiBody = await makeFrame({
    parentId: aiMsgRow,
    name: "ai-content-body",
    layout: { mode: "VERTICAL", gap: 12 },
    sizing: { w: "FILL", h: "HUG", grow: 1 }
  });

  // Activity Group (<ChatActivityGroup>)
  const activityBox = await makeFrame({
    parentId: aiBody,
    name: "<ChatActivityGroup>",
    bg: "#f0f0f1",
    border: "#cccccc",
    radius: 6,
    layout: { mode: "HORIZONTAL", px: 12, py: 7, gap: 8, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeText({
    parentId: activityBox,
    text: "⚡ Synthesized 2 memory vectors from ADR-0002.md & retrieval_policy.md (0.32s)",
    size: 11,
    weight: "Medium",
    color: "#555555"
  });

  // Markdown Answer Text
  const answerCard = await makeFrame({
    parentId: aiBody,
    name: "<MarkdownText>",
    layout: { mode: "VERTICAL", gap: 8 },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeText({
    parentId: answerCard,
    text: "MemoryOS uses a hybrid retrieval pipeline that combines dense vector embeddings with lexical BM25 token matching:",
    size: 13,
    weight: "Regular",
    color: "#1c1c1c",
    lineHeight: 20
  });
  await makeText({
    parentId: answerCard,
    text: "1. Dense Retrieval: Queries are embedded via text-embedding-3-large (3072 dimensions) with HNSW cosine distance scoring [1].\n2. Lexical Reranking: Reciprocal Rank Fusion (RRF) blends keyword exact-matches with dense semantic vectors.\n3. Transparent Verification: Every generated insight links directly to verifiable source chunks with exact line offsets [2].",
    size: 13,
    weight: "Regular",
    color: "#1c1c1c",
    lineHeight: 20
  });

  // Citations / Sources (<ChatSources>)
  const citeRow = await makeFrame({
    parentId: aiBody,
    name: "<ChatSources>",
    layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeBadge(citeRow, "📄 [1] ADR-0002.md (lines 45-89)", { tone: "info" });
  await makeBadge(citeRow, "📄 [2] retrieval_policy.md (lines 12-40)", { tone: "info" });

  // Action Bar
  const actBar = await makeFrame({
    parentId: aiBody,
    name: "<ActionBarPrimitive.Root>",
    layout: { mode: "HORIZONTAL", gap: 12, align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });
  await makeButton(actBar, "Copy Response", { prominence: "tertiary", size: "sm" });
  await makeButton(actBar, "Read Aloud 🔊", { prominence: "tertiary", size: "sm" });
  await makeText({ parentId: actBar, text: "Generated in 1.4s • 342 tokens", size: 11, weight: "Regular", color: "#808080" });

  // Bottom Composer Bar (<ChatThreadComposer>)
  const composer = await makeFrame({
    parentId: chatViewport,
    name: "<ChatThreadComposer>",
    bg: "#ffffff",
    border: "#cccccc",
    radius: 14,
    layout: { mode: "VERTICAL", px: 16, py: 12, gap: 10 },
    sizing: { w: "FILL", h: "HUG" }
  });

  await makeText({
    parentId: composer,
    text: "Ask MemoryOS a follow-up question, or type @ to mention specific sources...",
    size: 13,
    weight: "Regular",
    color: "#808080"
  });

  const composerFoot = await makeFrame({
    parentId: composer,
    name: "composer-toolbar",
    layout: { mode: "HORIZONTAL", justify: "SPACE_BETWEEN", align: "CENTER" },
    sizing: { w: "FILL", h: "HUG" }
  });

  const toolLeft = await makeFrame({ parentId: composerFoot, name: "tools-left", layout: { mode: "HORIZONTAL", gap: 8, align: "CENTER" }, sizing: { w: "HUG", h: "HUG" } });
  await makeButton(toolLeft, "＋ Add Context", { prominence: "secondary", size: "sm" });
  await makeBadge(toolLeft, "🌐 Web Search: ON", { tone: "info" });
  await makeBadge(toolLeft, "🔬 Deep Research", { tone: "neutral" });

  const toolRight = await makeFrame({ parentId: composerFoot, name: "tools-right", layout: { mode: "HORIZONTAL", gap: 10, align: "CENTER" }, sizing: { w: "HUG", h: "HUG" } });
  await makeBadge(toolRight, "Gemini 1.5 Pro ▼", { tone: "neutral" });
  await makeButton(toolRight, "↑ Send", { prominence: "primary", size: "sm" });

  console.log("=========================================================");
  console.log("🎉 SUCCESS: BOTH SEARCH & CHAT SCREENS BUILT ON MEMORYOS-APP!");
  console.log("=========================================================");
}

main().catch(err => {
  console.error("Execution error:", err);
  process.exit(1);
});
