import { Link, useRouterState } from "@tanstack/react-router";
import { Mic } from "lucide-react";
import { SidebarMenuButton, SidebarMenuItem } from "@/components/ui/sidebar";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useActiveMeeting } from "./meeting-session";

/** The sidebar entry; while a meeting records, a red dot shows it from anywhere in the app. */
export function MeetingsTab({ onNavigate }: { onNavigate?: () => void }) {
  const ui = useAppTranslation();
  const selected = useRouterState({
    select: (state) => state.location.pathname.startsWith("/meetings"),
  });
  const live = useActiveMeeting();
  const label = ui("Cuộc họp");
  return (
    <SidebarMenuItem>
      <SidebarMenuButton asChild isActive={selected} tooltip={label}>
        <Link
          to="/meetings"
          aria-current={selected ? "page" : undefined}
          aria-description={live ? ui("Đang ghi một cuộc họp") : undefined}
          onClick={onNavigate}
        >
          <span aria-hidden="true" className="relative shrink-0">
            <Mic />
            {live && (
              <span className="absolute -top-0.5 -right-0.5 size-2 animate-pulse rounded-full bg-status-danger-strong motion-reduce:animate-none" />
            )}
          </span>
          <span>{label}</span>
        </Link>
      </SidebarMenuButton>
    </SidebarMenuItem>
  );
}
