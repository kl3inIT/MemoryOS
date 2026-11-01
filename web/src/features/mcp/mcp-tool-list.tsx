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
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  listMcpServerToolsOptions,
  resetMcpServerToolPoliciesMutation,
  setMcpServerToolGroupPolicyMutation,
  setMcpServerToolPolicyMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { McpToolView } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { invalidateMcpServers } from "./mcp-servers";

type Policy = McpToolView["policy"];
type Risk = McpToolView["risk"];

const GROUPS: { risk: Risk; title: AppCopy }[] = [
  { risk: "READ", title: "Chỉ đọc" },
  { risk: "WRITE", title: "Ghi" },
  { risk: "DESTRUCTIVE", title: "Ghi đè hoặc xóa" },
];

/** A tool that may overwrite or delete always asks; the server refuses "Luôn cho phép" for it. */
function policies(risk: Risk): Policy[] {
  return risk === "DESTRUCTIVE" ? ["ASK", "OFF"] : ["ALWAYS", "ASK", "OFF"];
}

/** The level every exposable tool of a group shares, or undefined when they differ. */
function sharedPolicy(tools: McpToolView[]): Policy | undefined {
  const levels = new Set(tools.filter((tool) => tool.exposable).map((tool) => tool.policy));
  return levels.size === 1 ? [...levels][0] : undefined;
}

function PolicySelect({
  risk,
  value,
  label,
  disabled,
  placeholder,
  onChange,
}: {
  risk: Risk;
  value: Policy | undefined;
  label: string;
  disabled: boolean;
  placeholder?: string;
  onChange: (policy: Policy) => void;
}) {
  const ui = useAppTranslation();
  const names: Record<Policy, string> = {
    ALWAYS: ui("Luôn cho phép"),
    ASK: ui("Hỏi trước"),
    OFF: ui("Tắt"),
  };
  return (
    <Select
      value={value ?? ""}
      disabled={disabled}
      onValueChange={(policy) => onChange(policy as Policy)}
    >
      <SelectTrigger size="sm" className="w-40 shrink-0" aria-label={label}>
        <SelectValue placeholder={placeholder} />
      </SelectTrigger>
      <SelectContent align="end">
        {policies(risk).map((policy) => (
          <SelectItem key={policy} value={policy}>
            {names[policy]}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

/**
 * The tools one server offers and what each may do, grouped by what they do to data as LobeHub and Claude group
 * them. A group's selector sets every tool in it; each tool keeps its own. A level saves the moment it is chosen,
 * and a level the administrator chose survives refreshes until "Về mặc định".
 */
export function McpToolList({ serverId }: { serverId: string }) {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const cache = useQueryClient();
  const tools = useQuery(listMcpServerToolsOptions({ path: { serverId } }));
  const onSettled = () => invalidateMcpServers(cache, serverId);
  const single = useMutation({ ...setMcpServerToolPolicyMutation(), onSettled });
  const group = useMutation({ ...setMcpServerToolGroupPolicyMutation(), onSettled });
  const reset = useMutation({ ...resetMcpServerToolPoliciesMutation(), onSettled });
  const pending = single.isPending || group.isPending || reset.isPending;
  // One feedback line: the most recent failed change.
  const failure = single.error ?? group.error ?? reset.error;
  const clearFailures = () => {
    single.reset();
    group.reset();
    reset.reset();
  };

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
  const list = tools.data ?? [];
  const customized = list.some((tool) => tool.policyCustomized);

  return (
    <div className="flex flex-col gap-4">
      <SectionHeader
        level="group"
        title={ui("Công cụ")}
        actions={
          customized ? (
            <Button
              prominence="secondary"
              size="sm"
              disabled={pending}
              onClick={() => {
                clearFailures();
                reset.mutate({ path: { serverId } });
              }}
            >
              {ui("Về mặc định")}
            </Button>
          ) : null
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
      {GROUPS.map(({ risk, title }) => {
        const members = list.filter((tool) => tool.risk === risk);
        if (members.length === 0) return null;
        const exposable = members.some((tool) => tool.exposable);
        return (
          <section key={risk} className="flex flex-col gap-1">
            <div className="flex items-center justify-between gap-3">
              <h4 className="font-secondary-action text-content-secondary">
                {ui(title)} · {members.length}
              </h4>
              <PolicySelect
                risk={risk}
                value={sharedPolicy(members)}
                label={ui("Mức cho nhóm {{group}}", { group: ui(title) })}
                placeholder={ui("Khác nhau")}
                disabled={!exposable || pending}
                onChange={(policy) => {
                  clearFailures();
                  group.mutate({ path: { serverId }, body: { risk, policy } });
                }}
              />
            </div>
            <ul className="flex flex-col divide-y divide-border-subtle">
              {members.map((tool) => (
                <li key={tool.id} className="flex items-start justify-between gap-3 py-2.5">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-main-ui-body text-content-primary">
                        {tool.title ?? tool.name}
                      </span>
                      {!tool.exposable ? (
                        <StatusBadge tone="neutral">{ui("Tên quá dài")}</StatusBadge>
                      ) : tool.policyCustomized ? (
                        <StatusBadge tone="info">{ui("Đã chỉnh")}</StatusBadge>
                      ) : null}
                    </div>
                    <p className="line-clamp-2 font-secondary-body text-content-muted">
                      {tool.description}
                    </p>
                  </div>
                  <PolicySelect
                    risk={risk}
                    value={tool.policy}
                    label={tool.name}
                    disabled={!tool.exposable || pending}
                    onChange={(policy) => {
                      clearFailures();
                      single.mutate({
                        path: { serverId, toolId: tool.id },
                        query: { revision: tool.revision },
                        body: { policy },
                      });
                    }}
                  />
                </li>
              ))}
            </ul>
          </section>
        );
      })}
    </div>
  );
}
