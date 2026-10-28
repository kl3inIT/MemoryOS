// Builds the Report 4 section I.2 package diagram as a standalone HTML page with inline SVG.
// Run: node docs/academic/sep490/diagrams/r4-package-diagram.mjs
// Then capture #diagram at 2x into r4-package-diagram.png.
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const W = 1600;
const H = 1010;
const INK = '#2b2b2b';
const LINE = '#6f6a62';
const MONO = "'JetBrains Mono', Consolas, monospace";
const SANS = "Calibri, 'Segoe UI', Arial, sans-serif";

const tone = {
  module: { fill: '#ffffff', tab: '#eeeae4', stroke: '#6f6a62' },
  web: { fill: '#f3f8fb', tab: '#dcebf4', stroke: '#4f7f9c' },
  features: { fill: '#f0f6f1', tab: '#d9eadc', stroke: '#3f7d52' },
  knowledge: { fill: '#f0f3f9', tab: '#dce4f2', stroke: '#4a6597' },
  ai: { fill: '#fbf5ec', tab: '#f1e2c8', stroke: '#a7772d' },
  platform: { fill: '#f6f2f9', tab: '#e6dcef', stroke: '#76598f' },
  adapter: { fill: '#f7f6f3', tab: '#e7e3dc', stroke: '#6f6a62' },
};

const out = [];
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;');

// A UML package: a tab with the name above a body.
function pkg(x, y, w, h, name, t, { size = 15, bold = true, mono = false, stereotype } = {}) {
  const tabW = Math.max(70, name.length * size * (mono ? 0.62 : 0.56) + 24);
  const tabH = size + 12;
  out.push(`<path d="M${x} ${y + tabH} V${y + 4} q0 -4 4 -4 H${x + tabW - 4} q4 0 4 4 V${y + tabH}" fill="${t.tab}" stroke="${t.stroke}" stroke-width="1.4"/>`);
  out.push(`<rect x="${x}" y="${y + tabH}" width="${w}" height="${h - tabH}" rx="3" fill="${t.fill}" stroke="${t.stroke}" stroke-width="1.4"/>`);
  out.push(`<text x="${x + 12}" y="${y + tabH - 7}" font-family="${mono ? MONO : SANS}" font-size="${size}" font-weight="${bold ? 700 : 400}" fill="${INK}">${esc(name)}</text>`);
  if (stereotype) {
    out.push(`<text x="${x + w - 12}" y="${y + tabH + 18}" text-anchor="end" font-family="${SANS}" font-size="12.5" font-style="italic" fill="#6b655c">${esc(stereotype)}</text>`);
  }
  return { x, y, w, h, top: y + tabH };
}

// A row of small packages inside a parent body.
function chip(x, y, w, h, name, t, { mono = true, size = 13, sub } = {}) {
  const tabH = 9;
  out.push(`<path d="M${x} ${y + tabH} V${y + 3} q0 -3 3 -3 H${x + 37} q3 0 3 3 V${y + tabH}" fill="${t.tab}" stroke="${t.stroke}" stroke-width="1.2"/>`);
  out.push(`<rect x="${x}" y="${y + tabH}" width="${w}" height="${h - tabH}" rx="3" fill="#ffffff" stroke="${t.stroke}" stroke-width="1.2"/>`);
  const cy = y + tabH + (h - tabH) / 2 + (sub ? -3 : 5);
  text(x + w / 2, cy, name, { mono, size, anchor: 'middle' });
  if (sub) text(x + w / 2, cy + 18, sub, { size: 12.5, anchor: 'middle', color: '#5d574f' });
}

function chips(names, x, y, w, t, { cols, gap = 10, h = 40, mono = true, size = 13 } = {}) {
  const n = cols ?? names.length;
  const cw = (w - gap * (n - 1)) / n;
  names.forEach((name, i) => {
    const r = Math.floor(i / n);
    const c = i % n;
    chip(x + c * (cw + gap), y + r * (h + gap), cw, h, name, t, { mono, size });
  });
}

function text(x, y, s, { size = 13, mono = false, anchor = 'start', color = INK, italic = false, weight = 400 } = {}) {
  out.push(`<text x="${x}" y="${y}" text-anchor="${anchor}" font-family="${mono ? MONO : SANS}" font-size="${size}" font-weight="${weight}" ${italic ? 'font-style="italic"' : ''} fill="${color}">${esc(s)}</text>`);
}

