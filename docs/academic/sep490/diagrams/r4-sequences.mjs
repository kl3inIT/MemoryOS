// Builds the Report 4 section II sequence diagrams as standalone HTML pages with inline UML SVG.
// Run: node docs/academic/sep490/diagrams/r4-sequences.mjs
// Then capture #diagram of each r4-set<N>-seq-<name>.html at 2x into the matching .png.
// Lifelines are named by the class in the code; a "|" entry starts a new phase of the flow.
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const INK = '#2b2b2b';
const LINE = '#4d4943';
const MUTED = '#6b655c';
const MONO = "'JetBrains Mono', Consolas, monospace";
const SANS = "Calibri, 'Segoe UI', Arial, sans-serif";
const COL = 178;
const HEAD = 58;
const STEP = 40;
const TOP = 24;

const tone = {
  actor: { fill: '#f3f8fb', stroke: '#4f7f9c', kind: 'actor' },
  class: { fill: '#ffffff', stroke: '#6f6a62', kind: null },
  store: { fill: '#f6f2f9', stroke: '#76598f', kind: 'datastore' },
  queue: { fill: '#fbf5ec', stroke: '#a7772d', kind: 'queue' },
  external: { fill: '#f0f6f1', stroke: '#3f7d52', kind: 'external' },
};

const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

// Splits a long class name at a camel-case boundary near its middle.
function lines(name) {
  if (name.length <= 20 || name.includes(' ')) return [name];
  const cuts = [...name.matchAll(/[a-z][A-Z]/g)].map((m) => m.index + 1);
  const best = cuts.sort((a, b) => Math.abs(a - name.length / 2) - Math.abs(b - name.length / 2))[0];
  return best ? [name.slice(0, best), name.slice(best)] : [name];
}

