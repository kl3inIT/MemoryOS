import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { SectionHeader } from "@/components/composites/section-header";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { FieldDescription } from "@/components/ui/field";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { StatusBadge } from "@/components/ui/status-badge";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  listMcpServerToolsOptions,
  setAllMcpServerToolsEnabledMutation,
  setMcpServerToolApprovalMutation,
  setMcpServerToolEnabledMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { McpToolView } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { invalidateMcpServers } from "./mcp-servers";

/** What a tool may do in Chat, as Claude's connector permissions: run, ask the person first, or never. */
type Level = "ALWAYS" | "ASK" | "OFF";

function levelOf(tool: McpToolView): Level {
  if (!tool.enabled) return "OFF";
  return tool.approvalRequired ? "ASK" : "ALWAYS";
}

/** A tool the server does not declare read-only may change data. */
function mayChangeData(tool: McpToolView): boolean {
  return tool.readOnlyHint !== true || tool.destructiveHint === true;
}

/**
 * The tools one server offers and what each may do, grouped as Claude groups a connector's tools: those that only
 * read, then those that may change data. A level saves the moment it is chosen.
 */
export function McpToolList({ serverId }: { serverId: string }) {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const cache = useQueryClient();
  const tools = useQuery(listMcpServerToolsOptions({ path: { serverId } }));
  const onSuccess = () => invalidateMcpServers(cache, serverId);
  const enable = useMutation(setMcpServerToolEnabledMutation());
  const approve = useMutation(setMcpServerToolApprovalMutation());
  const toggleAll = useMutation({ ...setAllMcpServerToolsEnabledMutation(), onSuccess });
  const level = useMutation({
    // Two settings make one level; each write moves the tool's revision, so the second uses the first's answer.
    mutationFn: async ({ tool, to }: { tool: McpToolView; to: Level }) => {
      let current = tool;
      const enabled = to !== "OFF";
      if (current.enabled !== enabled)
        current = await enable.mutateAsync({
          path: { serverId, toolId: current.id },
          query: { revision: current.revision },
          body: { enabled },
        });
      const approvalRequired = to === "ASK";
      if (enabled && current.approvalRequired !== approvalRequired)
        await approve.mutateAsync({
          path: { serverId, toolId: current.id },
          query: { revision: current.revision },
          body: { approvalRequired },
        });
    },
    onSettled: onSuccess,
  });
  // One feedback line: the most recent failed change.
  const failure = level.error ?? toggleAll.error;

  if (tools.isError && !tools.data) {
    return (
      <Alert variant="destructive" role="alert">
        <AlertDescription>
          {problem(presentProblem(tools.error, "initialLoad").message)}
        </AlertDescription>
      </Alert>
    );
  }
  if (tools.data && tools.data.length === 0) {
    return <FieldDescription>{ui("Chưa lấy được công cụ nào.")}</FieldDescription>;
  }
  const setAll = (enabled: boolean) => {
    level.reset();
    toggleAll.mutate({ path: { serverId }, body: { enabled } });
  };
  const list = tools.data ?? [];
  const groups = [
    { title: ui("Chỉ đọc"), tools: list.filter((tool) => !mayChangeData(tool)) },
    { title: ui("Có thể thay đổi dữ liệu"), tools: list.filter(mayChangeData) },
  ].filter((group) => group.tools.length > 0);

  return (
    <div className="flex flex-col gap-4">
      <SectionHeader
        level="group"
        title={ui("Công cụ")}
        actions={
          <>
            <Button prominence="secondary" size="sm" onClick={() => setAll(true)}>
              {ui("Bật tất cả")}
            </Button>
            <Button prominence="secondary" size="sm" onClick={() => setAll(false)}>
              {ui("Tắt tất cả")}
            </Button>
          </>
        }
      />
      {tools.isError ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>
            {problem(presentProblem(tools.error, "backgroundRead").message)}
          </AlertDescription>
        </Alert>
      ) : null}
      {failure ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>
            {problem(presentProblem(failure, "mutation").message)}
          </AlertDescription>
        </Alert>
      ) : null}
      {groups.map((group) => (
        <section key={group.title} className="flex flex-col gap-1">
          <h4 className="font-secondary-action text-content-secondary">{group.title}</h4>
          <ul className="flex flex-col divide-y divide-border-subtle">
            {group.tools.map((tool) => (
              <li key={tool.id} className="flex items-start justify-between gap-3 py-2.5">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-main-ui-body text-content-primary">
                      {tool.title ?? tool.name}
                    </span>
                    {!tool.exposable ? (
                      <StatusBadge tone="neutral">{ui("Tên quá dài")}</StatusBadge>
                    ) : null}
                  </div>
                  <p className="line-clamp-2 font-secondary-body text-content-muted">
                    {tool.description}
                  </p>
                </div>
                <Select
                  value={levelOf(tool)}
                  disabled={!tool.exposable || level.isPending}
                  onValueChange={(to) => {
                    toggleAll.reset();
                    level.mutate({ tool, to: to as Level });
                  }}
                >
                  <SelectTrigger size="sm" className="w-40 shrink-0" aria-label={tool.name}>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent align="end">
                    <SelectItem value="ALWAYS">{ui("Luôn cho phép")}</SelectItem>
                    <SelectItem value="ASK">{ui("Hỏi trước")}</SelectItem>
                    <SelectItem value="OFF">{ui("Tắt")}</SelectItem>
                  </SelectContent>
                </Select>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
