import { useAppTranslation } from "@/i18n/use-app-translation";
import { useRef, useState, type RefObject } from "react";
import { Check, Copy } from "lucide-react";
import { useProblemMessage } from "@/lib/use-problem-message";
import { presentProblem, type ErrorMessage } from "@/lib/problem-presentation";
import { Dialog } from "radix-ui";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Checkbox } from "@/components/ui/checkbox";
import { createIdentityProvider, updateIdentityProvider } from "@/lib/hey-api/sdk.gen";
import type { IdentityProviderResponse } from "@/lib/hey-api/types.gen";
import { identityProviderMessages } from "./identity-provider-errors";

const ALIAS_PATTERN = /^[a-z0-9][a-z0-9._-]*$/;
const SECRET_MASK = "••••••••••••";

type IdentityProviderDialogProps = {
  open: boolean;
  provider: IdentityProviderResponse | null;
  returnFocusRef: RefObject<HTMLElement | null>;
  fallbackFocusRef: RefObject<HTMLElement | null>;
  onOpenChange: (open: boolean) => void;
  onSaved: () => void;
};

export function IdentityProviderDialog({
  open,
  provider,
  returnFocusRef,
  fallbackFocusRef,
  onOpenChange,
  onSaved,
}: IdentityProviderDialogProps) {
  const ui = useAppTranslation();
  const errorMessage = useProblemMessage();
  const isEditing = provider !== null;

  const [alias, setAlias] = useState(provider?.alias ?? "");
  const [aliasTouched, setAliasTouched] = useState(false);
  const [displayName, setDisplayName] = useState(provider?.displayName ?? "");
  const [issuerUrl, setIssuerUrl] = useState(provider?.issuer ?? "");
  const [clientId, setClientId] = useState(provider?.clientId ?? "");
  const [clientSecret, setClientSecret] = useState(provider ? SECRET_MASK : "");
  const [enabled, setEnabled] = useState(provider?.enabled ?? true);
  const [jitAllowed, setJitAllowed] = useState(provider?.jitAllowed ?? false);
  const [pending, setPending] = useState(false);
  const [formError, setFormError] = useState<ErrorMessage | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, ErrorMessage>>({});
  const submitInFlight = useRef(false);

  const [copied, setCopied] = useState(false);

  function redirectUriFor(nextAlias: string) {
    if (!provider) return "";
    const trimmed = nextAlias.trim();
    if (!trimmed || trimmed === provider.alias) return provider.brokerRedirectUri;
    return provider.brokerRedirectUri.replace(
      `/broker/${provider.alias}/endpoint`,
      `/broker/${trimmed}/endpoint`,
    );
  }

  async function copyRedirectUri() {
    try {
      await navigator.clipboard.writeText(redirectUriFor(alias));
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      setFormError({ key: "invalid" });
    }
  }

  function changeOpen(nextOpen: boolean) {
    onOpenChange(nextOpen);
  }

  function changeIssuerUrl(value: string) {
    setIssuerUrl(value);
    if (aliasTouched) return;
    try {
      const url = new URL(value);
      const candidate =
        url.pathname.match(/\/realms\/([^/]+)/)?.[1] ?? url.hostname.split(".")[0] ?? "";
      const alias = candidate
        .toLowerCase()
        .replace(/[^a-z0-9._-]/g, "-")
        .replace(/^[^a-z0-9]+/, "");
      setAlias(ALIAS_PATTERN.test(alias) ? alias.slice(0, 128) : "");
    } catch {
      setAlias("");
    }
  }

  async function submit() {
    if (submitInFlight.current) return;
    submitInFlight.current = true;
    setPending(true);
    setFormError(null);
    setFieldErrors({});
    try {
      if (isEditing) {
        await updateIdentityProvider({
          path: { alias: provider.alias },
          body: {
            ...(alias.trim() !== provider.alias ? { alias: alias.trim() } : {}),
            displayName: displayName.trim(),
            ...(issuerUrl.trim() !== provider.issuer ? { issuerUrl: issuerUrl.trim() } : {}),
            clientId: clientId.trim(),
            ...(clientSecret !== SECRET_MASK ? { clientSecret } : {}),
            enabled,
            jitAllowed,
          },
        });
      } else {
        await createIdentityProvider({
          body: {
            alias: alias.trim(),
            displayName: displayName.trim(),
            issuerUrl: issuerUrl.trim(),
            clientId: clientId.trim(),
            clientSecret,
            jitAllowed,
          },
        });
      }
      onSaved();
      changeOpen(false);
    } catch (error) {
      const problem = presentProblem(error, "mutation", identityProviderMessages);
      setFieldErrors(problem.fields);
      setFormError(problem.message);
    } finally {
      submitInFlight.current = false;
      setPending(false);
    }
  }
  const canSubmit =
    alias.trim() !== "" &&
    displayName.trim() !== "" &&
    issuerUrl.trim() !== "" &&
    clientId.trim() !== "" &&
    (isEditing || clientSecret !== "");

  return (
    <Dialog.Root open={open} onOpenChange={changeOpen}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-surface-scrim backdrop-blur-[2px] data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out data-[state=open]:fade-in motion-reduce:animate-none" />
        <Dialog.Content
          className="fixed top-1/2 left-1/2 z-50 max-h-[calc(100dvh-2rem)] w-[min(34rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 overflow-y-auto rounded-2xl border border-border-default bg-surface-overlay p-5 shadow-md outline-none sm:p-6"
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
          onEscapeKeyDown={(event) => {
            if (submitInFlight.current) event.preventDefault();
          }}
        >
          <form
            aria-busy={pending}
            onSubmit={(event) => {
              event.preventDefault();
              void submit();
            }}
          >
            <Dialog.Title className="font-heading-h3 text-content-primary">
              {isEditing
                ? ui("Edit {{v1}}", { v1: provider.displayName })
                : ui("Add identity provider")}
            </Dialog.Title>
            <Dialog.Description className="sr-only">
              {isEditing
                ? ui("Update the brokered sign-in configuration. Alias and issuer are fixed.")
                : ui("Connect an upstream OIDC provider for brokered sign-in.")}
            </Dialog.Description>

            <div className="mt-6 grid gap-4">
              <label className="grid gap-2 font-secondary-action text-content-secondary">
                {ui("Alias")}
                <Input
                  value={alias}
                  required
                  maxLength={128}
                  aria-invalid={Boolean(fieldErrors.alias)}
                  onChange={(event) => {
                    setAliasTouched(true);
                    setAlias(event.target.value);
                  }}
                  placeholder={ui("tasco")}
                  size="lg"
                />
              </label>

              <label className="grid gap-2 font-secondary-action text-content-secondary">
                {ui("Display name")}
                <Input
                  value={displayName}
                  required
                  maxLength={200}
                  aria-invalid={Boolean(fieldErrors.displayName)}
                  onChange={(event) => setDisplayName(event.target.value)}
                  placeholder={ui("Sign in with Tasco")}
                  size="lg"
                />
              </label>

              <label className="grid gap-2 font-secondary-action text-content-secondary">
                {ui("Issuer URL")}
                <Input
                  type="url"
                  value={issuerUrl}
                  required
                  maxLength={2048}
                  aria-invalid={Boolean(fieldErrors.issuerUrl)}
                  onChange={(event) => changeIssuerUrl(event.target.value)}
                  placeholder="https://keycloak.example.com/realms/partner"
                  size="lg"
                />
              </label>

              <label className="grid gap-2 font-secondary-action text-content-secondary">
                {ui("Client ID")}
                <Input
                  value={clientId}
                  required
                  maxLength={255}
                  aria-invalid={Boolean(fieldErrors.clientId)}
                  onChange={(event) => setClientId(event.target.value)}
                  placeholder={ui("memoryos-broker")}
                  size="lg"
                />
              </label>

              <label className="grid gap-2 font-secondary-action text-content-secondary">
                {ui("Client secret")}
                <Input
                  type="password"
                  value={clientSecret}
                  required={!isEditing}
                  maxLength={1024}
                  autoComplete="new-password"
                  aria-invalid={Boolean(fieldErrors.clientSecret)}
                  onChange={(event) => setClientSecret(event.target.value)}
                  size="lg"
                />
              </label>

              {isEditing ? (
                <label className="grid gap-2 font-secondary-action text-content-secondary">
                  {ui("Redirect URI")}
                  <div className="relative">
                    <Input
                      readOnly
                      value={redirectUriFor(alias)}
                      className="w-full pr-10 font-mono text-xs"
                      onFocus={(event) => event.currentTarget.select()}
                      size="lg"
                    />
                    <button
                      type="button"
                      aria-label={ui("Copy redirect URI")}
                      title={ui("Copy redirect URI")}
                      className="absolute top-1/2 right-3 -translate-y-1/2 text-content-muted transition-colors hover:text-content-primary"
                      onClick={() => void copyRedirectUri()}
                    >
                      {copied ? <Check className="size-4" /> : <Copy className="size-4" />}
                    </button>
                  </div>
                </label>
              ) : null}

              {isEditing ? (
                <label className="flex items-start gap-2 font-secondary-action text-content-secondary">
                  <Checkbox
                    checked={enabled}
                    onCheckedChange={(event) => setEnabled(event === true)}
                  />
                  {ui("Allow sign-in through this provider")}
                </label>
              ) : null}

              <label className="flex items-start gap-2 font-secondary-action text-content-secondary">
                <Checkbox
                  checked={jitAllowed}
                  onCheckedChange={(event) => setJitAllowed(event === true)}
                />
                {ui("Allow just-in-time admission")}
              </label>
            </div>

            {formError ? (
              <p
                role="alert"
                className="mt-4 rounded-lg bg-status-danger-surface px-4 py-3 font-secondary-body text-status-danger-content"
              >
                {errorMessage(formError)}
              </p>
            ) : null}

            <div className="mt-7 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
              <Button
                type="button"
                prominence="secondary"
                onClick={() => changeOpen(false)}
                disabled={pending}
              >
                {ui("Cancel")}
              </Button>
              <Button type="submit" pending={pending} disabled={!canSubmit || pending}>
                {pending ? ui("Saving…") : isEditing ? ui("Save changes") : ui("Add provider")}
              </Button>
            </div>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
