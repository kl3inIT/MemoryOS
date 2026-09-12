import type { ReactNode } from "react";
import { Drawing, Packet, PageGlyph } from "@/components/line-drawing";
import { ramp, type Point } from "@/lib/ramp";
import { cn } from "@/lib/utils";

/*
 * Enterprise identity governs every request: search, agents and MCP clients all pass the same access
 * check, which lets through the documents the signed-in person may read and stops the rest.
 */
const glyphX = 18;
const labelX = 32;
// Where a consumer's line leaves, clear of the longest label.
const consumerExit = 82;
const gate: Point = [132, 75];
const gateRadius = 20;
const documentX = 208;
// How far along its line a blocked request gets before the check stops it.
const blockedAt = 0.6;

function SearchGlyph({ y }: { y: number }) {
  return (
    <>
      <circle cx={glyphX - 1.5} cy={y - 1.5} r={4.5} />
      <path d={`M${glyphX + 1.8} ${y + 1.8} l4 4`} strokeLinecap="round" />
    </>
  );
}

function AgentGlyph({ y }: { y: number }) {
  return (
    <>
      <path d={`M${glyphX} ${y - 7} l6 3.5 v7 l-6 3.5 l-6 -3.5 v-7 z`} />
      <circle cx={glyphX} cy={y} r={1.8} className="fill-current" />
    </>
  );
}

function ClientGlyph({ y }: { y: number }) {
  return (
    <>
      <rect x={glyphX - 7} y={y - 6} width={14} height={12} rx={2} />
      <path d={`M${glyphX - 4} ${y - 1.5} l2.5 2 l-2.5 2 M${glyphX + 0.5} ${y + 3} h4`} />
    </>
  );
}

const consumers: readonly { label: string; y: number; glyph: ReactNode }[] = [
  { label: "Search", y: 28, glyph: <SearchGlyph y={28} /> },
  { label: "Agents", y: 75, glyph: <AgentGlyph y={75} /> },
  { label: "MCP clients", y: 122, glyph: <ClientGlyph y={122} /> },
];
const documents = [
  { y: 24, allowed: true },
  { y: 58, allowed: false },
  { y: 92, allowed: true },
  { y: 126, allowed: false },
];

function AccessDrawing() {
  const [gateX, gateY] = gate;
  const gateExit: Point = [gateX + gateRadius, gateY];
  const gateEntry: Point = [gateX - gateRadius, gateY];
  return (
    <Drawing viewBox="0 0 240 150" align="xMidYMid">
      {consumers.map((consumer, index) => {
        const arrive = index * 0.06;
        const leaving: Point = [consumerExit, consumer.y];
        return (
          <g key={consumer.label}>
            <g className="ramp ramp-fade" style={ramp(arrive, arrive + 0.15)}>
              <g
                strokeWidth={1.25}
                className="fill-none stroke-content-secondary text-content-secondary"
              >
                {consumer.glyph}
              </g>
              <text
                x={labelX}
                y={consumer.y + 2.5}
                fontSize={7}
                className="fill-content-secondary font-medium"
              >
                {consumer.label}
              </text>
            </g>
            <path
              d={`M${consumerExit} ${consumer.y} L${gateEntry[0]} ${gateEntry[1]}`}
              pathLength={100}
              strokeWidth={1}
              className="ramp ramp-draw fill-none stroke-border-strong"
              style={ramp(arrive + 0.12, arrive + 0.3)}
            />
            <Packet
              from={leaving}
              to={gateEntry}
              build={[arrive + 0.14, arrive + 0.34]}
              delay={index * 0.6}
            />
          </g>
        );
      })}
      <g className="ramp ramp-pop" style={ramp(0.35, 0.55)}>
        <circle
          cx={gateX}
          cy={gateY}
          r={gateRadius}
          strokeWidth={1.25}
          className="fill-accent/5 stroke-accent"
        />
        <g strokeWidth={1.25} className="fill-none stroke-accent">
          <rect
            x={gateX - 6}
            y={gateY - 1}
            width={12}
            height={9}
            rx={2}
            className="fill-accent/15"
          />
          <path d={`M${gateX - 3.5} ${gateY - 1} v-3 a3.5 3.5 0 0 1 7 0 v3`} />
        </g>
        <circle cx={gateX} cy={gateY + 3.5} r={1.1} className="fill-accent" />
      </g>
      <circle
        cx={gateX}
        cy={gateY}
        r={gateRadius}
        strokeWidth={1.25}
        className="ramp ramp-pass ramp-pop ambient-pulse fill-none stroke-accent"
        style={ramp(0.5, 0.66)}
      />
      {documents.map((document, index) => {
        const leave = 0.55 + index * 0.04;
        const reached: Point = [documentX - 9, document.y];
        const stopped: Point = [
          gateExit[0] + (reached[0] - gateExit[0]) * blockedAt,
          gateExit[1] + (reached[1] - gateExit[1]) * blockedAt,
        ];
        const end = document.allowed ? reached : stopped;
        return (
          <g key={document.y}>
            <path
              d={`M${gateExit[0]} ${gateExit[1]} L${end[0]} ${end[1]}`}
              pathLength={100}
              strokeWidth={1}
              className={cn(
                "ramp ramp-draw fill-none",
                document.allowed ? "stroke-accent" : "stroke-border-strong",
              )}
              style={ramp(leave, leave + 0.2)}
            />
            <Packet
              from={gateExit}
              to={end}
              build={[leave + 0.02, leave + 0.22]}
              delay={1.8 + index * 0.5}
            />
            <g
              className={cn("ramp ramp-fade", !document.allowed && "opacity-40")}
              style={ramp(leave + 0.08, leave + 0.24)}
            >
              <PageGlyph
                x={documentX}
                y={document.y - 9}
                className={document.allowed ? "stroke-accent" : ""}
              />
            </g>
            {document.allowed ? null : (
              <path
                d={`M${end[0] - 3} ${end[1] - 3} l6 6 m0 -6 l-6 6`}
                strokeWidth={1.25}
                strokeLinecap="round"
                className="ramp ramp-pop fill-none stroke-content-muted"
                style={ramp(0.8, 0.92)}
              />
            )}
          </g>
        );
      })}
    </Drawing>
  );
}

export { AccessDrawing };
