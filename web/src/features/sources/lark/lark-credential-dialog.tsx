import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQueryClient } from "@tanstack/react-query";
import { revalidateLogic, useStore } from "@tanstack/react-form";
import { Eye, EyeOff, KeyRound, TriangleAlert } from "lucide-react";
import { z } from "zod";
import { useAppForm } from "@/components/form/app-form";
import { useEffect, useId, useLayoutEffect, useRef, useState, type RefObject } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldGroup,
  FieldLabel,
  FieldTitle,
} from "@/components/ui/field";
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
} from "@/components/ui/input-group";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { isUnauthenticated } from "@/lib/api";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import { createLarkCredential, replaceLarkCredentialSecret } from "@/lib/hey-api/sdk.gen";
import type { LarkCredentialResponse } from "@/lib/hey-api/types.gen";
import { zLarkCredentialRequest } from "@/lib/hey-api/zod.gen";
import { sourceMutationError } from "@/features/sources/shared/source-errors";
import { LarkAppGuide } from "./lark-app-guide";
import { larkRegion, type LarkRegion } from "./lark-scope";

const APP_ID = /^cli_[0-9a-z]{8,40}$/;
const MAX_LARK_SECRET_LENGTH = 256;

type CredentialDialogProps = {
  open: boolean;
  /** Counts openings, so every opening starts from an empty form. */
  session: number;
  /** The credential whose App Secret is replaced, or null to register a new app. */
  replacing: LarkCredentialResponse | null;
  /** Any work of the credential step, the dialog's own included; the dialog stays open while it runs. */
  busy: boolean;
  triggerRef: RefObject<HTMLButtonElement | null>;
  onOpenChange: (open: boolean) => void;
  onBusyChange: (busy: boolean) => void;
  onSaved: (credential: LarkCredentialResponse) => Promise<void>;
};

/** Registers a Lark custom app, or replaces its App Secret, once Lark accepts it. */
export function LarkCredentialDialog(props: CredentialDialogProps) {
  const ui = useAppTranslation();
  const { open, busy, replacing, triggerRef, onOpenChange } = props;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className="sm:max-w-4xl"
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          triggerRef.current?.focus();
        }}
        onEscapeKeyDown={(event) => {
          if (busy) event.preventDefault();
        }}
        onPointerDownOutside={(event) => {
          if (busy) event.preventDefault();
        }}
      >
        <DialogHeader>
          <DialogTitle>
            {replacing ? ui("Replace the Lark App Secret") : ui("Create a Lark credential")}
          </DialogTitle>
          <DialogDescription>
            {ui(
              "The credential is verified with Lark before it is stored, so what Lark rejects is never saved.",
            )}
          </DialogDescription>
        </DialogHeader>
        <CredentialForm key={props.session} {...props} />
      </DialogContent>
    </Dialog>
  );
}

