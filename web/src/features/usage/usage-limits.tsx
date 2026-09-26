import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CircleAlert, Gauge, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { EmptyState } from "@/components/composites/empty-state";
import { SectionHeader } from "@/components/composites/section-header";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { Progress } from "@/components/ui/progress";
import { StatusBadge } from "@/components/ui/status-badge";
import { Switch } from "@/components/ui/switch";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  deleteAiUsageLimitMutation,
  listAiUsageLimitsOptions,
  listAiUsageLimitsQueryKey,
  updateAiUsageLimitMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { AiUsageLimit } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { count, money, scopeLabels } from "./ai-costs";
import { AddLimitDialog } from "./usage-limit-dialog";

/** Where a limit's spend stands: the share used, so a row reads at a glance. */
function share(limit: AiUsageLimit) {
  const byTokens = limit.tokenBudget ? limit.tokensUsed / limit.tokenBudget : 0;
  const byCost = limit.costBudgetUsd ? limit.costUsed / limit.costBudgetUsd : 0;
  return Math.min(1, Math.max(byTokens, byCost));
}

/** The limits, what is spent against them, and the changes a row offers; every change rereads the list. */
function useUsageLimits() {
  const cache = useQueryClient();
  const limits = useQuery(listAiUsageLimitsOptions());
  const refresh = () => cache.invalidateQueries({ queryKey: listAiUsageLimitsQueryKey() });
  const update = useMutation({ ...updateAiUsageLimitMutation(), onSettled: refresh });
  const remove = useMutation({ ...deleteAiUsageLimitMutation(), onSuccess: refresh });
  return { limits, refresh, update, remove };
}

/**
 * Admin › AI costs › Limits (Onyx token rate limits): what the Tenant, a Group and each person may spend, set beside
 * what is being spent, as StackAI and Langdock show a cap.
 */
