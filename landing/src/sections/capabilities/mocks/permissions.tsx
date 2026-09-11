import { illustrationCard, illustrationLabel } from "@/lib/illustration";
import { cn } from "@/lib/utils";
import { enter } from "@/sections/capabilities/animate-mock";

// The same question from two teams: each sees only what its members may read.
const question = "supplier contracts";
const views = [
  {
    team: "Procurement",
    results: [
      "Procurement policy 2026.pdf",
      "Supplier onboarding checklist.docx",
      "Contract register",
    ],
  },
  { team: "Engineering", results: ["Supplier onboarding checklist.docx"] },
];
const rowsPerView = Math.max(...views.map((view) => view.results.length));

function PermissionsMock() {
  return (
    <div className="flex h-full flex-col justify-center gap-3 p-5 sm:p-6">
      <p
        {...enter("rise", 0)}
        className={cn(illustrationCard, "flex items-center gap-3 px-3 py-2")}
      >
        <span className={illustrationLabel}>Same question</span>
        <span className="truncate font-main-ui-body text-content-primary">{question}</span>
      </p>
      <div className="grid grid-cols-2 gap-3">
        {views.map((view, viewIndex) => (
          <div
            key={view.team}
            {...enter("rise", 0.25 + viewIndex * 0.12)}
            className={cn(illustrationCard, "min-w-0 p-3")}
          >
            <p className="font-main-ui-action text-content-primary">{view.team}</p>
            <ul className="mt-2 space-y-1.5">
              {view.results.map((result, rowIndex) => (
                <li
                  key={result}
                  {...enter("slide", 0.6 + (viewIndex * rowsPerView + rowIndex) * 0.12)}
                  className="truncate rounded-md bg-surface-canvas px-2 py-1 font-secondary-body text-content-primary"
                >
                  {result}
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </div>
  );
}

export { PermissionsMock };
