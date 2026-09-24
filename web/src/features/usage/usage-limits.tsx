import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CircleAlert, Gauge, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { EmptyState } from "@/components/composites/empty-state";
import { SectionHeader } from "@/components/composites/section-header";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/radix-select";
import { Progress } from "@/components/ui/progress";
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
import { cn } from "@/lib/utils";
import {
  createAiUsageLimitMutation,
  deleteAiUsageLimitMutation,
  listAiUsageLimitsOptions,
  listAiUsageLimitsQueryKey,
  listGroupsOptions,
  updateAiUsageLimitMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { AiUsageLimit } from "@/lib/hey-api/types.gen";

type AiUsageLimitScope = AiUsageLimit["scope"];
import { count, money, scopeLabels } from "./ai-costs";

const scopes: AiUsageLimitScope[] = ["TENANT", "GROUP", "PERSON"];
const periodChoices = [1, 7, 30] as const;

/** Where a limit's spend stands: the share used, so a row reads at a glance. */
function share(limit: AiUsageLimit) {
  const byTokens = limit.tokenBudget ? limit.tokensUsed / limit.tokenBudget : 0;
  const byCost = limit.costBudgetUsd ? limit.costUsed / limit.costBudgetUsd : 0;
  return Math.min(1, Math.max(byTokens, byCost));
}

/** Spent is red, nearly spent is amber, and everything else is the ordinary bar. */
function barTone(used: number) {
  if (used >= 1) return "[&_[data-slot=progress-indicator]]:bg-status-danger-content";
  if (used >= 0.8) return "[&_[data-slot=progress-indicator]]:bg-status-warning-content";
  return "";
}

/**
 * Admin › AI costs › Limits (Onyx token rate limits): what the Tenant, a Group and each person may spend, set beside
 * what is being spent, as StackAI and Langdock show a cap.
 */
export function UsageLimits() {
  const ui = useAppTranslation();
  const queryClient = useQueryClient();
  const [adding, setAdding] = useState(false);
  const limits = useQuery(listAiUsageLimitsOptions());
  const refresh = () => queryClient.invalidateQueries({ queryKey: listAiUsageLimitsQueryKey() });
  const update = useMutation({ ...updateAiUsageLimitMutation(), onSuccess: () => void refresh() });
  const remove = useMutation({ ...deleteAiUsageLimitMutation(), onSuccess: () => void refresh() });
  const list = limits.data ?? [];

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
            <Plus aria-hidden="true" />
            {ui("Add a limit")}
          </Button>
        }
      />

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
          <Table className="w-full min-w-[44rem] table-fixed">
            <colgroup>
              <col className="w-[28%]" />
              <col />
              <col className="w-[8rem]" />
              <col className="w-[7rem]" />
              <col className="w-[4rem]" />
            </colgroup>
            <TableHeader className="border-b border-border-subtle bg-surface-subtle">
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
            <TableBody className="divide-y divide-border-subtle">
              {list.map((limit) => (
                <TableRow key={limit.id} className="bg-surface-raised align-middle">
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
                  <TableCell className="px-4 py-3 font-secondary-body text-content-secondary tabular-nums">
                    {ui("{{days}} days", { days: limit.periodDays })}
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
                      onClick={() =>
                        remove.mutate({
                          path: { limitId: limit.id },
                        })
                      }
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

      <AddLimit open={adding} onOpenChange={setAdding} onCreated={() => void refresh()} />
    </section>
  );
}

/** Both budgets read on one line, with the bar carrying the tighter of the two. */
function Spend({ limit }: { limit: AiUsageLimit }) {
  const ui = useAppTranslation();
  return (
    <span className="block min-w-0">
      <span className="block font-secondary-body text-content-secondary tabular-nums">
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
      </span>
      {/* A bar that is nearly full says so, as Langdock warns before it blocks. */}
      <Progress
        className={cn("mt-1.5 h-1.5", barTone(share(limit)))}
        value={Math.round(share(limit) * 100)}
        aria-hidden="true"
      />
      {limit.scope === "PERSON" ? (
        <span className="mt-1 block font-secondary-body text-content-muted">
          {ui("The person who spent the most")}
        </span>
      ) : null}
    </span>
  );
}

function AddLimit({
  open,
  onOpenChange,
  onCreated,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated: () => void;
}) {
  const ui = useAppTranslation();
  const [scope, setScope] = useState<AiUsageLimitScope>("TENANT");
  const [groupId, setGroupId] = useState<string>("");
  const [tokens, setTokens] = useState("");
  const [cost, setCost] = useState("");
  const [days, setDays] = useState("30");
  const groups = useQuery({ ...listGroupsOptions({ query: { size: 100 } }), enabled: open });
  const create = useMutation({
    ...createAiUsageLimitMutation(),
    onSuccess: () => {
      onCreated();
      onOpenChange(false);
      setTokens("");
      setCost("");
    },
  });
  const usable = (tokens.trim() !== "" || cost.trim() !== "") && (scope !== "GROUP" || groupId);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{ui("Add a limit")}</DialogTitle>
          <DialogDescription>
            {ui(
              "Set a token budget, a cost budget, or both. A model with no price adds token usage but no cost, so only a token budget caps it.",
            )}
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="limit-scope">{ui("Applies to")}</Label>
            <Select value={scope} onValueChange={(value) => setScope(value as AiUsageLimitScope)}>
              <SelectTrigger id="limit-scope">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {scopes.map((value) => (
                  <SelectItem key={value} value={value}>
                    {ui(scopeLabels[value])}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {scope === "GROUP" ? (
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="limit-group">{ui("Group")}</Label>
              <Select value={groupId} onValueChange={setGroupId}>
                <SelectTrigger id="limit-group">
                  <SelectValue placeholder={ui("Choose a group")} />
                </SelectTrigger>
                <SelectContent>
                  {(groups.data?.items ?? []).map((group) => (
                    <SelectItem key={group.id} value={group.id}>
                      {group.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          ) : null}

          <div className="grid gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="limit-tokens">{ui("Token budget")}</Label>
              <Input
                id="limit-tokens"
                inputMode="numeric"
                value={tokens}
                placeholder={ui("No limit")}
                onChange={(event) => setTokens(event.target.value.replace(/\D/g, ""))}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="limit-cost">{ui("Cost budget (USD)")}</Label>
              <Input
                id="limit-cost"
                inputMode="decimal"
                value={cost}
                placeholder={ui("No limit")}
                onChange={(event) => setCost(event.target.value.replace(/[^\d.]/g, ""))}
              />
            </div>
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="limit-period">{ui("Period")}</Label>
            <Select value={days} onValueChange={setDays}>
              <SelectTrigger id="limit-period">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {periodChoices.map((value) => (
                  <SelectItem key={value} value={String(value)}>
                    {ui("{{days}} days", { days: value })}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {create.isError ? (
            <p role="alert" className="font-secondary-body text-status-danger-content">
              {ui("The limit could not be saved. Check the budget and try again.")}
            </p>
          ) : null}
        </div>

        <DialogFooter>
          <Button prominence="secondary" onClick={() => onOpenChange(false)}>
            {ui("Cancel")}
          </Button>
          <Button
            disabled={!usable || create.isPending}
            onClick={() =>
              create.mutate({
                body: {
                  scope,
                  groupId: scope === "GROUP" ? groupId : undefined,
                  tokenBudget: tokens.trim() === "" ? undefined : Number(tokens),
                  costBudgetUsd: cost.trim() === "" ? undefined : Number(cost),
                  periodDays: Number(days),
                  enabled: true,
                },
              })
            }
          >
            {ui("Save")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
