import { Mic } from "lucide-react";
import { SidebarTab } from "@/components/ui/sidebar-tab";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useActiveMeeting } from "./meeting-session";

/** The sidebar entry; while a meeting records, a red dot shows it from anywhere in the app. */
export function MeetingsTab({
  collapsed,
  selected,
  onNavigate,
}: {
  collapsed?: boolean;
  selected: boolean;
  onNavigate?: () => void;
}) {
  const ui = useAppTranslation();
  const live = useActiveMeeting();
  return (
    <SidebarTab
      to="/meetings"
      icon={
        <span className="relative">
          <Mic className="size-4" />
          {live && (
            <span className="absolute -top-0.5 -right-0.5 size-2 animate-pulse rounded-full bg-status-danger-strong motion-reduce:animate-none" />
          )}
        </span>
      }
      collapsed={collapsed}
      selected={selected}
      onClick={onNavigate}
      aria-description={live ? ui("Đang ghi một cuộc họp") : undefined}
    >
      {ui("Cuộc họp")}
    </SidebarTab>
  );
}
