import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ChevronRight, WifiOff } from "lucide-react";
import { AppShellHeader } from "@/components/app-shell/app-shell-header";
import { BrandLoader } from "@/components/brand-loader";
import { EmptyState } from "@/components/composites/empty-state";
import { Alert, AlertAction, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { personaOf, type Persona } from "@/features/chat/chat-personas-api";
import { getChatPersonaOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { actionErrorText } from "@/lib/action-errors";
import {
  AdvancedSection,
  GeneralSection,
  InstructionsSection,
  KnowledgeSection,
  ToolsSection,
} from "./agent-editor-sections";
import { AgentPreview } from "./agent-preview";
import { useAgentChoices, useAgentForm } from "./use-agent-editor";

/** `/agents/create` and `/agents/$agentId/edit`: loads the agent, then renders the editor. */
export function AgentEditorPage({ agentId }: { agentId?: string }) {
  const ui = useAppTranslation();
  const agent = useQuery({
    ...getChatPersonaOptions({ path: { personaId: agentId ?? "" } }),
    enabled: agentId !== undefined,
    select: personaOf,
  });
  const title = agentId ? (agent.data?.name ?? ui("Sửa trợ lý")) : ui("Tạo trợ lý");
  return (
    <>
      <AppShellHeader title={title} />
      {agentId && agent.isPending ? (
        <div role="status" className="flex justify-center px-(--page-gutter) pt-16">
          <BrandLoader label={ui("Đang tải trợ lý…")} />
        </div>
      ) : agentId && agent.isError ? (
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
        <AgentEditor key={agent.data?.revision ?? "new"} agent={agent.data} />
      )}
    </>
  );
}

function AgentEditor({ agent }: { agent?: Persona }) {
  const ui = useAppTranslation();
  const { form, dirty, editable, blocker, restored, discardDraft } = useAgentForm(agent);
  const choices = useAgentChoices(agent);
  const sections = { form, agent, choices, editable };
  return (
    <form
      noValidate
      className="min-h-full"
      onSubmit={(event) => {
        event.preventDefault();
        void form.handleSubmit();
      }}
    >
      <div className="sticky top-0 z-20 border-b border-border-subtle bg-surface-base/90 backdrop-blur-sm">
        <div className="mx-auto flex h-14 w-full max-w-(--page-width-wide) items-center gap-3 px-(--page-gutter)">
          <nav aria-label={ui("Đường dẫn")} className="flex min-w-0 flex-1 items-center gap-1.5">
            <Link
              to="/agents"
              className="shrink-0 font-main-ui-body text-content-muted outline-none hover:text-content-primary focus-visible:underline"
            >
              {ui("Trợ lý")}
            </Link>
            <ChevronRight aria-hidden="true" className="size-4 shrink-0 text-content-faint" />
            <span className="truncate font-main-ui-action text-content-primary" aria-current="page">
              {agent ? agent.name : ui("Tạo trợ lý")}
            </span>
          </nav>
          <span role="status" className="hidden font-secondary-body text-content-muted sm:inline">
            {!editable
              ? ui("Chỉ xem")
              : !agent && dirty
                ? ui("Đã lưu nháp trên trình duyệt")
                : agent && dirty
                  ? ui("Có thay đổi chưa lưu")
                  : ""}
          </span>
          <Button asChild prominence="secondary">
            <Link to="/agents">{editable ? ui("Hủy") : ui("Đóng")}</Link>
          </Button>
          {editable && (
            <form.Subscribe selector={(state) => state.values.name.trim() !== ""}>
              {(named) => (
                <form.AppForm>
                  <form.SubmitButton disabled={!dirty || !named}>
                    {agent ? ui("Lưu") : ui("Tạo trợ lý")}
                  </form.SubmitButton>
                </form.AppForm>
              )}
            </form.Subscribe>
          )}
        </div>
      </div>

      <div className="mx-auto flex w-full max-w-(--page-width-wide) gap-10 px-(--page-gutter) pt-8 pb-24">
        <fieldset disabled={!editable} className="flex min-w-0 flex-1 flex-col lg:max-w-3xl">
          <legend className="sr-only">{agent ? agent.name : ui("Tạo trợ lý")}</legend>
          <div className="mb-6 flex flex-col gap-3 empty:hidden">
            <form.AppForm>
              <form.FormError />
            </form.AppForm>
            {restored && (
              <Alert>
                <AlertDescription>{ui("Đã khôi phục bản nháp bạn đang tạo dở.")}</AlertDescription>
                <AlertAction>
                  <Button type="button" size="sm" prominence="tertiary" onClick={discardDraft}>
                    {ui("Bỏ bản nháp")}
                  </Button>
                </AlertAction>
              </Alert>
            )}
          </div>
          <GeneralSection {...sections} />
          <InstructionsSection {...sections} />
          <KnowledgeSection {...sections} />
          <ToolsSection {...sections} />
          <AdvancedSection {...sections} />
        </fieldset>

        <aside aria-label={ui("Xem trước")} className="hidden w-88 shrink-0 lg:block">
          <form.Subscribe selector={(state) => state.values}>
            {(values) => <AgentPreview values={values} agentId={agent?.id ?? "new"} />}
          </form.Subscribe>
        </aside>
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
    </form>
  );
}
