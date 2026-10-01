import { LogOut, Moon, Settings, Settings2, Sun } from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "@tanstack/react-router";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { SidebarMenuButton } from "@/components/ui/sidebar";
import { useTheme } from "@/features/theme/theme-context";
import {
  useAdminAccess,
  useApplicationSession,
} from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";

const logoutLocationHeader = "X-MemoryOS-Logout-Location";

export function AccountMenu({ onNavigate }: { onNavigate?: () => void }) {
  const { t } = useTranslation("common");
  const [menuOpen, setMenuOpen] = useState(false);
  const { resolvedTheme, setTheme } = useTheme();
  const { displayName, tenant } = useApplicationSession();
  const { canAccessAdmin, adminEntryPath } = useAdminAccess();
  const membershipLabel = t(tenant.role === "OWNER" ? "owner" : "member");
  const accountDisplayName = displayName ?? tenant.displayName;
  const initials = accountDisplayName
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
      if (response.status !== 204) {
        throw new Error("Session logout failed");
      }
      // The server ends the identity-provider session itself and names a provider logout page only when it could not.
      window.location.assign(response.headers.get(logoutLocationHeader) ?? "/");
    } catch {
      setSignOutState("error");
    }
  }

  return (
    <DropdownMenu open={menuOpen} onOpenChange={setMenuOpen}>
      <DropdownMenuTrigger asChild>
        <SidebarMenuButton tooltip={membershipLabel}>
          <span
            aria-hidden="true"
            className="grid size-4 shrink-0 place-items-center rounded-full bg-surface-raised font-figure-small-label text-content-primary ring-1 ring-border-default"
          >
            {initials}
          </span>
          <span>{membershipLabel}</span>
        </SidebarMenuButton>
      </DropdownMenuTrigger>

      <DropdownMenuContent
        side="right"
        align="end"
        sideOffset={10}
        collisionPadding={12}
        className="w-64 max-w-[calc(100vw-1.5rem)]"
      >
        <DropdownMenuLabel className="flex flex-col">
          <span className="font-main-ui-body break-words text-content-primary">
            {accountDisplayName}
          </span>
          <span className="font-secondary-body text-content-muted">{membershipLabel}</span>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuGroup>
          <DropdownMenuItem
            onSelect={(event) => {
              // The theme changes in place, so the menu stays open on the switched item.
              event.preventDefault();
              setTheme(isDark ? "light" : "dark");
            }}
          >
            {isDark ? <Sun /> : <Moon />}
            {t(isDark ? "lightTheme" : "darkTheme")}
          </DropdownMenuItem>
          <DropdownMenuItem asChild onSelect={() => onNavigate?.()}>
            <Link to="/settings/general">
              <Settings2 />
              {t("settings")}
            </Link>
          </DropdownMenuItem>
          {canAccessAdmin ? (
            <DropdownMenuItem asChild onSelect={() => onNavigate?.()}>
              <Link to={adminEntryPath}>
                <Settings />
                {t("admin")}
              </Link>
            </DropdownMenuItem>
          ) : null}
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <DropdownMenuGroup>
          <DropdownMenuItem
            variant="destructive"
            disabled={signingOut}
            onSelect={(event) => {
              // Sign-out stays in the menu, so a failure is shown where it was asked for.
              event.preventDefault();
              void requestSignOut();
            }}
          >
            <LogOut />
            {t(signingOut ? "signingOut" : "signOut")}
          </DropdownMenuItem>
        </DropdownMenuGroup>
        {signOutState === "error" ? (
          <p className="px-1.5 py-1 font-secondary-body text-status-danger-content" role="alert">
            {t("signOutFailed")}
          </p>
        ) : null}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