// A dependency: dashed line with an open arrowhead (UML «use»), with an optional label.
function dep(points, label, { lx, ly, dashed = true, color = LINE, head = 'open' } = {}) {
  const d = points.map((p, i) => `${i ? 'L' : 'M'}${p[0]} ${p[1]}`).join(' ');
  out.push(`<path d="${d}" fill="none" stroke="${color}" stroke-width="1.6" ${dashed ? 'stroke-dasharray="6 4"' : ''} marker-end="url(#${head})"/>`);
  if (label) {
    const [a, b] = [points[points.length - 2], points[points.length - 1]];
    const x = lx ?? (a[0] + b[0]) / 2;
    const y = ly ?? (a[1] + b[1]) / 2;
    const wpx = label.length * 6.6 + 12;
    out.push(`<rect x="${x - wpx / 2}" y="${y - 10}" width="${wpx}" height="18" rx="3" fill="#ffffff"/>`);
    text(x, y + 4, label, { size: 12, anchor: 'middle', color: '#5d574f', italic: true });
  }
}

// A UML class: name compartment (with an optional stereotype), then attribute and operation compartments.
function cls(x, y, w, name, { stereo, compartments = true } = {}) {
  const nameH = stereo ? 36 : 24;
  const h = nameH + (compartments ? 16 : 0);
  out.push(`<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="#ffffff" stroke="${INK}" stroke-width="1.2"/>`);
  if (stereo) text(x + w / 2, y + 14, stereo, { size: 11, anchor: 'middle', color: '#5d574f' });
  text(x + w / 2, y + nameH - 7, name, { mono: true, size: 12, anchor: 'middle', weight: 700, italic: stereo === '«interface»' });
  if (compartments) {
    out.push(`<path d="M${x} ${y + nameH} H${x + w} M${x} ${y + nameH + 8} H${x + w}" stroke="${INK}" stroke-width="1"/>`);
  }
  return { x, y, w, h, cx: x + w / 2, bottom: y + h, mid: y + h / 2 };
}

// ---- Top row: web, api, worker ------------------------------------------------
const web = pkg(30, 20, 470, 200, 'web (React SPA)', tone.web, { stereotype: '«sub-system»' });
chips(['routes', 'features', 'components', 'hooks', 'lib/hey-api', 'i18n'], 46, web.top + 34, 438, tone.web, { cols: 3 });

const api = pkg(530, 20, 600, 200, 'memoryos-api', tone.module, { mono: true, stereotype: '«deployable»' });
chips(['security', 'chat', 'source', 'search', 'meeting', 'mcp', 'users, groups', 'audit, usage'], 546, api.top + 34, 568, tone.module, { cols: 4 });

const worker = pkg(1160, 20, 410, 200, 'memoryos-worker', tone.module, { mono: true, stereotype: '«deployable»' });
chips(['RedisStreamWorker', 'RedisOperationRelay', 'ControlPlaneConfiguration'], 1176, worker.top + 34, 378, tone.module, { cols: 1, h: 34, gap: 8, size: 12.5 });

// ---- memoryos-core with its four layers ---------------------------------------
const core = pkg(30, 290, 1540, 270, 'memoryos-core', tone.module, { mono: true, stereotype: '«library» Spring Modulith capabilities' });
const gy = core.top + 40;
const gh = 170;
const groups = [
  { key: 'features', name: 'Features', x: 50, mods: ['chat', 'meeting', 'ingestion', 'mcp'] },
  { key: 'knowledge', name: 'Knowledge', x: 440, mods: ['library', 'retrieval', 'connector', 'document'] },
  { key: 'ai', name: 'AI', x: 830, mods: ['ai', 'voice', 'usage'] },
  { key: 'platform', name: 'Platform', x: 1220, mods: ['iam', 'audit', 'objectstorage', 'shared'] },
];
const gw = 330;
for (const g of groups) {
  const p = pkg(g.x, gy, gw, gh, g.name, tone[g.key], { size: 15 });
  chips(g.mods, g.x + 16, p.top + 16, gw - 32, tone[g.key], { cols: 2, h: 46, gap: 12, size: 13.5 });
}
// layer dependencies
const mid = gy + 22 + (gh - 22) / 2;
dep([[50 + gw, mid], [440, mid]]);
dep([[440 + gw, mid], [830, mid]]);
dep([[830 + gw, mid], [1220, mid]]);
dep([[215, gy + 27], [215, gy - 14], [995, gy - 14], [995, gy + 27]]);
dep([[605, gy + gh], [605, gy + gh + 14], [1385, gy + gh + 14], [1385, gy + gh]]);

// ---- top row into core ---------------------------------------------------------
dep([[500, 130], [530, 130]]);
text(515, 112, 'HTTP', { anchor: 'middle', size: 12, italic: true, color: '#5d574f' });
dep([[830, 220], [830, 290]], '«use» public API', { lx: 830, ly: 255 });
dep([[1365, 220], [1365, 290]], '«use» public API', { lx: 1365, ly: 255 });