export function UsageLimits() {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const [adding, setAdding] = useState(false);
  const { limits, refresh, update, remove } = useUsageLimits();
  const list = limits.data ?? [];
  // The most recent failed change; the switch shows the saved state again once the list is reread.
  const failure = [update, remove]
    .filter((mutation) => mutation.isError)
    .sort((a, b) => b.submittedAt - a.submittedAt)[0]?.error;

  return (
    <section aria-labelledby="usage-limits-heading" className="flex min-w-0 flex-col gap-4">
      <SectionHeader
        id="usage-limits-heading"
        title={ui("Spending limits")}
        description={ui(
          "Cap what the organization, a group or each person may spend on AI. A chat turn is refused once its budget is spent.",
        )}
        actions={
          <Button prominence="secondary" onClick={() => setAdding(true)}>
            <Plus data-icon="inline-start" aria-hidden="true" />
            {ui("Add a limit")}
          </Button>
        }
      />

      {failure ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>
            {problem(presentProblem(failure, "mutation").message)}
          </AlertDescription>
        </Alert>
      ) : null}

      {limits.isPending ? (
        <p role="status" className="font-secondary-body text-content-muted">
          {ui("Loading spending limits…")}
        </p>
      ) : limits.isError ? (
        <EmptyState
          role="alert"
          icon={<CircleAlert />}
          title={ui("Failed to load spending limits.")}
          action={
            <Button prominence="secondary" onClick={() => void limits.refetch()}>
              {ui("Try again")}
            </Button>
          }
        />
      ) : list.length === 0 ? (
        <EmptyState icon={<Gauge />} title={ui("No limit is set.")} />
      ) : (
        <div className="overflow-hidden rounded-md border border-border-subtle">
          <Table className="w-full min-w-176 table-fixed">
            <colgroup>
              <col className="w-2/7" />
              <col />
              <col className="w-32" />
              <col className="w-28" />
              <col className="w-16" />
            </colgroup>
            <TableHeader>
              <TableRow>
                <TableHead scope="col" className="h-11 px-4">
                  {ui("Applies to")}
                </TableHead>
                <TableHead scope="col" className="h-11 px-4">
                  {ui("Used of budget")}
                </TableHead>
                <TableHead scope="col" className="h-11 px-4">
                  {ui("Period")}
                </TableHead>
                <TableHead scope="col" className="h-11 px-4">
                  {ui("Enforced")}
                </TableHead>
                <TableHead scope="col" className="h-11 px-4">
                  <span className="sr-only">{ui("Remove")}</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.map((limit) => (
                <TableRow key={limit.id} className="align-middle">
                  <TableCell className="px-4 py-3">
                    <span className="block font-main-ui-action text-content-primary">
                      {limit.scope === "GROUP" && limit.groupName
                        ? limit.groupName
                        : ui(scopeLabels[limit.scope])}
                    </span>
                    {limit.scope === "GROUP" ? (
                      <span className="block font-secondary-body text-content-muted">
                        {ui(scopeLabels.GROUP)}
                      </span>
                    ) : null}
                  </TableCell>
                  <TableCell className="px-4 py-3">
                    <Spend limit={limit} />
                  </TableCell>
                  <TableCell className="px-4 py-3">
                    <span className="font-secondary-body text-content-secondary tabular-nums">
                      {ui("{{days}} days", { days: limit.periodDays })}
                    </span>
                  </TableCell>
                  <TableCell className="px-4 py-3">
                    <Switch
                      checked={limit.enabled}
                      aria-label={ui("Enforced")}
                      onCheckedChange={(enabled) =>
                        update.mutate({
                          path: { limitId: limit.id },
                          body: {
                            scope: limit.scope,
                            groupId: limit.groupId,
                            tokenBudget: limit.tokenBudget,
                            costBudgetUsd: limit.costBudgetUsd,
                            periodDays: limit.periodDays,
                            enabled,
                          },
                        })
                      }
                    />
                  </TableCell>
                  <TableCell className="px-4 py-3">
                    <IconButton
                      aria-label={ui("Remove")}
                      prominence="tertiary"
                      pending={remove.isPending && remove.variables?.path.limitId === limit.id}
                      onClick={() => remove.mutate({ path: { limitId: limit.id } })}
                    >
                      <Trash2 aria-hidden="true" />
                    </IconButton>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}

      <AddLimitDialog open={adding} onOpenChange={setAdding} onCreated={() => void refresh()} />
    </section>
  );
}

/** Both budgets read on one line, with the bar carrying the tighter of the two. */
function Spend({ limit }: { limit: AiUsageLimit }) {
  const ui = useAppTranslation();
  const used = share(limit);
  return (
    <span className="flex min-w-0 flex-col gap-1.5">
      <span className="flex flex-wrap items-center gap-2 font-secondary-body text-content-secondary tabular-nums">
        {limit.tokenBudget
          ? ui(
              appText("{{used}} / {{budget}} token", {
                used: count(limit.tokensUsed),
                budget: count(limit.tokenBudget),
              }),
            )
          : null}
        {limit.tokenBudget && limit.costBudgetUsd ? " · " : null}
        {limit.costBudgetUsd
          ? ui(
              appText("{{used}} of {{budget}}", {
                used: money(limit.costUsed),
                budget: money(limit.costBudgetUsd),
              }),
            )
          : null}
        {/* A budget that is nearly spent says so, as Langdock warns before it blocks. */}
        {used >= 1 ? (
          <StatusBadge tone="danger" size="sm">
            {ui("Spent")}
          </StatusBadge>
        ) : used >= 0.8 ? (
          <StatusBadge tone="warning" size="sm">
            {ui("Nearly spent")}
          </StatusBadge>
        ) : null}
      </span>
      <Progress className="h-1.5" value={Math.round(used * 100)} aria-hidden="true" />
      {limit.scope === "PERSON" ? (
        <span className="block font-secondary-body text-content-muted">
          {ui("The person who spent the most")}
        </span>
      ) : null}
    </span>
  );
}
