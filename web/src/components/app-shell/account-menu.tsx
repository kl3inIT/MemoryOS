import { Moon, Settings2, Sun } from "lucide-react";
import { useState } from "react";
import { Popover } from "radix-ui";
import { MenuItem } from "@/components/ui/menu-item";
import { SidebarTab } from "@/components/ui/sidebar-tab";
import { useTheme } from "@/features/theme/theme-context";

export function AccountMenu({ collapsed }: { collapsed: boolean }) {
  const [open, setOpen] = useState(false);
  const { resolvedTheme, setTheme } = useTheme();
  const isDark = resolvedTheme === "dark";

  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger asChild>
        <SidebarTab
          icon={
            <span className="grid size-4 place-items-center rounded-full bg-surface-raised font-figure-small-label text-content-primary ring-1 ring-border-default">
              OW
            </span>
          }
          selected={open}
          collapsed={collapsed}
        >
          Workspace owner
        </SidebarTab>
      </Popover.Trigger>

      <Popover.Portal>
        <Popover.Content
          side="right"
          align="end"
          sideOffset={10}
          className="z-50 w-72 rounded-2xl border border-border-default bg-surface-overlay p-2 shadow-md outline-none data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out data-[state=open]:fade-in"
        >
          <p className="px-3 py-2 font-main-ui-body text-content-primary">Workspace owner</p>
          <div className="mt-1 border-t border-border-subtle pt-1">
            <MenuItem
              icon={isDark ? <Sun className="size-4.5" /> : <Moon className="size-4.5" />}
              onClick={() => setTheme(isDark ? "light" : "dark")}
            >
              {isDark ? "Use light theme" : "Use dark theme"}
            </MenuItem>
            <MenuItem href="/admin" icon={<Settings2 className="size-4.5" />}>
              Admin Panel
            </MenuItem>
          </div>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}
