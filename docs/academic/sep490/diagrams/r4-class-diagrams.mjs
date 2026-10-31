// Builds the Report 4 section II class diagrams as standalone HTML pages with inline UML SVG,
// in the same style as r4-sequences.mjs.
// Run: node docs/academic/sep490/diagrams/r4-class-diagrams.mjs
// Input: r4-set<N>-class.json (types on a row/col grid, relationships); output: r4-set<N>-class.html.
// Then capture #diagram of each page at 2x into the matching .png.
// Edges between neighbours are straight; others turn in the gutter between columns, and
// realizations turn in the gap between rows so that subtypes share one trunk.
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const INK = '#2b2b2b';
const LINE = '#4d4943';
const MUTED = '#6b655c';
const MONO = "'JetBrains Mono', Consolas, monospace";
const SANS = "Calibri, 'Segoe UI', Arial, sans-serif";
const CHAR = 7.25; // width of one 12px mono character
const GAP_X = 96;
const GAP_Y = 74;
const PAD = 24;
const VIS = { public: '+', private: '-', protected: '#', package: '~' };
const tone = {
  class: { fill: '#ffffff', head: '#f3f1ed', stroke: '#6f6a62' },
  interface: { fill: '#ffffff', head: '#e9eef7', stroke: '#4a6597' },
};

const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
// A long parameter list is elided, as UML allows, to keep the boxes narrow.
const sig = (m) => {
  const full = `${VIS[m.visibility ?? 'public']} ${m.name}(${m.parameters ?? ''}): ${m.returns ?? 'void'}`;
  return full.length > 56 ? `${VIS[m.visibility ?? 'public']} ${m.name}(...): ${m.returns ?? 'void'}` : full;
};