function render(file, parts, steps) {
  const x = Object.fromEntries(parts.map(([id], i) => [id, 20 + COL / 2 + i * COL]));
  const W = 40 + parts.length * COL;
  const out = [];
  let y = TOP + HEAD + 34;
  const msgs = [];
  const phases = [];
  for (const s of steps) {
    if (typeof s === 'string') {
      if (phases.length) phases.at(-1).to = y - 14;
      phases.push({ label: s.slice(1).trim(), from: y - 6 });
      y += 40;
      continue;
    }
    const [from, to, label, kind = 'call'] = s;
    msgs.push({ from, to, label, kind, y, phase: phases.length - 1 });
    y += STEP;
  }
  phases.at(-1).to = y - 14;
  const H = y + 18;

  // phase frames (UML combined-fragment style, behind everything)
  for (const p of phases) {
    out.push(`<rect x="10" y="${p.from}" width="${W - 20}" height="${p.to - p.from}" fill="none" stroke="#b9b2a7" stroke-width="1" stroke-dasharray="5 4"/>`);
    const tw = p.label.length * 7.4 + 22;
    out.push(`<path d="M10 ${p.from} H${10 + tw} V${p.from + 14} l-8 8 H10 Z" fill="#efebe5" stroke="#b9b2a7"/>`);
    out.push(`<text x="18" y="${p.from + 16}" font-family="${SANS}" font-size="13" font-weight="700" fill="${INK}">${esc(p.label)}</text>`);
  }

  // lifelines and heads
  for (const [id, t, name] of parts) {
    const c = tone[t];
    out.push(`<line x1="${x[id]}" y1="${TOP + HEAD}" x2="${x[id]}" y2="${H - 8}" stroke="#9a948a" stroke-width="1.2" stroke-dasharray="6 5"/>`);
    const w = COL - 16;
    out.push(`<rect x="${x[id] - w / 2}" y="${TOP}" width="${w}" height="${HEAD}" rx="4" fill="${c.fill}" stroke="${c.stroke}" stroke-width="1.4"/>`);
    const ls = lines(name);
    let ty = TOP + (c.kind ? 21 : 26) - (ls.length - 1) * 7;
    if (c.kind) {
      out.push(`<text x="${x[id]}" y="${ty}" text-anchor="middle" font-family="${SANS}" font-size="11.5" font-style="italic" fill="${MUTED}">«${c.kind}»</text>`);
      ty += 17;
    }
    for (const l of ls) {
      out.push(`<text x="${x[id]}" y="${ty}" text-anchor="middle" font-family="${t === 'class' ? MONO : SANS}" font-size="${t === 'class' ? 12.5 : 14}" font-weight="700" fill="${INK}">${esc(l)}</text>`);
      ty += 16;
    }
  }

  // activation bars: from the first to the last message a lifeline takes part in, per phase
  const bars = {};
  for (const m of msgs) {
    for (const id of [m.from, m.to]) {
      const k = `${id}:${m.phase}`;
      bars[k] ??= { id, from: m.y, to: m.y };
      bars[k].to = m.y;
    }
  }
  for (const b of Object.values(bars)) {
    out.push(`<rect x="${x[b.id] - 5}" y="${b.from - 8}" width="10" height="${b.to - b.from + 16}" fill="#ffffff" stroke="${LINE}" stroke-width="1.1"/>`);
  }

  // messages
  for (const m of msgs) {
    const dir = x[m.to] > x[m.from] ? 1 : -1;
    const x1 = x[m.from] + dir * 5;
    const x2 = x[m.to] - dir * 5;
    const ret = m.kind === 'return';
    out.push(`<line x1="${x1}" y1="${m.y}" x2="${x2}" y2="${m.y}" stroke="${LINE}" stroke-width="1.3"${ret ? ' stroke-dasharray="6 4"' : ''}/>`);
    const a = `${x2 - dir * 10},${m.y - 5} ${x2},${m.y} ${x2 - dir * 10},${m.y + 5}`;
    out.push(ret
      ? `<polyline points="${a}" fill="none" stroke="${LINE}" stroke-width="1.3"/>`
      : `<polygon points="${a}" fill="${LINE}"/>`);
    // keep a long label inside the right edge
    const lx = Math.min(dir > 0 ? x1 + 8 : x2 + 8, W - 12 - m.label.length * 7.25);
    // a white halo keeps a label readable where it crosses a lifeline
    out.push(`<text x="${lx}" y="${m.y - 6}" font-family="${MONO}" font-size="12" fill="${ret ? MUTED : INK}" stroke="#ffffff" stroke-width="4" stroke-linejoin="round" paint-order="stroke"${ret ? ' font-style="italic"' : ''}>${esc(m.label)}</text>`);
  }

  const svg = `<svg id="diagram" xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}"><rect width="${W}" height="${H}" fill="#ffffff"/>${out.join('')}</svg>`;
  const html = `<!doctype html><html><head><meta charset="utf-8"><title>${file}</title><style>body{margin:0;background:#fff}</style></head><body>${svg}</body></html>`;
  writeFileSync(fileURLToPath(new URL(`${file}.html`, import.meta.url)), html);
  console.log(file, `${parts.length} lifelines, ${msgs.length} messages, ${W}x${H}`);
}

render('r4-set1-seq-sync', [
  ['user', 'actor', 'Browser'],
  ['ctrl', 'class', 'GoogleDriveSourceController'],
  ['svc', 'class', 'DefaultGoogleDriveSourceService'],
  ['pg', 'store', 'PostgreSQL'],
  ['relay', 'class', 'RedisOperationRelay'],
  ['redis', 'queue', 'Redis Streams'],
  ['engine', 'class', 'SourceSyncEngine'],
  ['adapter', 'class', 'GoogleDriveSyncAdapter'],
  ['run', 'class', 'SyncRun'],
  ['drive', 'external', 'Google Drive'],
  ['minio', 'store', 'MinIO'],
], [
  '| Request',
  ['user', 'ctrl', 'POST /api/sources/{id}/google-drive/sync'],
  ['ctrl', 'svc', 'synchronize(actor, sourceId)'],
  ['svc', 'pg', 'enqueue(tenant, source, revision, MANUAL, actor)'],
  ['pg', 'svc', 'SourceOperationView', 'return'],
  ['ctrl', 'user', '202 SourceOperationResponse', 'return'],
  '| Dispatch (every second)',
  ['relay', 'pg', 'claim(SOURCE_SYNC, limit)'],
  ['pg', 'relay', 'due source_sync_attempts', 'return'],
  ['relay', 'redis', 'XADD operation'],
  ['relay', 'pg', 'recordPublished(claim, messageId)'],
  '| Synchronize',
  ['redis', 'engine', 'deliver (RedisStreamWorker)'],
  ['engine', 'pg', 'claim(tenant, operationId, deliveryId)'],
  ['engine', 'adapter', 'walk(run)'],
  ['adapter', 'drive', 'listFiles(folderId, pageToken)'],
  ['drive', 'adapter', 'files page', 'return'],
  ['adapter', 'run', 'unchanged(fileId, version, created, updated)'],
  ['adapter', 'drive', 'acquire(file)'],
  ['drive', 'adapter', 'file content', 'return'],
  ['adapter', 'run', 'acquire(content, hooks)'],
  ['run', 'minio', 'stage(tenant, spec, bytes)'],
  ['run', 'pg', 'acceptRemote(...)'],
  ['run', 'pg', 'create(...): index_attempts'],
  ['adapter', 'run', 'complete()'],
  ['engine', 'pg', 'complete(work)'],
  ['engine', 'redis', 'acknowledge and delete'],
]);

