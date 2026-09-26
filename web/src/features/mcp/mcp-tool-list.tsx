import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { SectionHeader } from "@/components/composites/section-header";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { FieldDescription } from "@/components/ui/field";
import { StatusBadge } from "@/components/ui/status-badge";
import { Switch } from "@/components/ui/switch";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  listMcpServerToolsOptions,
  setAllMcpServerToolsEnabledMutation,
  setMcpServerToolEnabledMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { invalidateMcpServers } from "./mcp-servers";

/** The tools one server offers and which of them the model may see. */
export function McpToolList({ serverId }: { serverId: string }) {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const cache = useQueryClient();
  const tools = useQuery(listMcpServerToolsOptions({ path: { serverId } }));
  const onSuccess = () => invalidateMcpServers(cache, serverId);
  const toggle = useMutation({ ...setMcpServerToolEnabledMutation(), onSuccess });
  const toggleAll = useMutation({ ...setAllMcpServerToolsEnabledMutation(), onSuccess });
  // One feedback line: the most recent failed change.
  const failure = toggle.error ?? toggleAll.error;

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
    return (
      <FieldDescription>
        {ui("Chưa lấy được công cụ nào. Bấm “Lấy công cụ” sau khi máy chủ đã kết nối.")}
      </FieldDescription>
    );
  }
  const setAll = (enabled: boolean) => {
    toggle.reset();
    toggleAll.mutate({ path: { serverId }, body: { enabled } });
  };
  return (
    <div className="flex flex-col gap-3">
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
      <ul className="flex flex-col gap-2">
        {(tools.data ?? []).map((tool) => (
          <li key={tool.id} className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-main-ui-body text-content-primary">
                  {tool.title ?? tool.name}
                </span>
                {tool.readOnlyHint === false || tool.destructiveHint === true ? (
                  <StatusBadge tone="warning">{ui("Có thể thay đổi dữ liệu")}</StatusBadge>
                ) : null}
                {!tool.exposable ? (
                  <StatusBadge tone="neutral">{ui("Tên quá dài")}</StatusBadge>
                ) : null}
              </div>
              <p className="font-secondary-body text-content-muted">{tool.description}</p>
            </div>
            <Switch
              checked={tool.enabled}
              disabled={!tool.exposable || toggle.isPending}
              aria-label={tool.name}
              onCheckedChange={(enabled) => {
                toggleAll.reset();
                toggle.mutate({
                  path: { serverId, toolId: tool.id },
                  query: { revision: tool.revision },
                  body: { enabled },
                });
              }}
            />
          </li>
        ))}
      </ul>
    </div>
  );
}
