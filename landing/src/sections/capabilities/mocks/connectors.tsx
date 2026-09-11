import { illustrationCard } from "@/lib/illustration";
import { cn } from "@/lib/utils";
import { enter } from "@/sections/capabilities/animate-mock";

const sources = ["Google Drive", "Uploaded files", "REST API", "Business system"];

function ConnectorsMock() {
  return (
    <div className="flex h-full items-center p-5 sm:p-6">
      <ul className="flex-1 space-y-2.5">
        {sources.map((source) => (
          <li key={source} className="flex items-center">
            <span
              {...enter("slide")}
              className={cn(
                illustrationCard,
                "w-32 shrink-0 px-3 py-2 font-main-ui-body text-content-primary",
              )}
            >
              {source}
            </span>
            <span {...enter("growX")} className="h-px flex-1 origin-left bg-accent/60" />
          </li>
        ))}
      </ul>
      <div
        {...enter("rise", 0.5)}
        className="w-36 shrink-0 rounded-xl bg-accent-surface p-4 text-accent-content shadow-[0_16px_48px_-12px_var(--glow-core)]"
      >
        <p className="font-main-ui-action">MemoryOS index</p>
        <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-accent-content/25">
          <span
            {...enter("growX", 0.7, 1.4)}
            className="block h-full origin-left rounded-full bg-accent-content"
          />
        </div>
        <p {...enter("fade", 2.1)} className="mt-2 font-secondary-body text-accent-content/80">
          Up to date
        </p>
      </div>
    </div>
  );
}

export { ConnectorsMock };
