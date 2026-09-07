import { Link } from "@tanstack/react-router";
import {
  ArrowLeft,
  Menu,
  House,
  PanelLeftClose,
  PanelLeftOpen,
  Plug,
  Settings2,
  UserPlus,
  X,
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

export type AppShellArea = "app" | "admin";
export type AdminPage = "sources" | "invitations";

type AppShellProps = {
  area?: AppShellArea;
  adminPage?: AdminPage;
  sourceSetupStep?: 0 | 1;
  pageTitle: string;
  children: ReactNode;
};

type SidebarContentsProps = {
  area: AppShellArea;
  adminPage?: AdminPage;
  sourceSetupStep?: 0 | 1;
  collapsed?: boolean;
  onCollapseToggle?: () => void;
  onNavigate?: () => void;
  mobile?: boolean;
};

function SourceSetupSidebarSteps({ step }: { step: 0 | 1 }) {
  return (
    <ol className="relative mx-2 mt-2 flex flex-col" aria-label="Connector setup progress">
      {["Credential", "Connector"].map((label, index) => (
        <li
          key={label}
          aria-current={step === index ? "step" : undefined}
          className={cn(
            "flex h-9 items-center gap-0.5 font-main-ui-body",
            index > step ? "text-content-muted" : "text-content-primary",
          )}
        >
          {index === 1 && (
            <span
              aria-hidden="true"
              className={cn(
                "absolute top-4.5 left-2 h-9 w-0.5",
                step === 1 ? "bg-status-info-content" : "bg-border-default",
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
                index > step ? "bg-border-default" : "bg-status-info-content",
              )}
            >
              {step === index && <span className="size-1.5 rounded-full bg-(--neutral-00)" />}
            </span>
          </span>
          <span>{label}</span>
          <span className="sr-only">
            {index < step ? "Completed" : index > step ? "Not started" : "Current step"}
          </span>
        </li>
      ))}
    </ol>
  );
}

function SidebarContents({
  area,
  adminPage = "sources",
  sourceSetupStep,
  collapsed = false,
  onCollapseToggle,
  onNavigate,
  mobile = false,
}: SidebarContentsProps) {
  const appArea = area === "app";
  const { canManageInvitations, canManageSources, canAccessAdmin, adminEntryPath } =
    useAdminAccess();

  return (
    <div className="flex h-full min-h-0 flex-col pb-2">
      <header
        className={cn(
          "flex shrink-0 gap-2",
          sourceSetupStep !== undefined ? "items-start pt-3" : "min-h-13 items-center pt-1",
          collapsed ? "px-1" : "px-3",
        )}
      >
        {collapsed && !mobile ? (
          <IconButton
            prominence="internal"
            size="sm"
            aria-label="Expand sidebar"
            title="Expand sidebar"
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
              aria-label="MemoryOS home"
              className="flex min-w-0 flex-1 items-center rounded-lg outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
              onClick={onNavigate}
            >
              <Brand />
            </Link>
            {mobile ? (
              <Dialog.Close asChild>
                <IconButton prominence="internal" size="md" aria-label="Close navigation">
                  <X />
                </IconButton>
              </Dialog.Close>
            ) : sourceSetupStep === undefined ? (
              <IconButton
                prominence="internal"
                size="sm"
                aria-label="Collapse sidebar"
                title="Collapse sidebar"
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
          sourceSetupStep !== undefined
            ? "Connector setup"
            : appArea
              ? "Primary navigation"
              : "Administration navigation"
        }
        className={cn(
          "min-h-0 flex-1 overflow-y-auto px-2",
          sourceSetupStep === undefined && "pt-4",
        )}
      >
        {sourceSetupStep !== undefined ? (
          <SourceSetupSidebarSteps step={sourceSetupStep} />
        ) : appArea ? (
          <SidebarTab
            to="/"
            icon={<House className="size-4" />}
            selected
            collapsed={collapsed}
            onClick={onNavigate}
          >
            Home
          </SidebarTab>
        ) : (
          <div className="space-y-4">
            {canManageInvitations ? (
              <SidebarSection title="Tenant" collapsed={collapsed}>
                <SidebarTab
                  to="/admin/invitations"
                  icon={<UserPlus className="size-4" />}
                  selected={adminPage === "invitations"}
                  collapsed={collapsed}
                  onClick={onNavigate}
                >
                  Invitations
                </SidebarTab>
              </SidebarSection>
            ) : null}
            {canManageSources ? (
              <SidebarSection title="Knowledge" collapsed={collapsed}>
                <SidebarTab
                  to="/admin"
                  icon={<Plug className="size-4" />}
                  selected={adminPage === "sources"}
                  collapsed={collapsed}
                  onClick={onNavigate}
                >
                  Sources
                </SidebarTab>
              </SidebarSection>
            ) : null}
          </div>
        )}
      </nav>

      <footer className="shrink-0 px-2 pt-4">
        {!collapsed && <div className="mx-2 mb-2 border-t border-border-subtle" />}
        {sourceSetupStep !== undefined ? (
          <SidebarTab
            to="/admin/sources/new"
            icon={<X className="size-4" />}
            variant="light"
            onClick={onNavigate}
          >
            Exit Connector Setup
          </SidebarTab>
        ) : !appArea ? (
          <SidebarTab
            to="/"
            icon={<ArrowLeft className="size-4" />}
            collapsed={collapsed}
            variant="light"
            onClick={onNavigate}
          >
            Back to MemoryOS
          </SidebarTab>
        ) : null}
        {sourceSetupStep === undefined && appArea && canAccessAdmin ? (
          <div className="mb-1">
            <SidebarTab
              to={adminEntryPath}
              icon={<Settings2 className="size-4" />}
              collapsed={collapsed}
              variant="light"
              onClick={onNavigate}
            >
              Admin Panel
            </SidebarTab>
          </div>
        ) : null}
        {sourceSetupStep === undefined && (
          <AccountMenu collapsed={collapsed} onNavigate={onNavigate} />
        )}
      </footer>
    </div>
  );
}

export function AppShell({
  area = "app",
  adminPage = "sources",
  sourceSetupStep,
  pageTitle,
  children,
}: AppShellProps) {
  const [collapsed, setCollapsed] = useState(false);
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false);
  const sidebarCollapsed = sourceSetupStep === undefined && collapsed;

  return (
    <div className="flex h-dvh min-h-0 overflow-hidden bg-surface-canvas text-content-primary">
      <a
        href="#main-content"
        className="sr-only z-[60] rounded-lg bg-surface-base px-3 py-2 font-main-ui-body shadow-md focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:ring-3 focus:ring-ring/50"
      >
        Skip to content
      </a>

      <aside
        aria-label={
          sourceSetupStep !== undefined
            ? "Connector setup sidebar"
            : area === "app"
              ? "Application sidebar"
              : "Administration sidebar"
        }
        className={cn(
          "relative hidden h-dvh shrink-0 overflow-hidden bg-surface-canvas transition-[width] duration-200 motion-reduce:transition-none md:block",
          sidebarCollapsed ? "w-(--sidebar-width-collapsed)" : "w-(--sidebar-width)",
        )}
      >
        <SidebarContents
          area={area}
          adminPage={adminPage}
          sourceSetupStep={sourceSetupStep}
          collapsed={sidebarCollapsed}
          onCollapseToggle={() => setCollapsed((current) => !current)}
        />
      </aside>

      <section className="flex min-w-0 flex-1 flex-col overflow-hidden bg-surface-base">
        <Dialog.Root open={mobileNavigationOpen} onOpenChange={setMobileNavigationOpen}>
          <header className="flex h-13 shrink-0 items-center gap-3 border-b border-border-subtle bg-surface-base px-3 md:hidden">
            <Dialog.Trigger asChild>
              <IconButton prominence="internal" size="md" aria-label="Open navigation">
                <Menu />
              </IconButton>
            </Dialog.Trigger>
            <span className="min-w-0 flex-1 truncate font-main-ui-body text-content-primary">
              {pageTitle}
            </span>
          </header>

          <Dialog.Portal>
            <Dialog.Overlay className="fixed inset-0 z-40 bg-surface-scrim backdrop-blur-[2px] data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out data-[state=open]:fade-in motion-reduce:animate-none" />
            <Dialog.Content
              aria-describedby={undefined}
              className="fixed inset-y-0 left-0 z-50 w-[min(var(--sidebar-width),86vw)] border-r border-border-subtle bg-surface-canvas shadow-md outline-none data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:slide-out-to-left data-[state=open]:slide-in-from-left motion-reduce:animate-none"
            >
              <Dialog.Title className="sr-only">MemoryOS navigation</Dialog.Title>
              <SidebarContents
                area={area}
                adminPage={adminPage}
                sourceSetupStep={sourceSetupStep}
                mobile
                onNavigate={() => setMobileNavigationOpen(false)}
              />
            </Dialog.Content>
          </Dialog.Portal>
        </Dialog.Root>

        <main
          id="main-content"
          tabIndex={-1}
          className="min-h-0 min-w-0 flex-1 overflow-auto outline-none"
        >
          {children}
        </main>
      </section>
    </div>
  );
}
