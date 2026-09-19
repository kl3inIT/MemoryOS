import { useAppTranslation } from "@/i18n/use-app-translation";
import { Link } from "@tanstack/react-router";
import {
  ArrowLeft,
  AudioLines,
  Blocks,
  Bot,
  Globe,
  ImageIcon,
  KeyRound,
  Menu,
  MessageSquare,
  PanelLeftClose,
  PanelLeftOpen,
  Plug,
  Settings,
  Sparkles,
  SquareTerminal,
  User,
  UserRound,
  Users,
  X,
  ReceiptText,
} from "lucide-react";
import { type ReactNode, useState } from "react";
import { Dialog } from "radix-ui";
import { AccountMenu } from "@/components/app-shell/account-menu";
import { Brand } from "@/components/brand";
import { IconButton } from "@/components/ui/icon-button";
import { SidebarSection } from "@/components/ui/sidebar-section";
import { SidebarTab } from "@/components/ui/sidebar-tab";
import { useAdminAccess } from "@/features/identity/application-session-context";
import { cn } from "@/lib/utils";
import { ChatHistorySearch } from "@/features/chat/chat-history-search";
import { ChatNavigation } from "@/features/chat/chat-navigation";

export type AppShellArea = "app" | "admin" | "settings";
/** Personal settings tabs, as Onyx Settings (MEM-145). */
export type SettingsPage = "general" | "chat";
export type AdminPage =
  | "sources"
  | "users"
  | "groups"
  | "web"
  | "voice"
  | "images"
  | "interpreter"
  | "providers"
  | "models"
  | "mcp"
  | "agents"
  | "costs";

type AppShellProps = {
  area?: AppShellArea;
  adminPage?: AdminPage;
  settingsPage?: SettingsPage;
  sourceSetup?: SourceSetupProgress;
  pageTitle: string;
  headerActions?: ReactNode;
  children: ReactNode;
};

type SidebarContentsProps = {
  area: AppShellArea;
  adminPage?: AdminPage;
  settingsPage?: SettingsPage;
  sourceSetup?: SourceSetupProgress;
  collapsed?: boolean;
  onCollapseToggle?: () => void;
  onNavigate?: () => void;
  mobile?: boolean;
};

