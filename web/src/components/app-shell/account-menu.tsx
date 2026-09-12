import { LogOut, Moon, Settings2, Sun } from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Popover } from "radix-ui";
import { MenuItem } from "@/components/ui/menu-item";
import { SidebarTab } from "@/components/ui/sidebar-tab";
import { useTheme } from "@/features/theme/theme-context";
import {
  useAdminAccess,
  useApplicationSession,
} from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";

const logoutLocationHeader = "X-MemoryOS-Logout-Location";

export function AccountMenu({
  collapsed,
  onNavigate,
}: {
  collapsed: boolean;
  onNavigate?: () => void;
}) {
  const { t } = useTranslation("common");
  const [menuOpen, setMenuOpen] = useState(false);
  const { resolvedTheme, setTheme } = useTheme();
  const { tenant } = useApplicationSession();
  const { canAccessAdmin, adminEntryPath } = useAdminAccess();
  const membershipLabel = t(tenant.role === "OWNER" ? "owner" : "member");
  const initials = tenant.displayName
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
          collisionPadding={12}
          className="z-50 max-h-[var(--radix-popover-content-available-height)] w-64 max-w-[calc(100vw-1.5rem)] overflow-y-auto rounded-2xl border border-border-subtle bg-surface-overlay p-2 shadow-md outline-none data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out data-[state=open]:fade-in"
        >
          <p className="break-words px-2 pt-2 font-main-ui-body text-content-primary">
            {tenant.displayName}
          </p>
          <p className="px-2 pb-2 font-secondary-body text-content-muted">{membershipLabel}</p>
          <div className="mt-1 border-t border-border-subtle pt-1">
            <MenuItem
              icon={isDark ? <Sun className="size-4.5" /> : <Moon className="size-4.5" />}
              onClick={() => setTheme(isDark ? "light" : "dark")}
            >
              {t(isDark ? "lightTheme" : "darkTheme")}
            </MenuItem>
            <MenuItem
              to="/settings/general"
              icon={<Settings2 className="size-4.5" />}
              onClick={() => {
                setMenuOpen(false);
                onNavigate?.();
              }}
            >
              {t("settings")}
            </MenuItem>
            {canAccessAdmin ? (
              <MenuItem
                to={adminEntryPath}
                icon={<Settings2 className="size-4.5" />}
                onClick={() => {
                  setMenuOpen(false);
                  onNavigate?.();
                }}
              >
                {t("admin")}
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
              {t(signingOut ? "signingOut" : "signOut")}
            </MenuItem>
            {signOutState === "error" ? (
              <p
                className="px-3 pb-2 pt-1 font-secondary-body text-status-danger-content"
                role="alert"
              >
                {t("signOutFailed")}
              </p>
            ) : null}
          </div>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}
