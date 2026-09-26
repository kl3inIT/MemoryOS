import { useAppTranslation } from "@/i18n/use-app-translation";
import { useRef, useState, type RefObject } from "react";
import { revalidateLogic, useStore } from "@tanstack/react-form";
import { useMutation } from "@tanstack/react-query";
import { Check, Copy } from "lucide-react";
import { z } from "zod";
import { useAppForm } from "@/components/form/app-form";
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
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
} from "@/components/ui/input-group";
import {
  createIdentityProviderMutation,
  updateIdentityProviderMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { IdentityProviderResponse } from "@/lib/hey-api/types.gen";
import { zCreateIdentityProviderRequest } from "@/lib/hey-api/zod.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { identityProviderMessages } from "./identity-provider-errors";

const ALIAS_PATTERN = /^[a-z0-9][a-z0-9._-]*$/;
const SECRET_MASK = "••••••••••••";

/** The alias an issuer suggests: its Keycloak realm, or else the first label of its host. */
function aliasFromIssuer(issuerUrl: string) {
  try {
    const url = new URL(issuerUrl);
    const candidate =
      url.pathname.match(/\/realms\/([^/]+)/)?.[1] ?? url.hostname.split(".")[0] ?? "";
    const alias = candidate
      .toLowerCase()
      .replace(/[^a-z0-9._-]/g, "-")
      .replace(/^[^a-z0-9]+/, "");
    return ALIAS_PATTERN.test(alias) ? alias.slice(0, 128) : "";
  } catch {
    return "";
  }
}

/** Adds an upstream OIDC provider or edits one. Mounted only while open. */
export function IdentityProviderDialog({
  provider,
  returnFocusRef,
  fallbackFocusRef,
  onClose,
  onSaved,
}: {
  provider: IdentityProviderResponse | null;
  returnFocusRef: RefObject<HTMLElement | null>;
  fallbackFocusRef: RefObject<HTMLElement | null>;
  onClose: () => void;
  onSaved: () => void;
}) {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const create = useMutation(createIdentityProviderMutation());
  const update = useMutation(updateIdentityProviderMutation());
  const [copied, setCopied] = useState(false);
  const [copyFailed, setCopyFailed] = useState(false);
  // The alias follows the issuer until it is typed.
  const aliasTyped = useRef(false);
  const isEditing = provider !== null;

  const required = (message: string) => z.string().trim().min(1, message);
  const schema = z.object({
    alias: required(ui("Enter an alias.")).regex(
      ALIAS_PATTERN,
      ui("Use lowercase letters, digits, dots, dashes or underscores."),
    ),
    displayName: required(ui("Enter a display name.")),
    issuerUrl: required(ui("Enter the issuer URL.")),
    clientId: required(ui("Enter the client ID.")),
    clientSecret: isEditing
      ? zCreateIdentityProviderRequest.shape.clientSecret
      : required(ui("Enter the client secret.")),
    enabled: z.boolean(),
    jitAllowed: z.boolean(),
  });

  const form = useAppForm({
    defaultValues: {
      alias: provider?.alias ?? "",
      displayName: provider?.displayName ?? "",
      issuerUrl: provider?.issuer ?? "",
      clientId: provider?.clientId ?? "",
      clientSecret: provider ? SECRET_MASK : "",
      enabled: provider?.enabled ?? true,
      jitAllowed: provider?.jitAllowed ?? false,
    },
    validationLogic: revalidateLogic(),
    validators: {
      onDynamic: schema,
      // A new attempt clears the previous attempt's server errors.
      // TODO(INFRA): remove once useAppForm clears submit errors itself.
      onSubmit: () => undefined,
    },
    onSubmit: async ({ value, formApi }) => {
      formApi.setErrorMap({ onSubmit: { form: undefined, fields: {} } });
      const alias = value.alias.trim();
      const issuerUrl = value.issuerUrl.trim();
      const common = {
        displayName: value.displayName.trim(),
        clientId: value.clientId.trim(),
        jitAllowed: value.jitAllowed,
      };
      try {
        if (provider)
          await update.mutateAsync({
            path: { alias: provider.alias },
            body: {
              ...common,
              ...(alias !== provider.alias ? { alias } : {}),
              ...(issuerUrl !== provider.issuer ? { issuerUrl } : {}),
              ...(value.clientSecret !== SECRET_MASK ? { clientSecret: value.clientSecret } : {}),
              enabled: value.enabled,
            },
          });
        else
          await create.mutateAsync({
            body: { ...common, alias, issuerUrl, clientSecret: value.clientSecret },
          });
      } catch (cause) {
        const problem = presentProblem(cause, "mutation", identityProviderMessages);
        formApi.setErrorMap({
          onSubmit: {
            form: problemMessage(problem.message),
            fields: Object.fromEntries(
              Object.entries(problem.fields).map(([name, message]) => [
                name,
                { message: problemMessage(message) },
              ]),
            ),
          },
        });
        return;
      }
      onSaved();
      onClose();
    },
  });
  const pending = useStore(form.store, (state) => state.isSubmitting);
  const alias = useStore(form.store, (state) => state.values.alias);

  function redirectUri() {
    if (!provider) return "";
    const trimmed = alias.trim();
    if (!trimmed || trimmed === provider.alias) return provider.brokerRedirectUri;
    return provider.brokerRedirectUri.replace(
      `/broker/${provider.alias}/endpoint`,
      `/broker/${trimmed}/endpoint`,
    );
  }

  async function copyRedirectUri() {
    try {
      await navigator.clipboard.writeText(redirectUri());
      setCopyFailed(false);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      setCopyFailed(true);
    }
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open && !pending) onClose();
      }}
    >
      <DialogContent
        className="sm:max-w-lg"
        onCloseAutoFocus={(event) => {
          const target = returnFocusRef.current?.isConnected
            ? returnFocusRef.current
            : fallbackFocusRef.current;
          if (target?.isConnected) {
            event.preventDefault();
            target.focus();
          }
          returnFocusRef.current = null;
        }}
      >
        <form
          noValidate
          aria-busy={pending}
          className="flex flex-col gap-6"
          onSubmit={(event) => {
            event.preventDefault();
            void form.handleSubmit();
          }}
        >
          <DialogHeader>
            <DialogTitle>
              {provider
                ? ui("Edit {{v1}}", { v1: provider.displayName })
                : ui("Add identity provider")}
            </DialogTitle>
            <DialogDescription className="sr-only">
              {provider
                ? ui("Update the brokered sign-in configuration. Alias and issuer are fixed.")
                : ui("Connect an upstream OIDC provider for brokered sign-in.")}
            </DialogDescription>
          </DialogHeader>

          <FieldGroup>
            <form.AppField
              name="alias"
              listeners={{
                onChange: () => {
                  aliasTyped.current = true;
                },
              }}
            >
              {(field) => (
                <field.TextField
                  label={ui("Alias")}
                  maxLength={128}
                  placeholder={ui("tasco")}
                  size="lg"
                  disabled={pending}
                />
              )}
            </form.AppField>
            <form.AppField name="displayName">
              {(field) => (
                <field.TextField
                  label={ui("Display name")}
                  maxLength={200}
                  placeholder={ui("Sign in with Tasco")}
                  size="lg"
                  disabled={pending}
                />
              )}
            </form.AppField>
            <form.AppField
              name="issuerUrl"
              listeners={{
                onChange: ({ value }) => {
                  if (!aliasTyped.current)
                    form.setFieldValue("alias", aliasFromIssuer(value), {
                      dontUpdateMeta: true,
                      dontRunListeners: true,
                    });
                },
              }}
            >
              {(field) => (
                <field.TextField
                  label={ui("Issuer URL")}
                  type="url"
                  maxLength={2048}
                  placeholder="https://keycloak.example.com/realms/partner"
                  size="lg"
                  disabled={pending}
                />
              )}
            </form.AppField>
            <form.AppField name="clientId">
              {(field) => (
                <field.TextField
                  label={ui("Client ID")}
                  maxLength={255}
                  placeholder={ui("memoryos-broker")}
                  size="lg"
                  disabled={pending}
                />
              )}
            </form.AppField>
            <form.AppField name="clientSecret">
              {(field) => (
                <field.TextField
                  label={ui("Client secret")}
                  type="password"
                  maxLength={1024}
                  autoComplete="new-password"
                  size="lg"
                  disabled={pending}
                />
              )}
            </form.AppField>

            {provider ? (
              <Field data-invalid={copyFailed || undefined}>
                <FieldLabel htmlFor="identity-provider-redirect-uri">
                  {ui("Redirect URI")}
                </FieldLabel>
                <InputGroup>
                  <InputGroupInput
                    id="identity-provider-redirect-uri"
                    readOnly
                    value={redirectUri()}
                    onFocus={(event) => event.currentTarget.select()}
                  />
                  <InputGroupAddon align="inline-end">
                    <InputGroupButton
                      size="icon-xs"
                      aria-label={ui("Copy redirect URI")}
                      title={ui("Copy redirect URI")}
                      onClick={() => void copyRedirectUri()}
                    >
                      {copied ? <Check /> : <Copy />}
                    </InputGroupButton>
                  </InputGroupAddon>
                </InputGroup>
                {copyFailed ? (
                  <FieldError>{ui("Could not copy the redirect URI.")}</FieldError>
                ) : null}
              </Field>
            ) : null}

            {provider ? (
              <form.AppField name="enabled">
                {(field) => (
                  <field.CheckboxField
                    label={ui("Allow sign-in through this provider")}
                    disabled={pending}
                  />
                )}
              </form.AppField>
            ) : null}
            <form.AppField name="jitAllowed">
              {(field) => (
                <field.CheckboxField
                  label={ui("Allow just-in-time admission")}
                  disabled={pending}
                />
              )}
            </form.AppField>
          </FieldGroup>

          <form.AppForm>
            <form.FormError />
            <DialogFooter>
              <Button type="button" prominence="secondary" onClick={onClose} disabled={pending}>
                {ui("Cancel")}
              </Button>
              <form.SubmitButton>
                {pending ? ui("Saving…") : isEditing ? ui("Save changes") : ui("Add provider")}
              </form.SubmitButton>
            </DialogFooter>
          </form.AppForm>
        </form>
      </DialogContent>
    </Dialog>
  );
}