/** Steps of a connector setup flow, shown in place of navigation while the flow is open. */
export type SourceSetupProgress = { steps: readonly string[]; current: number };

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
  adminPage = "sources",
  settingsPage = "general",
  sourceSetup,
  collapsed = false,
  onCollapseToggle,
  onNavigate,
  mobile = false,
}: SidebarContentsProps) {
  const ui = useAppTranslation();

  const appArea = area === "app";
  const {
    canManageUsers,
    canReadGroups,
    canReadSources,
    canManageModels,
    canManageProviders,
    canManageMcp,
    canManageAgents,
    canAccessAdmin,
    adminEntryPath,
  } = useAdminAccess();

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
              aria-label={ui("MemoryOS home")}
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
                className="text-content-secondary"
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
          <ChatNavigation collapsed={collapsed} onNavigate={onNavigate} />
        ) : area === "settings" ? (
          <SidebarSection title={ui("Settings")} collapsed={collapsed}>
            <SidebarTab
              to="/settings/general"
              icon={<UserRound className="size-4" />}
              selected={settingsPage === "general"}
              collapsed={collapsed}
              onClick={onNavigate}
            >
              {ui("General")}
            </SidebarTab>
            <SidebarTab
              to="/settings/chat"
              icon={<MessageSquare className="size-4" />}
              selected={settingsPage === "chat"}
              collapsed={collapsed}
              onClick={onNavigate}
            >
              {ui("Chat")}
            </SidebarTab>
          </SidebarSection>
        ) : (
          <div className="space-y-5">
            {canManageModels ? (
              <SidebarSection title={ui("Configuration")} collapsed={collapsed}>
                <SidebarTab
                  to="/admin/models"
                  icon={<Sparkles className="size-4" />}
                  selected={adminPage === "models"}
                  collapsed={collapsed}
                  onClick={onNavigate}
                >
                  {ui("Mô hình")}
                </SidebarTab>
                <SidebarTab
                  to="/admin/web-search"
                  icon={<Globe className="size-4" />}
                  selected={adminPage === "web"}
                  collapsed={collapsed}
                  onClick={onNavigate}
                >
                  {ui("Tìm kiếm Web")}
                </SidebarTab>
                <SidebarTab
                  to="/admin/voice"
                  icon={<AudioLines className="size-4" />}
                  selected={adminPage === "voice"}
                  collapsed={collapsed}
                  onClick={onNavigate}
                >
                  {ui("Giọng nói")}
                </SidebarTab>
                <SidebarTab
                  to="/admin/image-generation"
                  icon={<ImageIcon className="size-4" />}
                  selected={adminPage === "images"}
                  collapsed={collapsed}
                  onClick={onNavigate}
                >
                  {ui("Tạo ảnh")}
                </SidebarTab>
                <SidebarTab
                  to="/admin/code-interpreter"
                  icon={<SquareTerminal className="size-4" />}
                  selected={adminPage === "interpreter"}
                  collapsed={collapsed}
                  onClick={onNavigate}
                >
                  {ui("Code Interpreter")}
                </SidebarTab>
              </SidebarSection>
            ) : null}
            {canManageModels ? (
              <SidebarSection title={ui("Monitoring")} collapsed={collapsed}>
                <SidebarTab
                  to="/admin/ai-costs"
                  icon={<ReceiptText className="size-4" />}
                  selected={adminPage === "costs"}
                  collapsed={collapsed}
                  onClick={onNavigate}
                >
                  {ui("AI costs")}
                </SidebarTab>
              </SidebarSection>
            ) : null}
            {canManageAgents ? (
              <SidebarSection title={ui("Trợ lý")} collapsed={collapsed}>
                <SidebarTab
                  to="/admin/agents"
                  icon={<Bot className="size-4" />}
                  selected={adminPage === "agents"}
                  collapsed={collapsed}
                  onClick={onNavigate}
                >
                  {ui("Quản lý trợ lý")}
                </SidebarTab>
              </SidebarSection>
            ) : null}
            {canManageMcp ? (
              <SidebarSection title={ui("Connectors")} collapsed={collapsed}>
                <SidebarTab
                  to="/admin/mcp"
                  icon={<Blocks className="size-4" />}
                  selected={adminPage === "mcp"}
                  collapsed={collapsed}
                  onClick={onNavigate}
                >
                  {ui("Máy chủ MCP")}
                </SidebarTab>
              </SidebarSection>
            ) : null}
            {canReadSources ? (
              <SidebarSection title={ui("Knowledge")} collapsed={collapsed}>
                <SidebarTab
                  to="/admin"
                  icon={<Plug className="size-4" />}
                  selected={adminPage === "sources"}
                  collapsed={collapsed}
                  onClick={onNavigate}
                >
                  {ui("Sources")}
                </SidebarTab>
              </SidebarSection>
            ) : null}
            {canManageUsers || canReadGroups || canManageProviders ? (
              <SidebarSection title={ui("Tenant")} collapsed={collapsed}>
                {canManageUsers ? (
                  <SidebarTab
                    to="/admin/users"
                    icon={<User className="size-4" />}
                    selected={adminPage === "users"}
                    collapsed={collapsed}
                    onClick={onNavigate}
                  >
                    {ui("Users")}
                  </SidebarTab>
                ) : null}
                {canReadGroups ? (
                  <SidebarTab
                    to="/admin/groups"
                    icon={<Users className="size-4" />}
                    selected={adminPage === "groups"}
                    collapsed={collapsed}
                    onClick={onNavigate}
                  >
                    {ui("Groups")}
                  </SidebarTab>
                ) : null}
                {canManageProviders ? (
                  <SidebarTab
                    to="/admin/identity-providers"
                    icon={<KeyRound className="size-4" />}
                    selected={adminPage === "providers"}
                    collapsed={collapsed}
                    onClick={onNavigate}
                  >
                    {ui("Sign-in providers")}
                  </SidebarTab>
                ) : null}
              </SidebarSection>
            ) : null}
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

export function AppShell({
  area = "app",
  adminPage = "sources",
  settingsPage,
  sourceSetup,
  pageTitle,
  headerActions,
  children,
}: AppShellProps) {
  const ui = useAppTranslation();

  const [collapsed, setCollapsed] = useState(false);
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false);
  const sidebarCollapsed = sourceSetup === undefined && collapsed;

  return (
    <div className="flex h-dvh min-h-0 overflow-hidden bg-surface-canvas text-content-primary">
      <a
        href="#main-content"
        className="sr-only z-[60] rounded-lg bg-surface-base px-3 py-2 font-main-ui-body shadow-md focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:ring-3 focus:ring-ring/50"
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
          "relative hidden h-dvh shrink-0 overflow-hidden bg-surface-canvas transition-[width] duration-200 motion-reduce:transition-none md:block",
          sidebarCollapsed ? "w-(--sidebar-width-collapsed)" : "w-(--sidebar-width)",
        )}
      >
        <SidebarContents
          area={area}
          adminPage={adminPage}
          settingsPage={settingsPage}
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
            <span
              title={pageTitle}
              className="min-w-0 flex-1 truncate font-main-ui-body text-content-primary md:max-w-xl"
            >
              {pageTitle}
            </span>
            <div className="ml-auto flex shrink-0 items-center">{headerActions}</div>
          </header>

          <Dialog.Portal>
            <Dialog.Overlay className="fixed inset-0 z-40 bg-surface-scrim backdrop-blur-[2px] data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out data-[state=open]:fade-in motion-reduce:animate-none" />
            <Dialog.Content
              aria-describedby={undefined}
              className="fixed inset-y-0 left-0 z-50 w-[min(var(--sidebar-width),86vw)] border-r border-border-subtle bg-surface-canvas shadow-md outline-none data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:slide-out-to-left data-[state=open]:slide-in-from-left motion-reduce:animate-none"
            >
              <Dialog.Title className="sr-only">{ui("MemoryOS navigation")}</Dialog.Title>
              <SidebarContents
                area={area}
                adminPage={adminPage}
                settingsPage={settingsPage}
                sourceSetup={sourceSetup}
                mobile
                onNavigate={() => setMobileNavigationOpen(false)}
              />
            </Dialog.Content>
          </Dialog.Portal>
        </Dialog.Root>

        <main
          id="main-content"
          tabIndex={-1}
          className="min-h-0 min-w-0 flex-1 overflow-auto outline-none [scrollbar-gutter:stable]"
        >
          {children}
        </main>
      </section>
    </div>
  );
}