function CredentialForm({
  replacing,
  busy: stepBusy,
  onBusyChange,
  onSaved,
}: CredentialDialogProps) {
  const ui = useAppTranslation();
  const id = useId();
  const queryClient = useQueryClient();
  const notify = useActionNotifications();
  // The App Secret lives only in a ref: it never enters React Query variables, state or browser storage.
  const secretInput = useRef<HTMLInputElement>(null);
  const secret = useRef("");
  const active = useRef(true);
  const [secretReady, setSecretReady] = useState(false);
  const [revealSecret, setRevealSecret] = useState(false);
  const [region, setRegion] = useState<LarkRegion>(larkRegion(replacing?.region));
  const [error, setError] = useState<AppCopy | null>(null);
  const form = useAppForm({
    defaultValues: {
      credentialName: replacing?.name ?? "",
      appId: replacing?.appId ?? "",
    },
    validationLogic: revalidateLogic(),
    validators: {
      onDynamic: z.object({
        // The field id is its name, so it is distinct from the credential rename beside it.
        credentialName: zLarkCredentialRequest.shape.name,
        appId: z
          .string()
          .trim()
          .regex(APP_ID, ui("Supply the App ID of the Lark app, which starts with cli_.")),
      }),
    },
    onSubmit: ({ value }) => save(value),
  });
  const saving = useStore(form.store, (state) => state.isSubmitting);
  // Save waits for a name and the App Secret, and names what is missing by staying disabled.
  const named = useStore(form.store, (state) => Boolean(state.values.credentialName.trim()));
  const busy = saving || stepBusy;

  useLayoutEffect(() => {
    active.current = true;
    const input = secretInput.current;
    return () => {
      active.current = false;
      secret.current = "";
      if (input) input.value = "";
    };
  }, []);

  useEffect(() => {
    onBusyChange(saving);
    return () => onBusyChange(false);
  }, [saving, onBusyChange]);

  function takeSecret() {
    const value = secret.current.trim();
    secret.current = "";
    if (secretInput.current) secretInput.current.value = "";
    setSecretReady(false);
    return value;
  }

  async function save(value: { credentialName: string; appId: string }) {
    if (stepBusy || !value.credentialName.trim() || !secretReady) return;
    // A direct call, not a mutation: the App Secret is read here and never enters React Query.
    const appSecret = takeSecret();
    if (!appSecret) {
      setError("Paste the App Secret before saving.");
      return;
    }
    setError(null);
    try {
      const body = {
        name: value.credentialName.trim(),
        region,
        appId: value.appId.trim(),
        appSecret,
      };
      const { data } = replacing
        ? await replaceLarkCredentialSecret({
            path: { credentialId: replacing.id },
            headers: { "If-Match": `"${replacing.credentialRevision}"` },
            body,
          })
        : await createLarkCredential({ body });
      if (!active.current) return;
      notify({
        title: replacing ? "Credential updated" : "Credential verified",
        description: replacing
          ? `${data.name} was verified with Lark and its App Secret replaced.`
          : `${data.name} was verified with Lark and is ready to use with a Source.`,
        tone: "success",
      });
      await onSaved(data);
    } catch (cause) {
      if (!active.current) return;
      if (isUnauthenticated(cause))
        void queryClient.resetQueries({ queryKey: getCurrentIdentityQueryKey(), exact: true });
      setError(sourceMutationError(cause, "lark-credential"));
    }
  }

  return (
    <form
      className="flex flex-col gap-5"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        void form.handleSubmit();
      }}
    >
      <div className="grid min-w-0 gap-5 lg:grid-cols-5">
        <aside className="h-fit rounded-xl border border-border-subtle bg-surface-base p-4 lg:col-span-2">
          <div className="mb-3 flex items-center gap-2">
            <span className="grid size-8 place-items-center text-content-muted">
              <KeyRound className="size-4" aria-hidden="true" />
            </span>
            <div>
              <h3 className="font-main-ui-action text-content-primary">
                {ui("Lark custom app prerequisite")}
              </h3>
              <p className="font-secondary-body text-content-muted">
                {ui("Create and publish the app before entering its credentials.")}
              </p>
            </div>
          </div>
          <LarkAppGuide />
        </aside>
        <FieldGroup className="min-w-0 lg:col-span-3">
          <form.AppField name="credentialName">
            {(field) => (
              <field.TextField
                label={ui("Credential name")}
                maxLength={120}
                disabled={busy}
                placeholder={ui("e.g. Acme Lark")}
                autoComplete="off"
              />
            )}
          </form.AppField>
          <Field>
            <FieldTitle>{ui("Region")}</FieldTitle>
            <RadioGroup
              value={region}
              disabled={busy || Boolean(replacing)}
              onValueChange={(value) => setRegion(larkRegion(value))}
            >
              <div className="flex flex-wrap gap-3">
                {(["LARK", "FEISHU"] as const).map((value) => (
                  <FieldLabel key={value} htmlFor={`${id}-${value}`} className="min-w-40 flex-1">
                    <Field orientation="horizontal">
                      <RadioGroupItem id={`${id}-${value}`} value={value} />
                      <FieldContent>
                        <FieldTitle>{value === "LARK" ? ui("Lark") : ui("Feishu")}</FieldTitle>
                        <FieldDescription>
                          {value === "LARK" ? ui("larksuite.com") : ui("feishu.cn")}
                        </FieldDescription>
                      </FieldContent>
                    </Field>
                  </FieldLabel>
                ))}
              </div>
            </RadioGroup>
          </Field>
          <form.AppField name="appId">
            {(field) => (
              <field.TextField
                label={ui("App ID")}
                disabled={busy || Boolean(replacing)}
                readOnly={Boolean(replacing)}
                placeholder={ui("cli_a1b2c3d4e5f6a7b8")}
                autoComplete="off"
                spellCheck={false}
              />
            )}
          </form.AppField>
          {replacing ? (
            <Alert variant="warning" role="note">
              <TriangleAlert aria-hidden="true" />
              <AlertDescription>
                {ui("Replacing the App Secret affects all")} {replacing.sourceCount}{" "}
                {ui("Sources using this credential. Saved scopes and documents are retained.")}
              </AlertDescription>
            </Alert>
          ) : null}
          <Field>
            <FieldLabel htmlFor={`${id}-secret`}>{ui("App Secret")}</FieldLabel>
            <InputGroup>
              <InputGroupInput
                ref={secretInput}
                id={`${id}-secret`}
                type={revealSecret ? "text" : "password"}
                disabled={busy}
                maxLength={MAX_LARK_SECRET_LENGTH}
                autoComplete="off"
                autoCapitalize="off"
                spellCheck={false}
                aria-describedby={`${id}-privacy`}
                onChange={(event) => {
                  secret.current = event.target.value;
                  setSecretReady(Boolean(event.target.value.trim()));
                }}
              />
              <InputGroupAddon align="inline-end">
                <InputGroupButton
                  size="icon-xs"
                  aria-label={revealSecret ? ui("Hide secret") : ui("Show secret")}
                  disabled={busy}
                  onClick={() => setRevealSecret(!revealSecret)}
                >
                  {revealSecret ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
                </InputGroupButton>
              </InputGroupAddon>
            </InputGroup>
            <FieldDescription id={`${id}-privacy`}>
              {ui(
                "Sent once to MemoryOS, stored encrypted, and never returned. It is not kept in browser storage and is cleared when you submit or leave this dialog.",
              )}
            </FieldDescription>
          </Field>
        </FieldGroup>
      </div>
      {error ? (
        <Alert variant="destructive">
          <AlertDescription>{ui(error)}</AlertDescription>
        </Alert>
      ) : null}
      <DialogFooter>
        <DialogClose asChild>
          <Button prominence="secondary" disabled={busy}>
            {ui("Cancel")}
          </Button>
        </DialogClose>
        <Button type="submit" pending={saving} disabled={busy || !named || !secretReady}>
          {ui("Verify and save")}
        </Button>
      </DialogFooter>
    </form>
  );
}
