import { Drawing, Packet, PageGlyph } from "@/components/line-drawing";
import { ramp, type Point } from "@/lib/ramp";

/*
 * Ask once: one question fans out to every approved source at once, and the passages it uses drop
 * into the answer as its citations.
 */
const question = "supplier approval limit";
// The bottom of the question bar, where the search leaves it.
const asked: Point = [120, 26];
const sources = [
  { label: "SharePoint", x: 36, cited: true },
  { label: "Drive", x: 92, cited: true },
  { label: "Slack", x: 148, cited: false },
  { label: "Systems", x: 204, cited: false },
];
const pageTop = 56;
const labelY = pageTop + 28;
// Below each source's label, where a cited passage leaves for the answer.
const passageExit = labelY + 6;
const answer = { x: 24, y: 112, width: 192, height: 34 };
const citationX = [184, 200];

function SearchDrawing() {
  return (
    <Drawing viewBox="0 0 240 150" align="xMidYMid">
      <g className="ramp ramp-rise" style={ramp(0, 0.15)}>
        <rect
          x={48}
          y={4}
          width={144}
          height={22}
          rx={11}
          strokeWidth={1}
          className="fill-surface-raised stroke-border-strong"
        />
        <circle
          cx={61}
          cy={14}
          r={3.5}
          strokeWidth={1.25}
          className="fill-none stroke-content-secondary"
        />
        <path
          d="M63.5 16.5 l3 3"
          strokeWidth={1.25}
          strokeLinecap="round"
          className="stroke-content-secondary"
        />
        <text x={72} y={17.6} fontSize={7.5} className="fill-content-primary">
          {question}
        </text>
      </g>
      {sources.map((source, index) => {
        const leave = 0.18 + index * 0.05;
        const reached: Point = [source.x, pageTop - 2];
        return (
          <g key={source.label}>
            <path
              d={`M${asked[0]} ${asked[1]} L${reached[0]} ${reached[1]}`}
              pathLength={100}
              strokeWidth={1}
              className="ramp ramp-draw fill-none stroke-border-strong"
              style={ramp(leave, leave + 0.2)}
            />
            <Packet
              from={asked}
              to={reached}
              build={[leave + 0.02, leave + 0.24]}
              delay={index * 0.35}
            />
            <g className="ramp ramp-fade" style={ramp(leave + 0.15, leave + 0.3)}>
              <PageGlyph x={source.x} y={pageTop} className={source.cited ? "stroke-accent" : ""} />
              <text
                x={source.x}
                y={labelY}
                fontSize={7}
                textAnchor="middle"
                className="fill-content-secondary font-medium"
              >
                {source.label}
              </text>
            </g>
          </g>
        );
      })}
      {sources
        .filter((source) => source.cited)
        .map((source, index) => {
          const leave = 0.6 + index * 0.05;
          const from: Point = [source.x, passageExit];
          const to: Point = [source.x, answer.y];
          return (
            <g key={source.label}>
              <path
                d={`M${from[0]} ${from[1]} L${to[0]} ${to[1]}`}
                pathLength={100}
                strokeWidth={1}
                className="ramp ramp-draw fill-none stroke-accent"
                style={ramp(leave, leave + 0.12)}
              />
              <Packet
                from={from}
                to={to}
                build={[leave + 0.02, leave + 0.14]}
                delay={1.4 + index * 0.35}
              />
            </g>
          );
        })}
      <g className="ramp ramp-rise" style={ramp(0.7, 0.85)}>
        <rect
          {...answer}
          rx={6}
          strokeWidth={1}
          className="fill-surface-raised stroke-border-default"
        />
        <rect
          x={answer.x + 10}
          y={answer.y + 10}
          width={116}
          height={4}
          rx={2}
          className="fill-content-secondary"
        />
        <rect
          x={answer.x + 10}
          y={answer.y + 20}
          width={80}
          height={4}
          rx={2}
          className="fill-content-secondary"
        />
      </g>
      {citationX.map((x, index) => (
        <g key={x} className="ramp ramp-pop" style={ramp(0.84 + index * 0.05, 0.94 + index * 0.05)}>
          <circle cx={x} cy={answer.y + 12} r={6} className="fill-citation-surface" />
          <text
            x={x}
            y={answer.y + 14.6}
            fontSize={7}
            textAnchor="middle"
            className="fill-citation-content font-semibold"
          >
            {index + 1}
          </text>
        </g>
      ))}
    </Drawing>
  );
}

export { SearchDrawing };
