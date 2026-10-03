import { useAppTranslation } from "@/i18n/use-app-translation";
import { Link, useMatch, useMatchRoute, type LinkProps } from "@tanstack/react-router";
import {
  ArrowLeft,
  Blocks,
  Cable,
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
import { type ReactNode, useEffect, useId, useRef, useState } from "react";
import { AccountMenu } from "@/components/app-shell/account-menu";
import {
  adminGroups,
  adminPages,
  useCurrentAdminPage,
  type AdminPage,
} from "@/components/app-shell/admin-pages";
import { AppShellHeaderContent } from "@/components/app-shell/app-shell-header";
import { AppShellHeaderSlot } from "@/components/app-shell/app-shell-header-slot";
import { SidebarLink } from "@/components/app-shell/sidebar-link";
import {
  useSourceSetupProgress,
  type SourceSetupProgress,
} from "@/components/app-shell/source-setup-progress";
import { Brand } from "@/components/brand";
import { IconButton } from "@/components/ui/icon-button";
import { SheetClose } from "@/components/ui/sheet";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuItem,
  SidebarProvider,
  SidebarSeparator,
  useSidebar,
} from "@/components/ui/sidebar";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { useAdminAccess } from "@/features/identity/application-session-context";
import { AccessDeniedScreen } from "@/features/identity/session-states";
import { appText, type AppText } from "@/i18n/app-text";
import { cn } from "@/lib/utils";
import { ChatHistorySearch } from "@/features/chat/session/chat-history-search";
import { ChatNavigation } from "@/features/chat/session/chat-navigation";
import { MeetingsTab } from "@/features/meetings/meetings-tab";

type AppShellArea = "app" | "admin" | "settings";
/** Personal settings tabs, as Onyx Settings (MEM-145). */
type SettingsPage = "general" | "chat" | "storage" | "connections" | "mcp" | "usage";

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
  { id: "mcp", to: "/settings/mcp", label: appText("MemoryOS MCP"), icon: Cable },
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
};

