import { illustrationCard, illustrationChip, illustrationLabel } from "@/lib/illustration";
import { cn } from "@/lib/utils";
import { enter } from "@/sections/capabilities/animate-mock";

const formats = ["PDF", "DOCX", "XLSX", "PPTX", "Email", "Scan"];
// What MemoryOS reads inside one file: its text, a table and a chart, drawn without figures.
const textLines = ["w-full", "w-5/6", "w-3/5"];
const tableCells = 6;
const bars = [0.5, 0.85, 0.35, 0.65];

function FilesMock() {
  return (
    <div className="flex h-full flex-col justify-center gap-3 p-5 sm:p-6">
      <ul className="flex flex-wrap gap-1.5">
        {formats.map((format, index) => (
          <li
            key={format}
            {...enter("drop", index * 0.08)}
            className={cn(
              illustrationChip,
              "border border-border-subtle bg-surface-raised text-content-secondary",
            )}
          >
            {format}
          </li>
        ))}
      </ul>
      <div {...enter("rise", 0.7)} className={cn(illustrationCard, "grid grid-cols-3 gap-4 p-4")}>
        <div>
          <p className={illustrationLabel}>Text</p>
          <div className="mt-2 space-y-1.5">
            {textLines.map((width, index) => (
              <span
                key={width}
                {...enter("growX", 1.1 + index * 0.1)}
                className={cn("block h-1.5 origin-left rounded-full bg-content-secondary", width)}
              />
            ))}
          </div>
        </div>
        <div>
          <p className={illustrationLabel}>Table</p>
          <div className="mt-2 grid grid-cols-2 gap-1">
            {Array.from({ length: tableCells }, (_, index) => (
              <span
                key={index}
                {...enter("fade", 1.4 + index * 0.05)}
                className="h-2 rounded-sm bg-border-strong"
              />
            ))}
          </div>
        </div>
        <div>
          <p className={illustrationLabel}>Chart</p>
          <div className="mt-2 flex h-9 items-end gap-1 border-b border-border-default">
            {bars.map((share, index) => (
              <span
                key={index}
                {...enter("growY", 1.7 + index * 0.08)}
                className="flex-1 origin-bottom rounded-t-sm bg-accent"
                style={{ height: `${share * 100}%` }}
              />
            ))}
          </div>
        </div>
      </div>
      <p {...enter("fade", 2.2)} className={illustrationLabel}>
        Vietnamese and English
      </p>
    </div>
  );
}

export { FilesMock };
