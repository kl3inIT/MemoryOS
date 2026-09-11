import { citationMarker, illustrationCard } from "@/lib/illustration";
import { cn } from "@/lib/utils";
import { enter } from "@/sections/capabilities/animate-mock";

function CitationsMock() {
  return (
    <div className="grid h-full content-center gap-3 p-5 sm:p-6 xl:grid-cols-2 xl:items-center">
      <p className={cn(illustrationCard, "p-4 font-main-ui-body text-content-primary")}>
        <span {...enter("fade", 0)}>Contracts above your spending limit need Legal review.</span>{" "}
        <span {...enter("pop", 0.3)} className={citationMarker}>
          1
        </span>{" "}
        <span {...enter("fade", 0.5)}>Procurement then registers the supplier.</span>{" "}
        <span {...enter("pop", 0.8)} className={citationMarker}>
          2
        </span>
      </p>
      <div {...enter("arrive", 1)} className={cn(illustrationCard, "p-4")}>
        <p className="flex items-center gap-2">
          <span className={citationMarker}>1</span>
          <span className="truncate font-main-ui-action text-content-primary">
            Procurement policy 2026.pdf
          </span>
        </p>
        <p className="mt-2 font-secondary-body text-content-secondary">
          Section 4.{" "}
          <span
            {...enter("mark", 1.3)}
            className="box-decoration-clone bg-[linear-gradient(var(--citation-surface),var(--citation-surface))] bg-no-repeat text-content-primary [background-size:100%_100%]"
          >
            Contracts above your department's spending limit need Legal review
          </span>{" "}
          before signature.
        </p>
      </div>
    </div>
  );
}

export { CitationsMock };
