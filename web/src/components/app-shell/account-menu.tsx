import { LogOut, Moon, Settings2, Sun } from "lucide-react";
import { useState } from "react";
import { Popover } from "radix-ui";
import { MenuItem } from "@/components/ui/menu-item";
import { SidebarTab } from "@/components/ui/sidebar-tab";
import { useTheme } from "@/features/theme/theme-context";
import { useApplicationSession, useCan } from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";

const logoutLocationHeader = "X-MemoryOS-Logout-Location";

export function AccountMenu({ collapsed }: { collapsed: boolean }) {
  const [menuOpen, setMenuOpen] = useState(false);
  const { resolvedTheme, setTheme } = useTheme();
  const { organization } = useApplicationSession();
  const canAccessAdmin = useCan("INVITATIONS_MANAGE");
  const membershipLabel =
    organization.role === "OWNER" ? "Organization owner" : "Organization member";
  const initials = organization.displayName
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join("");
  const isDark = resolvedTheme === "dark";
  const [signOutState, setSignOutState] = useState<"idle" | "pending" | "error">("idle");
  const signingOut = signOutState === "pending";

  async function requestSignOut() {
    if (signingOut) return;
    setSignOutState("pending");
    try {
      const response = await fetch("/logout", {
        method: "POST",
        credentials: "same-origin",
        headers: sameOriginMutationHeaders,
      });
      const providerLogoutUrl = response.headers.get(logoutLocationHeader);
      if (response.status !== 204 || !providerLogoutUrl) {
        throw new Error("Session logout failed");
      }
      window.location.assign(providerLogoutUrl);
    } catch {
      setSignOutState("error");
    }
  }

  return (
    <Popover.Root open={menuOpen} onOpenChange={setMenuOpen}>
      <Popover.Trigger asChild>
        <SidebarTab
          icon={
            <span className="grid size-4 place-items-center rounded-full bg-surface-raised font-figure-small-label text-content-primary ring-1 ring-border-default">
              {initials}
            </span>
          }
          selected={menuOpen}
          collapsed={collapsed}
        >
          {membershipLabel}
        </SidebarTab>
      </Popover.Trigger>

      <Popover.Portal>
        <Popover.Content
          side="right"
          align="end"
          sideOffset={10}
          className="z-50 w-72 rounded-2xl border border-border-default bg-surface-overlay p-2 shadow-md outline-none data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out data-[state=open]:fade-in"
        >
          <p className="px-3 pt-2 font-main-ui-body text-content-primary">
            {organization.displayName}
          </p>
          <p className="px-3 pb-2 font-secondary-body text-content-secondary">{membershipLabel}</p>
          <div className="mt-1 border-t border-border-subtle pt-1">
            <MenuItem
              icon={isDark ? <Sun className="size-4.5" /> : <Moon className="size-4.5" />}
              onClick={() => setTheme(isDark ? "light" : "dark")}
            >
              {isDark ? "Use light theme" : "Use dark theme"}
            </MenuItem>
            {canAccessAdmin ? (
              <MenuItem
                to="/admin"
                icon={<Settings2 className="size-4.5" />}
                onClick={() => setMenuOpen(false)}
              >
                Admin Panel
              </MenuItem>
            ) : null}
          </div>
          <div className="mt-1 border-t border-border-subtle pt-1">
            <MenuItem
              icon={<LogOut className="size-4.5" />}
              tone="danger"
              disabled={signingOut}
              onClick={() => void requestSignOut()}
            >
              {signingOut ? "Signing out…" : "Sign out"}
            </MenuItem>
            {signOutState === "error" ? (
              <p
                className="px-3 pb-2 pt-1 font-secondary-body text-status-danger-content"
                role="alert"
              >
                We couldn't sign you out. Try again.
              </p>
            ) : null}
          </div>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}
