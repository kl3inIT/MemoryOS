import { revalidateLogic, useStore } from "@tanstack/react-form";
import { useMutation, useQuery } from "@tanstack/react-query";
import { z } from "zod";
import { useAppForm, useProblemErrors } from "@/components/form/app-form";
import { useFieldValidity } from "@/components/form/form-context";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Field, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  createAiUsageLimitMutation,
  listGroupsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { AiUsageLimit } from "@/lib/hey-api/types.gen";
import { scopeLabels } from "./ai-costs";

type Scope = AiUsageLimit["scope"];

const scopes: Scope[] = ["TENANT", "GROUP", "PERSON"];
const periodChoices = ["1", "7", "30"] as const;

/** A limit needs a token budget, a cost budget or both, and a Group when it applies to one. */
const limitSchema = z
  .object({
    scope: z.enum(["TENANT", "GROUP", "PERSON"]),
    groupId: z.string(),
    tokens: z.string(),
    cost: z.string(),
    days: z.enum(periodChoices),
  })
  .refine((draft) => draft.tokens.trim() !== "" || draft.cost.trim() !== "", { path: ["tokens"] })
  .refine((draft) => draft.scope !== "GROUP" || draft.groupId !== "", { path: ["groupId"] });

type LimitDraft = z.input<typeof limitSchema>;

const emptyDraft: LimitDraft = { scope: "TENANT", groupId: "", tokens: "", cost: "", days: "30" };

/** A labelled Radix select bound to the surrounding form field. */
function SelectField({
  label,
  placeholder,
  options,
}: {
  label: string;
  placeholder?: string;
  options: { value: string; label: string }[];
}) {
  const { field, invalid, errors } = useFieldValidity<string>();
  return (
    <Field data-invalid={invalid || undefined}>
      <FieldLabel htmlFor={field.name}>{label}</FieldLabel>
      <Select value={field.state.value} onValueChange={field.handleChange}>
        <SelectTrigger id={field.name} aria-invalid={invalid || undefined}>
          <SelectValue placeholder={placeholder} />
        </SelectTrigger>
        <SelectContent>
          <SelectGroup>
            {options.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectGroup>
        </SelectContent>
      </Select>
      {invalid ? <FieldError errors={errors} /> : null}
    </Field>
  );
}

/** A budget typed as digits (and a decimal point for money); anything else is dropped as it is typed. */
function BudgetField({ label, decimal }: { label: string; decimal?: boolean }) {
  const ui = useAppTranslation();
  const { field, invalid, errors } = useFieldValidity<string>();
  return (
    <Field data-invalid={invalid || undefined}>
      <FieldLabel htmlFor={field.name}>{label}</FieldLabel>
      <Input
        id={field.name}
        inputMode={decimal ? "decimal" : "numeric"}
        value={field.state.value}
        placeholder={ui("No limit")}
        aria-invalid={invalid || undefined}
        onBlur={field.handleBlur}
        onChange={(event) =>
          field.handleChange(event.target.value.replace(decimal ? /[^\d.]/g : /\D/g, ""))
        }
      />
      {invalid ? <FieldError errors={errors} /> : null}
    </Field>
  );
}

export function AddLimitDialog({
  open,
  onOpenChange,
  onCreated,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated: () => void;
}) {
  const ui = useAppTranslation();
  const problemErrors = useProblemErrors();
  const groups = useQuery({ ...listGroupsOptions({ query: { size: 100 } }), enabled: open });
  const create = useMutation(createAiUsageLimitMutation());
  const form = useAppForm({
    defaultValues: emptyDraft,
    validationLogic: revalidateLogic(),
    validators: { onDynamic: limitSchema },
    onSubmit: async ({ value, formApi }) => {
      formApi.setErrorMap({ onSubmit: { form: undefined, fields: {} } });
      try {
        await create.mutateAsync({
          body: {
            scope: value.scope,
            groupId: value.scope === "GROUP" ? value.groupId : undefined,
            tokenBudget: value.tokens.trim() === "" ? undefined : Number(value.tokens),
            costBudgetUsd: value.cost.trim() === "" ? undefined : Number(value.cost),
            periodDays: Number(value.days),
            enabled: true,
          },
        });
      } catch (cause) {
        formApi.setErrorMap({ onSubmit: problemErrors(cause) });
        return;
      }
      onCreated();
      onOpenChange(false);
      formApi.reset({ ...value, tokens: "", cost: "" });
    },
  });
  const scope = useStore(form.store, (state) => state.values.scope);
  const usable = useStore(form.store, (state) => limitSchema.safeParse(state.values).success);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <form
          noValidate
          className="flex flex-col gap-6"
          onSubmit={(event) => {
            event.preventDefault();
            void form.handleSubmit();
          }}
        >
          <DialogHeader>
            <DialogTitle>{ui("Add a limit")}</DialogTitle>
            <DialogDescription>
              {ui(
                "Set a token budget, a cost budget, or both. A model with no price adds token usage but no cost, so only a token budget caps it.",
              )}
            </DialogDescription>
          </DialogHeader>

          <FieldGroup>
            <form.AppField name="scope">
              {() => (
                <SelectField
                  label={ui("Applies to")}
                  options={scopes.map((value) => ({ value, label: ui(scopeLabels[value]) }))}
                />
              )}
            </form.AppField>
            {scope === "GROUP" ? (
              <form.AppField name="groupId">
                {() => (
                  <SelectField
                    label={ui("Group")}
                    placeholder={ui("Choose a group")}
                    options={(groups.data?.items ?? []).map((group) => ({
                      value: group.id,
                      label: group.name,
                    }))}
                  />
                )}
              </form.AppField>
            ) : null}
            <div className="grid gap-4 sm:grid-cols-2">
              <form.AppField name="tokens">
                {() => <BudgetField label={ui("Token budget")} />}
              </form.AppField>
              <form.AppField name="cost">
                {() => <BudgetField label={ui("Cost budget (USD)")} decimal />}
              </form.AppField>
            </div>
            <form.AppField name="days">
              {() => (
                <SelectField
                  label={ui("Period")}
                  options={periodChoices.map((value) => ({
                    value,
                    label: ui("{{days}} days", { days: Number(value) }),
                  }))}
                />
              )}
            </form.AppField>
            <form.AppForm>
              <form.FormError />
            </form.AppForm>
          </FieldGroup>

          <DialogFooter>
            <Button prominence="secondary" onClick={() => onOpenChange(false)}>
              {ui("Cancel")}
            </Button>
            <form.AppForm>
              <form.SubmitButton disabled={!usable}>{ui("Save")}</form.SubmitButton>
            </form.AppForm>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
