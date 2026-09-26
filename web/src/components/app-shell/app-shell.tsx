import { useAppTranslation } from "@/i18n/use-app-translation";
import { Link, useMatch, useMatchRoute, type LinkProps } from "@tanstack/react-router";
import {
  ArrowLeft,
  Blocks,
  ChartColumn,
  HardDrive,
  Menu,
  MessageSquare,
  PanelLeftClose,
  PanelLeftOpen,
  Settings,
  UserRound,
  X,
  type LucideIcon,
} from "lucide-react";
import { type ReactNode, useState } from "react";
import { Dialog } from "radix-ui";
import { AccountMenu } from "@/components/app-shell/account-menu";
import {
  adminGroups,
  adminPages,
  useCurrentAdminPage,
  type AdminPage,
} from "@/components/app-shell/admin-pages";
import { AppShellHeaderContent } from "@/components/app-shell/app-shell-header";
import { AppShellHeaderSlot } from "@/components/app-shell/app-shell-header-slot";
import {
  useSourceSetupProgress,
  type SourceSetupProgress,
} from "@/components/app-shell/source-setup-progress";
import { Brand } from "@/components/brand";
import { IconButton } from "@/components/ui/icon-button";
import { SidebarSection } from "@/components/ui/sidebar-section";
import { SidebarTab } from "@/components/ui/sidebar-tab";
import { useAdminAccess } from "@/features/identity/application-session-context";
import { AccessDeniedScreen } from "@/features/identity/session-states";
import { appText, type AppText } from "@/i18n/app-text";
import { cn } from "@/lib/utils";
import { ChatHistorySearch } from "@/features/chat/session/chat-history-search";
import { ChatNavigation } from "@/features/chat/session/chat-navigation";
import { MeetingsTab } from "@/features/meetings/meetings-tab";

type AppShellArea = "app" | "admin" | "settings";
/** Personal settings tabs, as Onyx Settings (MEM-145). */
type SettingsPage = "general" | "chat" | "storage" | "connections" | "usage";

type SettingsEntry = { id: SettingsPage; to: LinkProps["to"]; label: AppText; icon: LucideIcon };

const generalSettings: SettingsEntry = {
  id: "general",
  to: "/settings/general",
  label: appText("General"),
  icon: UserRound,
};

const settingsPages: readonly SettingsEntry[] = [
  generalSettings,
  { id: "chat", to: "/settings/chat", label: appText("Chat"), icon: MessageSquare },
  { id: "storage", to: "/settings/storage", label: appText("Bộ nhớ lưu trữ"), icon: HardDrive },
  { id: "connections", to: "/settings/connections", label: appText("Connections"), icon: Blocks },
  { id: "usage", to: "/settings/usage", label: appText("Usage"), icon: ChartColumn },
];

/** The personal settings tab of the current route; General owns the settings index. */
function useCurrentSettingsPage() {
  const matchRoute = useMatchRoute();
  return settingsPages.find((page) => matchRoute({ to: page.to }) !== false) ?? generalSettings;
}

type SidebarContentsProps = {
  area: AppShellArea;
  adminPage: AdminPage;
  settingsPage: SettingsPage;
  sourceSetup?: SourceSetupProgress;
  collapsed?: boolean;
  onCollapseToggle?: () => void;
  onNavigate?: () => void;
  mobile?: boolean;
};

function SourceSetupSidebarSteps({ steps, current }: SourceSetupProgress) {
  const ui = useAppTranslation();

  return (
    <ol className="mx-2 mt-2 flex flex-col" aria-label={ui("Connector setup progress")}>
      {steps.map((label, index) => (
        <li
          key={label}
          aria-current={current === index ? "step" : undefined}
          className={cn(
            "relative flex h-9 items-center gap-0.5 font-main-ui-body",
            index > current ? "text-content-muted" : "text-content-primary",
          )}
        >
          {index > 0 && (
            <span
              aria-hidden="true"
              className={cn(
                "absolute -top-4.5 left-2 h-9 w-0.5",
                index <= current ? "bg-status-info-content" : "bg-border-default",
              )}
            />
          )}
          <span
            className="flex min-h-5 shrink-0 items-center justify-center p-0.5"
            aria-hidden="true"
          >
            <span
              className={cn(
                "z-10 flex size-3.5 shrink-0 items-center justify-center rounded-full",
                index > current ? "bg-border-default" : "bg-status-info-content",
              )}
            >
              {current === index && <span className="size-1.5 rounded-full bg-(--neutral-00)" />}
            </span>
          </span>
          <span>{ui(label)}</span>
          <span className="sr-only">
            {index < current
              ? ui("Completed")
              : index > current
                ? ui("Not started")
                : ui("Current step")}
          </span>
        </li>
      ))}
    </ol>
  );
}

