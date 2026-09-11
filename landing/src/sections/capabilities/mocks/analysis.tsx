import { illustrationCard, illustrationLabel } from "@/lib/illustration";
import { cn } from "@/lib/utils";
import { enter } from "@/sections/capabilities/animate-mock";

const code = 'spend.groupby("team").sum().plot.bar()';
// Relative spend per team, as bar heights; the mock shows no figures.
const teams = [
  { name: "Legal", share: 0.45 },
  { name: "Procurement", share: 0.9 },
  { name: "Finance", share: 0.65 },
  { name: "IT", share: 0.35 },
];

function AnalysisMock() {
  return (
    <div className="flex h-full flex-col justify-center gap-3 p-5 sm:p-6">
      <div className={cn(illustrationCard, "px-3 py-2")}>
        <p className={illustrationLabel}>Python</p>
        <code
          {...enter("type", 0)}
          className="mt-1 block truncate font-mono text-xs text-content-primary"
        >
          {code}
        </code>
      </div>
      <div
        className={cn(illustrationCard, "grid grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)] gap-4 p-4")}
      >
        <ul className="space-y-1.5 font-secondary-body text-content-secondary">
          {teams.map((team, index) => (
            <li
              key={team.name}
              {...enter("slide", 1.45 + index * 0.08)}
              className="border-b border-border-subtle pb-1.5 last:border-0"
            >
              {team.name}
            </li>
          ))}
        </ul>
        <div className="flex h-24 items-end gap-2 border-b border-border-default">
          {teams.map((team, index) => (
            <span
              key={team.name}
              {...enter("growY", 1.6 + index * 0.1)}
              className="flex-1 origin-bottom rounded-t bg-accent"
              style={{ height: `${team.share * 100}%` }}
            />
          ))}
        </div>
      </div>
    </div>
  );
}

export { AnalysisMock };