// ---- adapters below core -------------------------------------------------------
const ay = 630;
const adapters = ['memoryos-integration-google', 'memoryos-integration-microsoft', 'memoryos-integration-lark', 'memoryos-extraction'];
const aw = 228;
adapters.forEach((name, i) => {
  const x = 30 + i * (aw + 14);
  chip(x, ay, aw, 70, name, tone.adapter, { size: 12, sub: i < 3 ? ['Drive API, ACL', 'Microsoft Graph', 'Lark Open API'][i] : 'Docling, readers, OCR' });
  dep([[x + aw / 2, ay], [x + aw / 2, 560]], i === 0 ? 'implements adapters' : undefined, { lx: x + aw / 2, ly: 596 });
});

// ---- inset: inside one capability (real classes of chat) ---------------------
const ix = 1010;
const inset = pkg(ix, 590, 560, 405, 'Inside a capability: chat', tone.module, { size: 14, stereotype: 'naming conventions' });
const apiChat = pkg(ix + 16, 644, 250, 222, 'io.memoryos.api.chat', tone.module, { mono: true, size: 12 });
const controller = cls(ix + 30, 680, 222, 'ChatSessionController');
const contract = pkg(ix + 30, 734, 222, 122, 'contract', tone.module, { mono: true, size: 11.5, bold: false });
cls(ix + 42, 766, 198, 'CreateChatSessionRequest', { stereo: '«record»', compartments: false });
cls(ix + 42, 810, 198, 'ChatSessionResponse', { stereo: '«record»', compartments: false });

const coreChat = pkg(ix + 290, 644, 254, 336, 'io.memoryos.chat', tone.features, { mono: true, size: 12 });
const port = cls(ix + 304, 680, 226, 'ChatSessionService', { stereo: '«interface»' });
const session = pkg(ix + 304, 762, 226, 206, 'session', tone.features, { mono: true, size: 11.5, bold: false });
const impl = cls(ix + 316, 798, 202, 'DefaultChatSessionService');
const persistence = pkg(ix + 316, 860, 202, 98, 'persistence', tone.features, { mono: true, size: 11.5, bold: false });
const repo = cls(ix + 328, 896, 178, 'JdbcChatRepository');

dep([[controller.x + controller.w, controller.mid], [port.x, controller.mid]], '«use»', { lx: ix + 278, ly: controller.mid - 14 });
dep([[impl.cx - 40, impl.y], [impl.cx - 40, port.bottom]], undefined, { head: 'tri' });
dep([[impl.cx + 50, impl.bottom], [impl.cx + 50, repo.y]], '«use»', { lx: impl.cx + 50, ly: impl.bottom + 30 });

text(ix + 16, 886, 'Root package: published API (*Service)', { size: 12.5 });
text(ix + 16, 903, 'Subpackage: internal (Default*Service)', { size: 12.5 });
text(ix + 16, 920, 'persistence: Jdbc*Repository, *Entity', { size: 12.5 });
text(ix + 16, 937, 'api: *Controller; contract: *Request, *Response', { size: 12.5 });
text(ix + 16, 956, 'api never imports persistence', { size: 12.5, italic: true, color: '#8a3b2b' });

// ---- legend --------------------------------------------------------------------
text(30, 745, 'Dashed open arrow: «use» dependency. Dashed hollow triangle: realization. Layer arrows summarise the module dependencies listed in Table 2;', { size: 12.5, color: '#5d574f' });
text(30, 763, 'every module may use shared. Out of the capstone scope and not drawn: channels, chat bots, sandbox.', { size: 12.5, color: '#5d574f' });

const svg = `<svg id="diagram" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}">
<defs><marker id="tri" viewBox="0 0 14 14" refX="13" refY="7" markerWidth="13" markerHeight="13" orient="auto-start-reverse"><path d="M1 1 L13 7 L1 13 Z" fill="#ffffff" stroke="${LINE}" stroke-width="1.4"/></marker><marker id="open" viewBox="0 0 12 12" refX="11" refY="6" markerWidth="11" markerHeight="11" orient="auto-start-reverse"><path d="M1 1 L11 6 L1 11" fill="none" stroke="${LINE}" stroke-width="1.6"/></marker></defs>
<rect width="${W}" height="${H}" fill="#ffffff"/>
${out.join('\n')}
</svg>`;

const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>MemoryOS Package Diagram</title>
<style>body{margin:0;background:#fff}svg{display:block}</style></head><body>${svg}</body></html>`;

writeFileSync(fileURLToPath(new URL('./r4-package-diagram.html', import.meta.url)), html);
