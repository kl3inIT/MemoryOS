import { createFileRoute, Outlet, useMatchRoute, useRouterState } from "@tanstack/react-router";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { AppShell, type SourceSetupProgress } from "@/components/app-shell/app-shell";
import { sharePointSetupSteps } from "@/features/sources/sharepoint-setup-search";
import { AccessDeniedScreen } from "@/features/identity/session-states";
import { useAdminAccess } from "@/features/identity/application-session-context";
import { SourceUploadRecoveryProvider } from "@/features/sources/source-upload-recovery-provider";

export const Route = createFileRoute("/_authenticated/admin")({
  component: function AdministrationLayout() {
    const ui = useAppTranslation();
    const {
      canManageUsers,
      canReadGroups,
      canReadSources,
      canManageModels,
      canManageProviders,
      canManageMcp,
      canManageAgents,
      canReadAudit,
      canReadChatHistory,
    } = useAdminAccess();
    const matchRoute = useMatchRoute();
    const sharePointStep = useRouterState({
      select: (state) =>
        state.location.pathname === "/admin/sources/new/sharepoint"
          ? ((state.location.search as { step?: string }).step ?? "credential")
          : undefined,
    });
    const sourceSetup: SourceSetupProgress | undefined = matchRoute({
      to: "/admin/sources/new/google-drive",
    })
      ? {
          steps: ["Credential", "Connector"],
          current: matchRoute({
            to: "/admin/sources/new/google-drive",
            search: { step: "connector" },
            includeSearch: true,
          })
            ? 1
            : 0,
        }
      : sharePointStep !== undefined
        ? {
            steps: sharePointSetupSteps.map((step) => step.label),
            current: Math.max(
              0,
              sharePointSetupSteps.findIndex((step) => step.id === sharePointStep),
            ),
          }
        : undefined;
    const usersSelected = Boolean(matchRoute({ to: "/admin/users" }));
    const groupsSelected = Boolean(matchRoute({ to: "/admin/groups", fuzzy: true }));
    const providersSelected = Boolean(matchRoute({ to: "/admin/identity-providers" }));
    const modelsSelected = Boolean(matchRoute({ to: "/admin/models" }));
    const searchSettingsSelected = Boolean(matchRoute({ to: "/admin/search-settings" }));
    const webSearchSelected = Boolean(matchRoute({ to: "/admin/web-search" }));
    const voiceSelected = Boolean(matchRoute({ to: "/admin/voice" }));
    const imageGenerationSelected = Boolean(matchRoute({ to: "/admin/image-generation" }));
    const interpreterSelected = Boolean(matchRoute({ to: "/admin/code-interpreter" }));
    const mcpSelected = Boolean(matchRoute({ to: "/admin/mcp" }));
    const agentsSelected = Boolean(matchRoute({ to: "/admin/agents" }));
    const costsSelected = Boolean(matchRoute({ to: "/admin/ai-costs" }));
    const auditSelected = Boolean(matchRoute({ to: "/admin/audit" }));
    const chatHistorySelected = Boolean(matchRoute({ to: "/admin/chat-history" }));
    const documentSetsSelected = Boolean(matchRoute({ to: "/admin/document-sets", fuzzy: true }));
    const addSourceSelected = Boolean(matchRoute({ to: "/admin/sources/new", fuzzy: true }));
    const page = usersSelected
      ? "users"
      : groupsSelected
        ? "groups"
        : providersSelected
          ? "providers"
          : modelsSelected
            ? "models"
            : searchSettingsSelected
              ? "searchSettings"
              : webSearchSelected
                ? "web"
                : voiceSelected
                  ? "voice"
                  : imageGenerationSelected
                    ? "images"
                    : interpreterSelected
                      ? "interpreter"
                      : mcpSelected
                        ? "mcp"
                        : agentsSelected
                          ? "agents"
                          : costsSelected
                            ? "costs"
                            : auditSelected
                              ? "audit"
                              : chatHistorySelected
                                ? "chatHistory"
                                : documentSetsSelected
                                  ? "documentSets"
                                  : addSourceSelected
                                    ? "addSource"
                                    : "sources";
    const allowed =
      page === "users"
        ? canManageUsers
        : page === "groups"
          ? canReadGroups
          : page === "providers"
            ? canManageProviders
            : page === "models" ||
                page === "searchSettings" ||
                page === "web" ||
                page === "voice" ||
                page === "images" ||
                page === "interpreter" ||
                page === "costs"
              ? canManageModels
              : page === "mcp"
                ? canManageMcp
                : page === "agents"
                  ? canManageAgents
                  : page === "audit"
                    ? canReadAudit
                    : page === "chatHistory"
                      ? canReadChatHistory
                      : canReadSources;

    if (!allowed) {
      return <AccessDeniedScreen />;
    }

    return (
      <AppShell
        area="admin"
        adminPage={page}
        pageTitle={ui(
          page === "users"
            ? "Users"
            : page === "groups"
              ? "Groups"
              : page === "providers"
                ? "Sign-in providers"
                : page === "models"
                  ? "Models"
                  : page === "searchSettings"
                    ? "Cấu hình tìm kiếm"
                    : page === "web"
                      ? "Tìm kiếm Web"
                      : page === "voice"
                        ? "Giọng nói"
                        : page === "images"
                          ? "Tạo ảnh"
                          : page === "interpreter"
                            ? "Code Interpreter"
                            : page === "mcp"
                              ? "Máy chủ MCP"
                              : page === "agents"
                                ? "Quản lý trợ lý"
                                : page === "costs"
                                  ? "AI costs"
                                  : page === "audit"
                                    ? "Audit log"
                                    : page === "chatHistory"
                                      ? "Conversation history"
                                      : page === "documentSets"
                                        ? "Bộ tài liệu"
                                        : page === "addSource"
                                          ? "Add a source"
                                          : "Sources",
        )}
        sourceSetup={sourceSetup}
      >
        <SourceUploadRecoveryProvider>
          <Outlet />
        </SourceUploadRecoveryProvider>
      </AppShell>
    );
  },
});
