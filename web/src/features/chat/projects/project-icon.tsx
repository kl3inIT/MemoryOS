import { useAppTranslation } from "@/i18n/use-app-translation";
import { Folder } from "lucide-react";
import { InputGroupButton } from "@/components/ui/input-group";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { agentIcons, agentIconTones } from "@/features/agents/agent-icons";
import { cn } from "@/lib/utils";

/** The project's chosen topic icon, or the plain folder when none was picked. */
export function ProjectIcon({
  iconName,
  className,
}: {
  iconName?: string | null;
  className?: string;
}) {
  const Icon = (iconName && agentIcons[iconName]) || Folder;
  return <Icon className={className} aria-hidden="true" />;
}

const tile =
  "grid size-9 place-items-center rounded-lg outline-none transition-shadow focus-visible:ring-3 focus-visible:ring-focus-ring/40";
const chosenTile = "ring-2 ring-content-primary ring-offset-2 ring-offset-surface-overlay";
const otherTile = "hover:ring-1 hover:ring-border-default";

/** Small icon button inside the name field that opens the shared topic-icon grid. */
export function ProjectIconPicker({
  iconName,
  onIcon,
}: {
  iconName: string;
  onIcon: (key: string) => void;
}) {
  const ui = useAppTranslation();
  const iconNames: Record<string, string> = {
    bot: ui("Trợ lý"),
    chart: ui("Biểu đồ"),
    finance: ui("Tài chính"),
    calculator: ui("Máy tính"),
    people: ui("Nhân sự"),
    legal: ui("Pháp chế"),
    document: ui("Tài liệu"),
    book: ui("Sổ tay"),
    briefcase: ui("Công việc"),
    search: ui("Tìm kiếm"),
    idea: ui("Ý tưởng"),
    shield: ui("An toàn"),
    chat: ui("Hỏi đáp"),
  };
  return (
    <Popover>
      <PopoverTrigger asChild>
        <InputGroupButton size="icon-xs" aria-label={ui("Đổi biểu tượng")}>
          <ProjectIcon iconName={iconName} />
        </InputGroupButton>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-auto">
        <p className="font-secondary-action text-content-muted">{ui("Biểu tượng")}</p>
        <div role="radiogroup" aria-label={ui("Biểu tượng")} className="grid grid-cols-7 gap-1.5">
          <button
            type="button"
            role="radio"
            aria-checked={iconName === ""}
            aria-label={ui("Thư mục")}
            onClick={() => onIcon("")}
            className={cn(tile, agentIconTones.bot, iconName === "" ? chosenTile : otherTile)}
          >
            <Folder aria-hidden="true" className="size-4.5" strokeWidth={1.75} />
          </button>
          {Object.entries(agentIcons).map(([key, Icon]) => (
            <button
              key={key}
              type="button"
              role="radio"
              aria-checked={iconName === key}
              aria-label={iconNames[key] ?? ui("Biểu tượng")}
              onClick={() => onIcon(key)}
              className={cn(tile, agentIconTones[key], iconName === key ? chosenTile : otherTile)}
            >
              <Icon aria-hidden="true" className="size-4.5" strokeWidth={1.75} />
            </button>
          ))}
        </div>
      </PopoverContent>
    </Popover>
  );
}