render('r4-set1-seq-index', [
  ['redis', 'queue', 'Redis Streams'],
  ['coord', 'class', 'DefaultIngestionCoordinator'],
  ['pg', 'store', 'PostgreSQL'],
  ['minio', 'store', 'MinIO'],
  ['ext', 'class', 'SourceContentExtractorRouter'],
  ['sic', 'class', 'SearchIngestionCoordinator'],
  ['chunks', 'class', 'DocumentChunkService'],
  ['osidx', 'class', 'OpenSearchIndexService'],
  ['ai', 'external', 'Embedding model'],
  ['os', 'store', 'OpenSearch'],
], [
  '| Extract and publish',
  ['redis', 'coord', 'deliver INGESTION operation'],
  ['coord', 'pg', 'claim(tenant, operationId, deliveryId)'],
  ['coord', 'minio', 'open(object key)'],
  ['coord', 'ext', 'extract(tenant, stream, size, filename, input)'],
  ['ext', 'coord', 'DocumentContent', 'return'],
  ['coord', 'minio', 'stage(tenant, content)'],
  ['coord', 'pg', 'publish(...): documents, search_index_operations'],
  ['coord', 'pg', 'complete(work, documentId)'],
  ['coord', 'redis', 'acknowledge'],
  '| Chunk and index',
  ['redis', 'sic', 'deliver SEARCH operation'],
  ['sic', 'pg', 'claim(delivery)'],
  ['sic', 'chunks', 'prepare(tenant, documentId, generation)'],
  ['chunks', 'minio', 'open(artifact key)'],
  ['chunks', 'pg', 'publish(reader, chunks): document_chunks'],
  ['sic', 'osidx', 'index(chunkSet, identity)'],
  ['osidx', 'ai', 'embed chunk texts'],
  ['ai', 'osidx', 'vectors', 'return'],
  ['osidx', 'os', 'bulk index through alias'],
  ['sic', 'pg', 'finish(claim, SUCCESS)'],
  ['sic', 'chunks', 'markSearchReady(...)'],
  ['chunks', 'pg', 'set searchable_generation'],
]);

render('r4-set2-seq-answer', [
  ['user', 'actor', 'Browser'],
  ['tctrl', 'class', 'ChatTurnController'],
  ['sctrl', 'class', 'ChatStreamController'],
  ['turn', 'class', 'ChatTurnService'],
  ['limit', 'class', 'AiUsageLimitService'],
  ['pg', 'store', 'PostgreSQL'],
  ['redis', 'queue', 'Redis Streams'],
  ['exec', 'class', 'ChatModelExecutor'],
  ['model', 'external', 'AI model provider'],
  ['search', 'class', 'SearchTool'],
  ['dss', 'class', 'DocumentSearchService'],
  ['os', 'store', 'OpenSearch'],
], [
  '| Accept the question',
  ['user', 'tctrl', 'POST /api/chat/sessions/{id}/messages'],
  ['tctrl', 'turn', 'command(actor, session, SEND, ...)'],
  ['turn', 'limit', 'enforce(actor)'],
  ['turn', 'pg', 'reserve(...): user and RUNNING assistant rows'],
  ['turn', 'redis', 'open(assistantMessageId)'],
  ['tctrl', 'user', '202 ChatTurnAcceptedResponse', 'return'],
  ['user', 'sctrl', 'GET .../messages/{assistantId}/events'],
  ['sctrl', 'turn', 'subscribe(actor, session, assistant, after)'],
  '| Answer in the background',
  ['turn', 'model', 'ChatGuardrailCheck.check(question)'],
  ['turn', 'exec', 'execute(setup, ...)'],
  ['exec', 'model', 'stream(prompt) with tools'],
  ['model', 'exec', 'tool call search_knowledge', 'return'],
  ['exec', 'search', 'searchKnowledge(queries, filters)'],
  ['search', 'dss', 'scope(actor), ranked(scope, queries, ...)'],
  ['dss', 'os', 'POST /{alias}/_search (hybrid)'],
  ['os', 'dss', 'hits', 'return'],
  ['dss', 'pg', 'readableMetadata(scope, ids)'],
  ['search', 'exec', 'passages with citation numbers', 'return'],
  ['exec', 'model', 'tool result'],
  ['model', 'exec', 'answer text', 'return'],
  ['exec', 'turn', 'output(text)'],
  ['turn', 'redis', 'append(id, text) after CitationGate.accept'],
  ['redis', 'sctrl', 'read new events', 'return'],
  ['sctrl', 'user', 'SSE text', 'return'],
  '| Finish',
  ['turn', 'pg', 'finishAndRead(...) and record(AiUsage)'],
  ['turn', 'redis', 'finish(id, status, ...)'],
  ['sctrl', 'user', 'SSE outcome', 'return'],
]);