function SourceSetupSidebarSteps({ steps, current }: SourceSetupProgress) {
  const ui = useAppTranslation();

  return (
    <ol className="mx-2 flex flex-col" aria-label={ui("Connector setup progress")}>
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

/** A titled section of the sidebar menu, named by its heading; folded to the rail only the rows remain. */
function NavigationGroup({ title, children }: { title: string; children: ReactNode }) {
  const headingId = useId();
  return (
    <SidebarGroup role="group" aria-labelledby={headingId}>
      <SidebarGroupLabel asChild>
        <h2 id={headingId}>{title}</h2>
      </SidebarGroupLabel>
      <SidebarMenu>{children}</SidebarMenu>
    </SidebarGroup>
  );
}

function SidebarContents({ area, adminPage, settingsPage, sourceSetup }: SidebarContentsProps) {
  const ui = useAppTranslation();
  const { state, isMobile, setOpenMobile, toggleSidebar } = useSidebar();
  const collapsed = !isMobile && state === "collapsed";
  const onNavigate = isMobile ? () => setOpenMobile(false) : undefined;

  const appArea = area === "app";
  const { authority, canAccessAdmin, adminEntryPath } = useAdminAccess();
  const expandLabel = ui("Expand sidebar");

  // The administration menu outgrows a laptop screen, and its resting scrollbar is invisible: on every navigation
  // the open page's own link scrolls into view, and nothing moves when it is already visible.
  const navigation = useRef<HTMLElement>(null);
  useEffect(() => {
    if (appArea) return;
    navigation.current
      ?.querySelector<HTMLElement>('[aria-current="page"]')
      ?.scrollIntoView?.({ block: "nearest" });
  }, [appArea, adminPage, settingsPage]);

  return (
    <>
      <SidebarHeader>
        {collapsed ? (
          <Tooltip>
            <TooltipTrigger asChild>
              <IconButton
                prominence="internal"
                size="sm"
                aria-label={expandLabel}
                onClick={toggleSidebar}
                className="group/expand relative mx-auto"
              >
                <span className="transition-opacity group-hover/expand:opacity-0 group-focus-visible/expand:opacity-0">
                  <Brand compact />
                </span>
                <PanelLeftOpen className="absolute opacity-0 transition-opacity group-hover/expand:opacity-100 group-focus-visible/expand:opacity-100" />
              </IconButton>
            </TooltipTrigger>
            <TooltipContent side="right">{expandLabel}</TooltipContent>
          </Tooltip>
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
            {isMobile ? (
              <SheetClose asChild>
                <IconButton prominence="internal" size="md" aria-label={ui("Close navigation")}>
                  <X />
                </IconButton>
              </SheetClose>
            ) : sourceSetup === undefined ? (
              <IconButton
                prominence="internal"
                size="sm"
                aria-label={ui("Collapse sidebar")}
                title={ui("Collapse sidebar")}
                aria-keyshortcuts="Control+B Meta+B"
                onClick={toggleSidebar}
              >
                <PanelLeftClose />
              </IconButton>
            ) : null}
          </>
        )}
      </SidebarHeader>

      <SidebarContent>
        <nav
          ref={navigation}
          aria-label={
            sourceSetup !== undefined
              ? ui("Connector setup")
              : appArea
                ? ui("Primary navigation")
                : area === "settings"
                  ? ui("Settings navigation")
                  : ui("Administration navigation")
          }
          // Each group pads its own heading, so the administration menu fits a laptop screen at this gap.
          className="flex min-h-0 flex-1 flex-col gap-4"
        >
          {sourceSetup !== undefined ? (
            <SourceSetupSidebarSteps {...sourceSetup} />
          ) : appArea ? (
            <ChatNavigation
              collapsed={collapsed}
              onNavigate={onNavigate}
              meetingsTab={<MeetingsTab onNavigate={onNavigate} />}
            />
          ) : area === "settings" ? (
            <NavigationGroup title={ui("Settings")}>
              {settingsPages.map((page) => (
                <SidebarLink
                  key={page.id}
                  to={page.to}
                  label={ui(page.label)}
                  icon={<page.icon />}
                  selected={settingsPage === page.id}
                  onNavigate={onNavigate}
                />
              ))}
            </NavigationGroup>
          ) : (
            adminGroups.map((group) => {
              const pages = adminPages.filter(
                (page) => page.group === group.id && page.visible(authority),
              );
              return pages.length > 0 ? (
                <NavigationGroup key={group.id} title={ui(group.label)}>
                  {pages.map((page) => (
                    <SidebarLink
                      key={page.id}
                      to={page.to}
                      label={ui(page.label)}
                      // Without this the router marks the Sources tab current on every page under /admin.
                      activeOptions={page.id === "sources" ? { exact: true } : undefined}
                      icon={<page.icon />}
                      selected={adminPage === page.id}
                      onNavigate={onNavigate}
                    />
                  ))}
                </NavigationGroup>
              ) : null;
            })
          )}
        </nav>
      </SidebarContent>

      <SidebarFooter>
        <SidebarSeparator className="mb-1" />
        <SidebarMenu>
          {sourceSetup !== undefined ? (
            <SidebarLink
              to="/admin/sources/new"
              label={ui("Exit Connector Setup")}
              icon={<X />}
              variant="light"
              onNavigate={onNavigate}
            />
          ) : !appArea ? (
            <SidebarLink
              to="/"
              label={ui("Back to the app")}
              icon={<ArrowLeft />}
              variant="light"
              onNavigate={onNavigate}
            />
          ) : canAccessAdmin ? (
            <SidebarLink
              to={adminEntryPath}
              label={ui("Admin Panel")}
              icon={<Settings />}
              variant="light"
              onNavigate={onNavigate}
            />
          ) : null}
          {sourceSetup === undefined && (
            <SidebarMenuItem>
              <AccountMenu onNavigate={onNavigate} />
            </SidebarMenuItem>
          )}
        </SidebarMenu>
      </SidebarFooter>
    </>
  );
}

/** The drawer's trigger on narrow screens, where the header replaces the sidebar. */
function OpenNavigationButton() {
  const ui = useAppTranslation();
  const { openMobile, setOpenMobile } = useSidebar();
  return (
    <IconButton
      prominence="internal"
      size="md"
      aria-label={ui("Open navigation")}
      aria-haspopup="dialog"
      aria-expanded={openMobile}
      className="md:hidden"
      onClick={() => setOpenMobile(true)}
    >
      <Menu />
    </IconButton>
  );
}

// The setup steps need the full width, so the sidebar stays open and the fold keeps its remembered state.
const keepSetupOpen = () => {};

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

  const [headerSlot, setHeaderSlot] = useState<HTMLElement | null>(null);

  // An administration page the person may not open is refused before any administration frame renders.
  if (area === "admin" && !adminPage.visible(authority)) return <AccessDeniedScreen />;

  return (
    <SidebarProvider
      open={sourceSetup !== undefined ? true : undefined}
      onOpenChange={sourceSetup !== undefined ? keepSetupOpen : undefined}
      className="h-dvh min-h-0 overflow-hidden"
    >
      <a
        href="#main-content"
        className="sr-only z-60 rounded-lg bg-surface-base px-3 py-2 font-main-ui-body shadow-md focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:ring-3 focus:ring-ring/50"
      >
        {ui("Skip to content")}
      </a>

      <Sidebar
        collapsible="icon"
        aria-label={
          sourceSetup !== undefined
            ? ui("Connector setup sidebar")
            : area === "app"
              ? ui("Application sidebar")
              : area === "settings"
                ? ui("Settings sidebar")
                : ui("Administration sidebar")
        }
      >
        <SidebarContents
          area={area}
          adminPage={adminPage.id}
          settingsPage={settingsPage.id}
          sourceSetup={sourceSetup}
        />
      </Sidebar>

      <SidebarInset className="overflow-hidden">
        <header
          role="banner"
          className={cn(
            "flex shrink-0 items-center gap-3 border-b border-border-subtle bg-surface-base px-3",
            sourceSetup === undefined && area === "app"
              ? "h-14 md:has-data-page-header:hidden"
              : "h-13 md:hidden",
          )}
        >
          <OpenNavigationButton />
          {pageTitle === undefined ? (
            <div ref={setHeaderSlot} className="contents" />
          ) : (
            <AppShellHeaderContent title={pageTitle} />
          )}
        </header>

        <AppShellHeaderSlot value={headerSlot}>
          <main
            id="main-content"
            tabIndex={-1}
            className="min-h-0 min-w-0 flex-1 scrollbar-stable overflow-auto outline-none"
          >
            {children}
          </main>
        </AppShellHeaderSlot>
      </SidebarInset>
    </SidebarProvider>
  );
}