function SidebarContents({
  area,
  adminPage,
  settingsPage,
  sourceSetup,
  collapsed = false,
  onCollapseToggle,
  onNavigate,
  mobile = false,
}: SidebarContentsProps) {
  const ui = useAppTranslation();

  const appArea = area === "app";
  const { authority, canAccessAdmin, adminEntryPath } = useAdminAccess();

  return (
    <div className="flex h-full min-h-0 flex-col pb-2">
      <header
        className={cn(
          "flex shrink-0 gap-2",
          sourceSetup !== undefined ? "items-start pt-3" : "min-h-13 items-center pt-1",
          collapsed ? "px-1" : "px-3",
        )}
      >
        {collapsed && !mobile ? (
          <IconButton
            prominence="internal"
            size="sm"
            aria-label={ui("Expand sidebar")}
            title={ui("Expand sidebar")}
            onClick={onCollapseToggle}
            className="group relative mx-auto"
          >
            <span className="transition-opacity group-hover:opacity-0 group-focus-visible:opacity-0">
              <Brand compact />
            </span>
            <PanelLeftOpen className="absolute opacity-0 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100" />
          </IconButton>
        ) : (
          <>
            <Link
              to="/"
              aria-label={ui("Home")}
              className="flex min-w-0 flex-1 items-center rounded-lg outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
              onClick={onNavigate}
            >
              <Brand />
            </Link>
            {appArea && sourceSetup === undefined ? (
              <ChatHistorySearch variant="icon" onNavigate={onNavigate} />
            ) : null}
            {mobile ? (
              <Dialog.Close asChild>
                <IconButton prominence="internal" size="md" aria-label={ui("Close navigation")}>
                  <X />
                </IconButton>
              </Dialog.Close>
            ) : sourceSetup === undefined ? (
              <IconButton
                prominence="internal"
                size="sm"
                aria-label={ui("Collapse sidebar")}
                title={ui("Collapse sidebar")}
                onClick={onCollapseToggle}
              >
                <PanelLeftClose />
              </IconButton>
            ) : null}
          </>
        )}
      </header>

      <nav
        aria-label={
          sourceSetup !== undefined
            ? ui("Connector setup")
            : appArea
              ? ui("Primary navigation")
              : area === "settings"
                ? ui("Settings navigation")
                : ui("Administration navigation")
        }
        className={cn("min-h-0 flex-1 overflow-y-auto px-2", sourceSetup === undefined && "pt-4")}
      >
        {sourceSetup !== undefined ? (
          <SourceSetupSidebarSteps {...sourceSetup} />
        ) : appArea ? (
          <ChatNavigation
            collapsed={collapsed}
            onNavigate={onNavigate}
            meetingsTab={<MeetingsTab collapsed={collapsed} onNavigate={onNavigate} />}
          />
        ) : area === "settings" ? (
          <SidebarSection title={ui("Settings")} collapsed={collapsed}>
            {settingsPages.map((page) => (
              <SidebarTab
                key={page.id}
                to={page.to}
                icon={<page.icon className="size-4" />}
                selected={settingsPage === page.id}
                collapsed={collapsed}
                onClick={onNavigate}
              >
                {ui(page.label)}
              </SidebarTab>
            ))}
          </SidebarSection>
        ) : (
          // Each section already pads its own heading, so the menu fits a laptop screen at this gap.
          <div className="space-y-4">
            {adminGroups.map((group) => {
              const pages = adminPages.filter(
                (page) => page.group === group.id && page.visible(authority),
              );
              return pages.length > 0 ? (
                <SidebarSection key={group.id} title={ui(group.label)} collapsed={collapsed}>
                  {pages.map((page) => (
                    <SidebarTab
                      key={page.id}
                      to={page.to}
                      // Without this the router marks the Sources tab current on every page under /admin.
                      activeOptions={page.id === "sources" ? { exact: true } : undefined}
                      icon={<page.icon className="size-4" />}
                      selected={adminPage === page.id}
                      collapsed={collapsed}
                      onClick={onNavigate}
                    >
                      {ui(page.label)}
                    </SidebarTab>
                  ))}
                </SidebarSection>
              ) : null;
            })}
          </div>
        )}
      </nav>

      <footer className="shrink-0 px-2 pt-4">
        {!collapsed && <div className="mx-2 mb-2 border-t border-border-subtle" />}
        {sourceSetup !== undefined ? (
          <SidebarTab
            to="/admin/sources/new"
            icon={<X className="size-4" />}
            variant="light"
            onClick={onNavigate}
          >
            {ui("Exit Connector Setup")}
          </SidebarTab>
        ) : !appArea ? (
          <SidebarTab
            to="/"
            icon={<ArrowLeft className="size-4" />}
            collapsed={collapsed}
            variant="light"
            onClick={onNavigate}
          >
            {ui("Back to MemoryOS")}
          </SidebarTab>
        ) : null}
        {sourceSetup === undefined && appArea && canAccessAdmin ? (
          <div className="mb-1">
            <SidebarTab
              to={adminEntryPath}
              icon={<Settings className="size-4" />}
              collapsed={collapsed}
              variant="light"
              onClick={onNavigate}
            >
              {ui("Admin Panel")}
            </SidebarTab>
          </div>
        ) : null}
        {sourceSetup === undefined && <AccountMenu collapsed={collapsed} onNavigate={onNavigate} />}
      </footer>
    </div>
  );
}