render('r4-set2-seq-mcp', [
  ['user', 'actor', 'Browser'],
  ['tctrl', 'class', 'ChatTurnController'],
  ['turn', 'class', 'ChatTurnService'],
  ['redis', 'queue', 'Redis Streams'],
  ['mcpsvc', 'class', 'McpTurnService'],
  ['pg', 'store', 'PostgreSQL'],
  ['oauth', 'class', 'McpOAuthService'],
  ['model', 'external', 'AI model provider'],
  ['tools', 'class', 'McpTools'],
  ['turntools', 'class', 'McpTurnTools'],
  ['ext', 'external', 'External MCP server'],
], [
  '| Open the tools when the turn starts',
  ['turn', 'mcpsvc', 'open(actor, serverIds, ..., WEB)'],
  ['mcpsvc', 'pg', 'tools and credentials (OFF skipped)'],
  ['mcpsvc', 'oauth', 'accessToken(tenant, server, owner)'],
  ['oauth', 'ext', 'refresh the token if it expired'],
  ['mcpsvc', 'turn', 'McpTurnTools', 'return'],
  ['turn', 'model', 'stream(prompt) with McpTools'],
  '| The model calls a tool that needs approval (ASK)',
  ['model', 'tools', 'tool call (arguments)'],
  ['tools', 'turntools', 'decide(binding, callId, args)'],
  ['turntools', 'turn', 'awaitApproval(...)'],
  ['turn', 'redis', 'approval(id, callId, server, tool, ...)'],
  ['redis', 'user', 'SSE tool_approval', 'return'],
  ['user', 'tctrl', 'POST .../tool-calls/{callId}/decision'],
  ['tctrl', 'turn', 'decide(..., approved, alwaysAllow)'],
  ['turn', 'turntools', 'approved', 'return'],
  '| Call the tool',
  ['tools', 'turntools', 'call(binding, arguments, deadline)'],
  ['turntools', 'ext', 'McpClients.open(url, headers), call(tool, args)'],
  ['ext', 'turntools', 'McpToolResult', 'return'],
  ['turntools', 'tools', 'Outcome(text, error)', 'return'],
  ['tools', 'model', 'Tool.Result.text(...)', 'return'],
]);