function render(name) {
  const spec = JSON.parse(readFileSync(fileURLToPath(new URL(`${name}.json`, import.meta.url)), 'utf8'));
  const out = [];
  const box = {};
  for (const t of spec.types) {
    const ops = (t.methods ?? []).map(sig);
    const w = Math.max(230, ...[t.label, ...ops].map((s) => s.length * CHAR + 26));
    const headH = t.kind === 'interface' ? 46 : 32;
    box[t.id] = { t, ops, w, headH, h: headH + 10 + ops.length * 18 + 6 };
  }
  const cols = Math.max(...spec.types.map((t) => t.col)) + 1;
  const rows = Math.max(...spec.types.map((t) => t.row)) + 1;
  const colW = Array.from({ length: cols }, (_, c) => Math.max(0, ...spec.types.filter((t) => t.col === c).map((t) => box[t.id].w)));
  const rowH = Array.from({ length: rows }, (_, r) => Math.max(0, ...spec.types.filter((t) => t.row === r).map((t) => box[t.id].h)));
  const colX = colW.map((_, c) => PAD + colW.slice(0, c).reduce((a, b) => a + b, 0) + c * GAP_X);
  const rowY = rowH.map((_, r) => PAD + rowH.slice(0, r).reduce((a, b) => a + b, 0) + r * GAP_Y);
  const W = colX.at(-1) + colW.at(-1) + PAD;
  const H = rowY.at(-1) + rowH.at(-1) + PAD;
  for (const b of Object.values(box)) {
    b.x = colX[b.t.col] + (colW[b.t.col] - b.w) / 2;
    b.y = rowY[b.t.row];
    b.cx = b.x + b.w / 2;
  }
  const gutterX = (c) => colX[c] + colW[c] + GAP_X / 2; // between column c and c+1
  const gapY = (r) => rowY[r] + rowH[r] + GAP_Y / 2; // between row r and r+1

  // relationships first, so boxes and labels sit on top
  const labels = [];
  for (const r of spec.relationships) {
    const a = box[r.from];
    const b = box[r.to];
    const ra = a.t.row;
    const rb = b.t.row;
    const ca = a.t.col;
    const cb = b.t.col;
    let pts;
    if (ra === rb) {
      const y = a.y + Math.min(a.h, b.h) / 2;
      pts = ca < cb ? [[a.x + a.w, y], [b.x, y]] : [[a.x, y], [b.x + b.w, y]];
    } else if (ca === cb) {
      pts = ra < rb ? [[a.cx, a.y + a.h], [a.cx, b.y]] : [[a.cx, a.y], [a.cx, b.y + b.h]];
    } else if (r.kind === 'realization' || r.kind === 'inheritance') {
      const gy = gapY(Math.max(ra, rb) - 1); // the gap just above the subtype
      pts = ra > rb
        ? [[a.cx, a.y], [a.cx, gy], [b.cx, gy], [b.cx, b.y + b.h]]
        : [[a.cx, a.y + a.h], [a.cx, gy], [b.cx, gy], [b.cx, b.y]];
    } else {
      const gx = ca < cb ? gutterX(ca) : gutterX(cb);
      // an edge going up leaves high and enters low, one going down leaves low and enters high, so two
      // edges in one gutter never meet and neither lands on a straight edge at mid height
      const ya = a.y + a.h * (ra > rb ? 0.15 : 0.85);
      const yb = b.y + b.h * (ra > rb ? 0.85 : 0.15);
      pts = ca < cb
        ? [[a.x + a.w, ya], [gx, ya], [gx, yb], [b.x, yb]]
        : [[a.x, ya], [gx, ya], [gx, yb], [b.x + b.w, yb]];
    }
    const dashed = r.kind === 'dependency' || r.kind === 'realization';
    out.push(`<polyline points="${pts.map((p) => p.join(',')).join(' ')}" fill="none" stroke="${LINE}" stroke-width="1.3"${dashed ? ' stroke-dasharray="6 4"' : ''}/>`);
    const [p1, p2] = pts.slice(-2);
    const [q1, q2] = pts.slice(0, 2);
    const head = (from, to, shape) => {
      const dx = Math.sign(to[0] - from[0]);
      const dy = Math.sign(to[1] - from[1]);
      const [x, y] = to;
      const px = -dy;
      const py = dx;
      if (shape === 'open') return `<polyline points="${x - dx * 11 + px * 5},${y - dy * 11 + py * 5} ${x},${y} ${x - dx * 11 - px * 5},${y - dy * 11 - py * 5}" fill="none" stroke="${LINE}" stroke-width="1.3"/>`;
      if (shape === 'triangle') return `<polygon points="${x - dx * 13 + px * 7},${y - dy * 13 + py * 7} ${x},${y} ${x - dx * 13 - px * 7},${y - dy * 13 - py * 7}" fill="#ffffff" stroke="${LINE}" stroke-width="1.3"/>`;
      return `<polygon points="${x},${y} ${x - dx * 9 + px * 5},${y - dy * 9 + py * 5} ${x - dx * 18},${y - dy * 18} ${x - dx * 9 - px * 5},${y - dy * 9 - py * 5}" fill="#ffffff" stroke="${LINE}" stroke-width="1.3"/>`;
    };
    if (r.kind === 'aggregation' || r.kind === 'composition') out.push(head(q2, q1, 'diamond'));
    else out.push(head(p1, p2, r.kind === 'realization' || r.kind === 'inheritance' ? 'triangle' : 'open'));
    if (r.label) {
      // label on the longest segment
      let best = 0;
      for (let i = 1; i < pts.length - 1; i += 1) {
        const len = (i) => Math.abs(pts[i + 1][0] - pts[i][0]) + Math.abs(pts[i + 1][1] - pts[i][1]);
        if (len(i) > len(best)) best = i;
      }
      const [s, e] = [pts[best], pts[best + 1]];
      const vertical = s[0] === e[0];
      labels.push(vertical
        ? [s[0] + 8, (s[1] + e[1]) / 2 + 4, 'start', r.label]
        : [(s[0] + e[0]) / 2, s[1] - 7, 'middle', r.label]);
    }
  }

  for (const b of Object.values(box)) {
    const c = tone[b.t.kind === 'interface' ? 'interface' : 'class'];
    out.push(`<rect x="${b.x}" y="${b.y}" width="${b.w}" height="${b.h}" fill="${c.fill}" stroke="${c.stroke}" stroke-width="1.4"/>`);
    out.push(`<rect x="${b.x}" y="${b.y}" width="${b.w}" height="${b.headH}" fill="${c.head}" stroke="${c.stroke}" stroke-width="1.4"/>`);
    let ty = b.y + 21;
    if (b.t.kind === 'interface') {
      out.push(`<text x="${b.cx}" y="${b.y + 17}" text-anchor="middle" font-family="${SANS}" font-size="12" font-style="italic" fill="${MUTED}">«interface»</text>`);
      ty = b.y + 35;
    }
    out.push(`<text x="${b.cx}" y="${ty}" text-anchor="middle" font-family="${MONO}" font-size="13" font-weight="700"${b.t.kind === 'interface' ? ' font-style="italic"' : ''} fill="${INK}">${esc(b.t.label)}</text>`);
    b.ops.forEach((op, i) => {
      // UML: abstract operations in italics, static operations underlined
      const { abstract, static: isStatic } = b.t.methods[i];
      out.push(`<text x="${b.x + 12}" y="${b.y + b.headH + 22 + i * 18}" font-family="${MONO}" font-size="12"${abstract ? ' font-style="italic"' : ''}${isStatic ? ' text-decoration="underline"' : ''} fill="${INK}">${esc(op)}</text>`);
    });
  }
  for (const [x, y, anchor, text] of labels) {
    out.push(`<text x="${x}" y="${y}" text-anchor="${anchor}" font-family="${SANS}" font-size="12.5" font-style="italic" fill="${MUTED}" stroke="#ffffff" stroke-width="4" stroke-linejoin="round" paint-order="stroke">${esc(text)}</text>`);
  }

  const svg = `<svg id="diagram" xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}"><rect width="${W}" height="${H}" fill="#ffffff"/>${out.join('')}</svg>`;
  const html = `<!doctype html><html><head><meta charset="utf-8"><title>${name}</title><style>body{margin:0;background:#fff}</style></head><body>${svg}</body></html>`;
  writeFileSync(fileURLToPath(new URL(`${name}.html`, import.meta.url)), html);
  console.log(name, `${spec.types.length} types, ${spec.relationships.length} relationships, ${Math.round(W)}x${Math.round(H)}`);
}

for (const n of [1, 2, 3]) {
  if (existsSync(fileURLToPath(new URL(`r4-set${n}-class.json`, import.meta.url)))) render(`r4-set${n}-class`);
}