/**
 * The authenticated application frame: the sidebar of the area the route is in, the header and the main region. It is
 * rendered once by the authenticated layout, so navigating keeps it mounted; application pages put their title and
 * actions into its header with `AppShellHeader`, administration and settings take theirs from their page tables.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const ui = useAppTranslation();
  const admin = useMatch({ from: "/_authenticated/admin", shouldThrow: false }) !== undefined;
  const settings = useMatch({ from: "/_authenticated/settings", shouldThrow: false }) !== undefined;
  const area: AppShellArea = admin ? "admin" : settings ? "settings" : "app";
  const adminPage = useCurrentAdminPage();
  const settingsPage = useCurrentSettingsPage();
  const { authority } = useAdminAccess();
  const setupProgress = useSourceSetupProgress();
  const sourceSetup = admin ? setupProgress : undefined;
  const pageTitle =
    area === "admin"
      ? ui(adminPage.title)
      : area === "settings"
        ? ui(settingsPage.label)
        : undefined;

  const [collapsed, setCollapsed] = useState(false);
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false);
  const [headerSlot, setHeaderSlot] = useState<HTMLElement | null>(null);
  const sidebarCollapsed = sourceSetup === undefined && collapsed;

  // An administration page the person may not open is refused before any administration frame renders.
  if (area === "admin" && !adminPage.visible(authority)) return <AccessDeniedScreen />;

  return (
    <div className="flex h-dvh min-h-0 overflow-hidden bg-surface-canvas text-content-primary">
      <a
        href="#main-content"
        className="sr-only z-60 rounded-lg bg-surface-base px-3 py-2 font-main-ui-body shadow-md focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:ring-3 focus:ring-ring/50"
      >
        {ui("Skip to content")}
      </a>

      <aside
        aria-label={
          sourceSetup !== undefined
            ? ui("Connector setup sidebar")
            : area === "app"
              ? ui("Application sidebar")
              : area === "settings"
                ? ui("Settings sidebar")
                : ui("Administration sidebar")
        }
        className={cn(
          "relative hidden h-dvh shrink-0 overflow-hidden bg-surface-canvas transition-all duration-200 motion-reduce:transition-none md:block",
          sidebarCollapsed ? "w-(--sidebar-width-collapsed)" : "w-(--sidebar-width)",
        )}
      >
        <SidebarContents
          area={area}
          adminPage={adminPage.id}
          settingsPage={settingsPage.id}
          sourceSetup={sourceSetup}
          collapsed={sidebarCollapsed}
          onCollapseToggle={() => setCollapsed((current) => !current)}
        />
      </aside>

      <section className="flex min-w-0 flex-1 flex-col overflow-hidden bg-surface-base">
        <Dialog.Root open={mobileNavigationOpen} onOpenChange={setMobileNavigationOpen}>
          <header
            role="banner"
            className={cn(
              "flex shrink-0 items-center gap-3 border-b border-border-subtle bg-surface-base px-3",
              sourceSetup === undefined && area === "app" ? "h-14" : "h-13 md:hidden",
            )}
          >
            <Dialog.Trigger asChild>
              <IconButton
                prominence="internal"
                size="md"
                aria-label={ui("Open navigation")}
                className="md:hidden"
              >
                <Menu />
              </IconButton>
            </Dialog.Trigger>
            {pageTitle === undefined ? (
              <div ref={setHeaderSlot} className="contents" />
            ) : (
              <AppShellHeaderContent title={pageTitle} />
            )}
          </header>

          <Dialog.Portal>
            <Dialog.Overlay className="fixed inset-0 z-40 bg-surface-scrim backdrop-blur-2xs data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out data-[state=open]:fade-in motion-reduce:animate-none" />
            <Dialog.Content
              aria-describedby={undefined}
              className="fixed inset-y-0 left-0 z-50 w-[min(var(--sidebar-width),86vw)] border-r border-border-subtle bg-surface-canvas shadow-md outline-none data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:slide-out-to-left data-[state=open]:slide-in-from-left motion-reduce:animate-none"
            >
              <Dialog.Title className="sr-only">{ui("Navigation")}</Dialog.Title>
              <SidebarContents
                area={area}
                adminPage={adminPage.id}
                settingsPage={settingsPage.id}
                sourceSetup={sourceSetup}
                mobile
                onNavigate={() => setMobileNavigationOpen(false)}
              />
            </Dialog.Content>
          </Dialog.Portal>
        </Dialog.Root>

        <AppShellHeaderSlot value={headerSlot}>
          <main
            id="main-content"
            tabIndex={-1}
            className="min-h-0 min-w-0 flex-1 overflow-auto outline-none scrollbar-stable"
          >
            {children}
          </main>
        </AppShellHeaderSlot>
      </section>
    </div>
  );
}