render('r4-set3-seq-live', [
  ['user', 'actor', 'Browser'],
  ['ctrl', 'class', 'MeetingController'],
  ['ticket', 'class', 'VoiceTicketStore'],
  ['intercept', 'class', 'MeetingHandshakeInterceptor'],
  ['ws', 'class', 'MeetingStreamWebSocketHandler'],
  ['svc', 'class', 'MeetingService'],
  ['live', 'class', 'LiveTranscriptionService'],
  ['stt', 'external', 'Speech-to-text provider'],
  ['pg', 'store', 'PostgreSQL'],
], [
  '| Create the meeting',
  ['user', 'ctrl', 'POST /api/meetings'],
  ['ctrl', 'svc', 'create(actor, draft)'],
  ['svc', 'pg', 'insert meeting (RECORDING)'],
  ['ctrl', 'user', '201 MeetingDetailResponse', 'return'],
  ['user', 'ctrl', 'POST /api/meetings/{id}/tickets {track}'],
  ['ctrl', 'ticket', 'issue(actor, "MEETING:{id}:{track}")'],
  ['ctrl', 'user', 'MeetingTicketResponse', 'return'],
  '| Open one audio stream per track (MIC, TAB)',
  ['user', 'intercept', 'WebSocket /api/meeting-stream?meeting&track&ticket'],
  ['intercept', 'ticket', 'consume(ticket, actor, scope)'],
  ['intercept', 'ws', 'handshake accepted', 'return'],
  ['ws', 'svc', 'openTrack(actor, id, track, offsetMs, listener)'],
  ['svc', 'live', 'open(actor, options, offsetMs, listener)'],
  ['live', 'stt', 'open a live stream (or chunked fallback)'],
  ['svc', 'pg', 'recordProvider(tenant, id, provider, model, ...)'],
  ['ws', 'user', '{"type":"ready"}', 'return'],
  '| Stream audio and transcript',
  ['user', 'ws', 'binary PCM16 frame'],
  ['ws', 'svc', 'TrackSession.append(pcm)'],
  ['svc', 'stt', 'LiveTranscription.append(pcm)'],
  ['stt', 'svc', 'preview(speaker, text)', 'return'],
  ['ws', 'user', '{"type":"preview"}', 'return'],
  ['stt', 'svc', 'segment(segment)', 'return'],
  ['svc', 'pg', 'insertUtterance(tenant, id, utterance)'],
  ['ws', 'user', '{"type":"utterance"}', 'return'],
  '| End the meeting',
  ['user', 'ws', '{"type":"end"}'],
  ['ws', 'svc', 'TrackSession.finish()'],
  ['ws', 'user', '{"type":"finished"}', 'return'],
  ['user', 'ctrl', 'POST /api/meetings/{id}/end'],
  ['ctrl', 'svc', 'end(actor, id)'],
  ['svc', 'pg', 'end(tenant, id), queueMinutes(tenant, id)'],
  ['ctrl', 'user', 'MeetingDetailResponse', 'return'],
]);

render('r4-set3-seq-minutes', [
  ['user', 'actor', 'Browser'],
  ['ctrl', 'class', 'MeetingController'],
  ['svc', 'class', 'MeetingService'],
  ['jobs', 'class', 'MeetingMinutes'],
  ['minsvc', 'class', 'MeetingMinutesService'],
  ['leased', 'class', 'LeasedJob'],
  ['repo', 'class', 'MeetingRepository'],
  ['sum', 'class', 'TranscriptSummarizer'],
  ['model', 'external', 'AI model provider'],
], [
  '| Queue the minutes when the meeting ends',
  ['user', 'ctrl', 'POST /api/meetings/{id}/end'],
  ['ctrl', 'svc', 'end(actor, id)'],
  ['svc', 'repo', 'queueMinutes(tenant, id): PENDING'],
  '| Write the minutes (every 5 seconds, in the API process)',
  ['jobs', 'minsvc', 'writeNext()'],
  ['minsvc', 'leased', 'runNext(log, "meeting.minutes", steps)'],
  ['leased', 'repo', 'failAbandonedMinutes(maxAttempts)'],
  ['leased', 'repo', 'claimMinutes(lease, maxAttempts): RUNNING'],
  ['repo', 'leased', 'MinutesClaim', 'return'],
  ['leased', 'minsvc', 'write(claim)'],
  ['minsvc', 'repo', 'find, utterances, speakers'],
  ['repo', 'minsvc', 'transcript lines', 'return'],
  ['minsvc', 'sum', 'summarize(actor, tenant, subject, lines)'],
  ['sum', 'model', 'generateObject(binding, ...)'],
  ['model', 'sum', 'TranscriptSummary', 'return'],
  ['sum', 'minsvc', 'TranscriptSummary', 'return'],
  ['minsvc', 'repo', 'writeMinutes(tenant, id, attempt, summary, kind, items)'],
  ['leased', 'repo', 'on error: failMinutes(...): PENDING or FAILED'],
  '| Read the result',
  ['user', 'ctrl', 'GET /api/meetings/{id}'],
  ['ctrl', 'svc', 'get(actor, id)'],
  ['ctrl', 'user', 'MeetingDetailResponse with minutes', 'return'],
]);
