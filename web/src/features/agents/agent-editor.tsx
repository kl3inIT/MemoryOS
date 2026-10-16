import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { MessageSquare, WifiOff } from "lucide-react";
import { lazy, Suspense, useState, type ReactNode } from "react";
import { AppShellHeader } from "@/components/app-shell/app-shell-header";
import { BrandLoader } from "@/components/brand-loader";
import { DetailBreadcrumb } from "@/components/composites/detail-header";
import { EmptyState } from "@/components/composites/empty-state";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Drawer, DrawerContent, DrawerTitle, DrawerTrigger } from "@/components/ui/drawer";
import { ResizableHandle, ResizablePanel, ResizablePanelGroup } from "@/components/ui/resizable";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { personaOf, type Persona } from "@/features/chat/chat-personas-api";
import { getChatPersonaOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { actionErrorText } from "@/lib/action-errors";
import { useIsWide } from "@/hooks/use-mobile";
import type { AgentEditorTab } from "./agent-editor-search";
import {
  DocumentsSection,
  IdentitySection,
  InstructionsSection,
  ModelSection,
  OpeningSection,
  ToolsSection,
} from "./agent-editor-sections";
import { AgentSharePanel } from "./agent-share-panel";
import {
  useAgentChoices,
  useAgentForm,
  useAgentRevision,
  type AgentChoices,
  type AgentFormApi,
} from "./use-agent-editor";

const formId = "agent-editor";

const AgentTestChat = lazy(() => import("./agent-test-chat"));

type TabProps = { tab: AgentEditorTab; onTabChange: (tab: AgentEditorTab) => void };

/** `/agents/$agentId/edit`: loads the assistant, then renders its editor on the tab its address names. */
export function AgentEditorPage({ agentId, ...tabs }: { agentId: string } & TabProps) {
  const ui = useAppTranslation();
  const agent = useQuery({
    ...getChatPersonaOptions({ path: { personaId: agentId } }),
    select: personaOf,
  });
  if (!agent.data)
    return (
      <>
        <AppShellHeader
          title={ui("Sửa trợ lý")}
          breadcrumb={<DetailBreadcrumb parent={{ label: ui("Trợ lý"), to: "/agents" }} />}
        />
        {agent.isError ? (
          <div className="px-(--page-gutter) pt-10">
            <EmptyState
              role="alert"
              icon={<WifiOff />}
              title={ui("Không mở được trợ lý")}
              detail={actionErrorText(agent.error)}
              action={
                <Button size="sm" prominence="secondary" onClick={() => void agent.refetch()}>
                  {ui("Tải lại")}
                </Button>
              }
            />
          </div>
        ) : (
          <div role="status" className="flex justify-center px-(--page-gutter) pt-16">
            <BrandLoader label={ui("Đang tải trợ lý…")} />
          </div>
        )}
      </>
    );
  return <AgentEditor agent={agent.data} {...tabs} />;
}

/**
 * The editor of one assistant: Cấu hình, saved with the header's Lưu without leaving the page, and Chia sẻ, which
 * saves each change at once (LobeHub `AgentProfileTabs`). The tab is kept in the address.
 */
function AgentEditor({ agent, tab, onTabChange }: { agent: Persona } & TabProps) {
  const ui = useAppTranslation();
  const revision = useAgentRevision(agent);
  const choices = useAgentChoices(agent);
  const { form, dirty, saved, editable, blocker } = useAgentForm(agent, revision);
  const status = !editable
    ? ui("Chỉ xem")
    : dirty
      ? ui("Có thay đổi chưa lưu")
      : saved
        ? ui("Đã lưu")
        : "";
  return (
    <>
      <AppShellHeader
        title={agent.name}
        breadcrumb={
          <DetailBreadcrumb
            parent={{ label: ui("Trợ lý"), to: "/agents" }}
            title={agent.name}
            oneLine
          />
        }
        actions={
          <div className="flex items-center gap-2">
            <span role="status" className="hidden font-secondary-body text-content-muted sm:inline">
              {status}
            </span>
            <Button asChild size="sm" prominence="secondary">
              <Link to="/agents">{editable ? ui("Hủy") : ui("Đóng")}</Link>
            </Button>
            {editable && (
              <form.Subscribe selector={(state) => state.values.name.trim() !== ""}>
                {(named) => {
                  const reason = !named
                    ? ui("Cần nhập tên")
                    : !dirty
                      ? ui("Chưa có thay đổi")
                      : undefined;
                  return (
                    <TooltipProvider>
                      <Tooltip>
                        <TooltipTrigger asChild>
                          {/* An unavailable Lưu stays in the tab order (aria-disabled, not disabled) and lets the
                              pointer through to this wrapper, so focus and hover both reach its reason. */}
                          <span className="inline-flex">
                            {reason ? (
                              <Button asChild size="sm">
                                <button type="button" aria-disabled="true">
                                  {ui("Lưu")}
                                </button>
                              </Button>
                            ) : (
                              <form.AppForm>
                                <form.SubmitButton size="sm" form={formId}>
                                  {ui("Lưu")}
                                </form.SubmitButton>
                              </form.AppForm>
                            )}
                          </span>
                        </TooltipTrigger>
                        {reason && <TooltipContent>{reason}</TooltipContent>}
                      </Tooltip>
                    </TooltipProvider>
                  );
                }}
              </form.Subscribe>
            )}
          </div>
        }
      />
      <div className="flex h-full min-h-0 flex-col">
        <Tabs
          value={tab}
          onValueChange={(value) => onTabChange(value as AgentEditorTab)}
          className="min-h-0 flex-1"
        >
          <div className="shrink-0 border-b border-border-subtle px-(--page-gutter) pt-4">
            <TabsList variant="line" aria-label={ui("Trợ lý")}>
              <TabsTrigger value="config" className="flex-none">
                {ui("Cấu hình")}
              </TabsTrigger>
              <TabsTrigger value="share" className="flex-none">
                {ui("Chia sẻ")}
              </TabsTrigger>
            </TabsList>
          </div>
          {/* The form stays mounted on the other tab, so unsaved changes survive a look at Chia sẻ. */}
          <TabsContent
            value="config"
            forceMount
            className="min-h-0 flex-1 data-[state=inactive]:hidden"
          >
            <ConfigLayout agent={agent} form={form} dirty={dirty} editable={editable}>
              <form
                id={formId}
                noValidate
                onSubmit={(event) => {
                  event.preventDefault();
                  void form.handleSubmit();
                }}
                className="px-(--page-gutter) pt-6 pb-24"
              >
                <ConfigSections agent={agent} choices={choices} form={form} editable={editable} />
              </form>
            </ConfigLayout>
          </TabsContent>
          <TabsContent value="share" className="relative min-h-0 flex-1 overflow-y-auto">
            <div className="max-w-3xl px-(--page-gutter) pt-6 pb-24">
              <AgentSharePanel agent={agent} choices={choices} revision={revision} />
            </div>
          </TabsContent>
        </Tabs>
      </div>

      <ConfirmDialog
        open={blocker.status === "blocked"}
        onOpenChange={(open) => {
          if (!open && blocker.status === "blocked") blocker.reset();
        }}
        title={ui("Bỏ thay đổi chưa lưu?")}
        description={ui("Các thay đổi của trợ lý này sẽ mất nếu bạn rời trang.")}
        confirmLabel={ui("Rời trang")}
        pendingLabel={ui("Đang rời trang…")}
        onConfirm={async () => blocker.proceed?.()}
      />
    </>
  );
}

/**
 * Cấu hình beside its test chat (Dify, FastGPT): two resizable panes from `lg` up, and below it one column with a
 * "Chat thử" button that opens the test chat in a drawer, as Dify does on phones. Only an editor may test.
 *
 * Each scroller is `relative`: the visually hidden inputs behind switches and checkboxes are positioned absolutely,
 * and without a positioned scroller they escape it, so focusing one scrolls the shell and pushes its header away.
 */
function ConfigLayout({
  agent,
  form,
  dirty,
  editable,
  children,
}: {
  agent: Persona;
  form: AgentFormApi;
  dirty: boolean;
  editable: boolean;
  children: ReactNode;
}) {
  const ui = useAppTranslation();
  const wide = useIsWide();
  const [drawer, setDrawer] = useState(false);
  const chat = (
    <Suspense
      fallback={
        <div role="status" className="flex h-full items-center justify-center">
          <BrandLoader label={ui("Đang mở chat thử…")} />
        </div>
      }
    >
      <AgentTestChat agent={agent} form={form} dirty={dirty} />
    </Suspense>
  );
  if (!editable) return <div className="relative h-full overflow-y-auto">{children}</div>;
  if (wide)
    return (
      <ResizablePanelGroup orientation="horizontal">
        <ResizablePanel defaultSize="58%" minSize="40%">
          <div className="relative h-full overflow-y-auto">{children}</div>
        </ResizablePanel>
        <ResizableHandle withHandle />
        <ResizablePanel defaultSize="42%" minSize="28%" maxSize="60%">
          {chat}
        </ResizablePanel>
      </ResizablePanelGroup>
    );
  return (
    <div className="relative h-full overflow-y-auto">
      {children}
      <Drawer open={drawer} onOpenChange={setDrawer}>
        <DrawerTrigger asChild>
          <Button
            type="button"
            className="fixed right-4 bottom-[calc(1rem+env(safe-area-inset-bottom,0px))] z-30"
          >
            <MessageSquare aria-hidden="true" />
            {ui("Chat thử")}
          </Button>
        </DrawerTrigger>
        <DrawerContent className="h-4/5">
          <DrawerTitle className="sr-only">{ui("Chat thử")}</DrawerTitle>
          <div className="min-h-0 flex-1 bg-surface-base">{chat}</div>
        </DrawerContent>
      </Drawer>
    </div>
  );
}

function ConfigSections({
  agent,
  choices,
  form,
  editable,
}: {
  agent: Persona;
  choices: AgentChoices;
  form: AgentFormApi;
  editable: boolean;
}) {
  const sections = { form, agent, choices, editable };
  return (
    <fieldset disabled={!editable} className="flex min-w-0 flex-col lg:max-w-3xl">
      <legend className="sr-only">{agent.name}</legend>
      <div className="mb-6 flex flex-col gap-3 empty:hidden">
        <form.AppForm>
          <form.FormError />
        </form.AppForm>
      </div>
      <IdentitySection {...sections} />
      <InstructionsSection {...sections} />
      <OpeningSection {...sections} />
      <DocumentsSection {...sections} />
      <ToolsSection {...sections} />
      <ModelSection {...sections} />
    </fieldset>
  );
}
