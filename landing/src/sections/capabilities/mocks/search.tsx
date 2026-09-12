import { illustrationCard, illustrationLabel } from "@/lib/illustration";
import { cn } from "@/lib/utils";
import { enter } from "@/sections/capabilities/animate-mock";

const query = "supplier approval limit";
const results = [
  { title: "Procurement policy 2026.pdf", source: "SharePoint", relevance: 0.92 },
  { title: "Supplier onboarding checklist.docx", source: "Google Drive", relevance: 0.74 },
  { title: "Contract register", source: "Salesforce", relevance: 0.58 },
];

function SearchMock() {
  return (
    <div className="flex h-full flex-col justify-center gap-3 p-5 sm:p-6">
      <p className={cn(illustrationCard, "flex items-center gap-3 px-3 py-2.5")}>
        <span className={illustrationLabel}>Search</span>
        <span {...enter("type", 0)} className="truncate font-main-ui-body text-content-primary">
          {query}
        </span>
      </p>
      <ol className="space-y-2">
        {results.map((result, index) => (
          <li
            key={result.title}
            {...enter("rise", 1 + index * 0.15)}
            className={cn(illustrationCard, "px-3 py-2")}
          >
            <p className="truncate font-main-ui-action text-content-primary">{result.title}</p>
            <div className="mt-1.5 flex items-center gap-3">
              <span className="font-secondary-body text-content-muted">{result.source}</span>
              <span className="ml-auto h-1 w-16 overflow-hidden rounded-full bg-surface-canvas">
                <span
                  {...enter("growX")}
                  className="block h-full origin-left rounded-full bg-accent"
                  style={{ width: `${result.relevance * 100}%` }}
                />
              </span>
            </div>
          </li>
        ))}
      </ol>
    </div>
  );
}

export { SearchMock };
