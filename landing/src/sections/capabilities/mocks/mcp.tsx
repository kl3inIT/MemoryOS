import { citationMarker, illustrationCard, illustrationLabel } from "@/lib/illustration";
import { cn } from "@/lib/utils";
import { enter } from "@/sections/capabilities/animate-mock";

const tools = ["search_knowledge", "read_passage", "run_agent"];
const call = 'search_knowledge("supplier approval")';

function McpMock() {
  return (
    <div className="flex h-full items-center justify-center p-5 sm:p-6">
      <div
        {...enter("rise", 0)}
        className={cn(illustrationCard, "w-full max-w-96 overflow-hidden")}
      >
        <p className="flex items-center gap-1.5 border-b border-border-subtle px-3 py-2">
          <span className="size-2 rounded-full bg-border-default" />
          <span className="size-2 rounded-full bg-border-default" />
          <span className="size-2 rounded-full bg-border-default" />
          <span className={cn(illustrationLabel, "ml-2")}>MCP client</span>
        </p>
        <div className="space-y-3 p-3">
          <div>
            <p className={illustrationLabel}>MemoryOS tools</p>
            <ul className="mt-1.5 flex flex-wrap gap-1.5">
              {tools.map((tool, index) => (
                <li
                  key={tool}
                  {...enter("rise", 0.25 + index * 0.1)}
                  className="rounded-md bg-surface-canvas px-2 py-0.5 font-mono text-xs text-content-primary"
                >
                  {tool}
                </li>
              ))}
            </ul>
          </div>
          <p {...enter("type", 0.75)} className="truncate font-mono text-xs text-content-primary">
            {call}
          </p>
          <div
            {...enter("rise", 2.2)}
            className="rounded-lg border border-border-subtle bg-surface-base p-2.5 font-secondary-body text-content-primary"
          >
            <p>
              Contracts above the limit need Legal review. <span className={citationMarker}>1</span>
            </p>
            <p className="mt-1 text-content-muted">Procurement policy 2026.pdf</p>
          </div>
        </div>
      </div>
    </div>
  );
}

export { McpMock };
