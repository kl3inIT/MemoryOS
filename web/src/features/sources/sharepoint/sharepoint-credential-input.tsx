import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useId, useImperativeHandle, useLayoutEffect, useRef, useState, type Ref } from "react";
import { Eye, EyeOff, Paperclip, X } from "lucide-react";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { cn } from "@/lib/utils";

export const MAX_SHAREPOINT_KEYSTORE_BYTES = 16 * 1024;
export const MAX_SHAREPOINT_SECRET_LENGTH = 256;

export type SharePointAuthMethod = "CLIENT_SECRET" | "CERTIFICATE";

export type SharePointAuthentication =
  | { authMethod: "CLIENT_SECRET"; clientSecret: string }
  | { authMethod: "CERTIFICATE"; certificate: string; certificatePassword: string };

export type SharePointCredentialInputHandle = {
  /** Returns the drafted authentication once and clears it from this component. */
  take: () => SharePointAuthentication | undefined;
  clear: () => void;
};

/**
 * The client secret and the PKCS#12 keystore live only in refs here: they never enter React
 * Query variables, component state that survives a submit, or browser storage.
 */
export function SharePointCredentialInput({
  ref,
  method,
  disabled = false,
  onMethodChange,
  onReadyChange,
}: {
  ref: Ref<SharePointCredentialInputHandle>;
  method: SharePointAuthMethod;
  disabled?: boolean;
  onMethodChange: (method: SharePointAuthMethod) => void;
  onReadyChange: (ready: boolean) => void;
}) {
  const ui = useAppTranslation();

  const id = useId();
  const secretInput = useRef<HTMLInputElement>(null);
  const passwordInput = useRef<HTMLInputElement>(null);
  const upload = useRef<HTMLInputElement>(null);
  const secret = useRef("");
  const keystore = useRef("");
  const password = useRef("");
  const readVersion = useRef(0);
  const [revealSecret, setRevealSecret] = useState(false);
  const [fileName, setFileName] = useState<string | null>(null);
  const [reading, setReading] = useState(false);
  const [error, setError] = useState<AppCopy | null>(null);

  function clear() {
    readVersion.current += 1;
    secret.current = "";
    keystore.current = "";
    password.current = "";
    if (secretInput.current) secretInput.current.value = "";
    if (passwordInput.current) passwordInput.current.value = "";
    if (upload.current) upload.current.value = "";
    setFileName(null);
    setReading(false);
    setRevealSecret(false);
    setError(null);
    onReadyChange(false);
  }

  useImperativeHandle(ref, () => ({
    clear,
    take() {
      const drafted: SharePointAuthentication | undefined =
        method === "CLIENT_SECRET"
          ? secret.current.trim()
            ? { authMethod: "CLIENT_SECRET", clientSecret: secret.current.trim() }
            : undefined
          : keystore.current
            ? {
                authMethod: "CERTIFICATE",
                certificate: keystore.current,
                certificatePassword: password.current,
              }
            : undefined;
      clear();
      return drafted;
    },
  }));

  useLayoutEffect(() => {
    const fields = [secretInput.current, passwordInput.current, upload.current];
    return () => {
      readVersion.current += 1;
      secret.current = "";
      keystore.current = "";
      password.current = "";
      for (const field of fields) if (field) field.value = "";
    };
  }, []);

  function updateSecret(value: string) {
    secret.current = value;
    setError(
      value.length > MAX_SHAREPOINT_SECRET_LENGTH
        ? "Paste the client secret Value, at most 256 characters."
        : null,
    );
    onReadyChange(Boolean(value.trim()) && value.length <= MAX_SHAREPOINT_SECRET_LENGTH);
  }

  async function readKeystore(file: File | undefined) {
    const version = ++readVersion.current;
    keystore.current = "";
    setFileName(null);
    setError(null);
    onReadyChange(false);
    if (!file) return;
    if (!file.size || file.size > MAX_SHAREPOINT_KEYSTORE_BYTES) {
      setError("Choose a .pfx or .p12 keystore between 1 byte and 16 KiB.");
      return;
    }
    setReading(true);
    try {
      const bytes = new Uint8Array(await file.arrayBuffer());
      if (version !== readVersion.current) return;
      let binary = "";
      for (const byte of bytes) binary += String.fromCharCode(byte);
      keystore.current = btoa(binary);
      setFileName(file.name);
      onReadyChange(true);
    } catch {
      if (version === readVersion.current)
        setError("The keystore could not be read. Choose the file again.");
    } finally {
      if (version === readVersion.current) setReading(false);
    }
  }

  return (
    <fieldset disabled={disabled} className="flex min-w-0 flex-col gap-4">
      <legend className="sr-only">{ui("SharePoint authentication")}</legend>
      <div className="space-y-2">
        <p className="font-main-ui-action text-content-primary">{ui("Authentication")}</p>
        <RadioGroup
          className="flex flex-wrap gap-3"
          value={method}
          onValueChange={(value) => {
            clear();
            onMethodChange(value as SharePointAuthMethod);
          }}
        >
          {(["CLIENT_SECRET", "CERTIFICATE"] as const).map((value) => (
            <label
              key={value}
              className="flex min-h-11 flex-1 cursor-pointer items-start gap-2 rounded-lg border border-border-default p-3 has-checked:bg-surface-subtle has-disabled:cursor-default"
            >
              <RadioGroupItem value={value} className="mt-1" />
              <span className="min-w-0">
                <span className="block font-secondary-action text-content-primary">
                  {value === "CLIENT_SECRET" ? ui("Client secret") : ui("Certificate")}
                </span>
                <span className="mt-0.5 block font-secondary-body text-content-muted">
                  {value === "CLIENT_SECRET"
                    ? ui("Fastest to set up; Entra expires it on its own schedule.")
                    : ui("Upload a PKCS#12 keystore whose certificate is registered on the app.")}
                </span>
              </span>
            </label>
          ))}
        </RadioGroup>
      </div>
      {method === "CLIENT_SECRET" ? (
        <div className="flex flex-col gap-1">
          <label htmlFor={`${id}-secret`} className="font-secondary-action text-content-primary">
            {ui("Client secret Value")}
          </label>
          <div
            className={cn(
              "mt-1 flex w-full items-center justify-between gap-1 rounded-lg border border-border-subtle bg-surface-raised p-1.5 transition-colors focus-within:border-focus-ring hover:border-border-default",
              disabled && "border-transparent bg-surface-sunken",
              error && "border-status-danger-content",
            )}
          >
            <Input
              ref={secretInput}
              id={`${id}-secret`}
              type={revealSecret ? "text" : "password"}
              placeholder={ui("Paste the secret Value, not the Secret ID")}
              disabled={disabled}
              maxLength={MAX_SHAREPOINT_SECRET_LENGTH + 1}
              autoComplete="off"
              autoCapitalize="off"
              spellCheck={false}
              aria-describedby={`${id}-privacy${error ? ` ${id}-error` : ""}`}
              aria-invalid={Boolean(error)}
              className="h-6 rounded-none border-0 bg-transparent p-0.5 focus-visible:shadow-none"
              onChange={(event) => updateSecret(event.target.value)}
            />
            <IconButton
              size="sm"
              prominence="tertiary"
              aria-label={revealSecret ? ui("Hide secret") : ui("Show secret")}
              disabled={disabled}
              onClick={() => setRevealSecret(!revealSecret)}
            >
              {revealSecret ? <EyeOff /> : <Eye />}
            </IconButton>
          </div>
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          <div className="flex flex-col gap-1">
            <label
              htmlFor={`${id}-keystore`}
              className="font-secondary-action text-content-primary"
            >
              {ui("Keystore (.pfx or .p12)")}
            </label>
            <input
              ref={upload}
              id={`${id}-keystore`}
              type="file"
              accept=".pfx,.p12,application/x-pkcs12"
              aria-describedby={`${id}-privacy${error ? ` ${id}-error` : ""}`}
              aria-invalid={Boolean(error)}
              disabled={disabled}
              className="hidden"
              onChange={(event) => void readKeystore(event.target.files?.[0])}
            />
            <div
              className={cn(
                "mt-1 flex w-full items-center justify-between gap-2 rounded-lg border border-border-subtle bg-surface-raised px-3 py-2 text-sm",
                disabled && "border-transparent bg-surface-sunken",
                error && "border-status-danger-content",
              )}
            >
              <span className="min-w-0 flex-1 truncate text-content-secondary">
                {fileName ?? ui("No keystore chosen")}
              </span>
              {fileName ? (
                <IconButton
                  size="sm"
                  prominence="tertiary"
                  aria-label={ui("Clear keystore")}
                  disabled={disabled}
                  onClick={() => clear()}
                >
                  <X />
                </IconButton>
              ) : null}
              <IconButton
                size="sm"
                prominence="tertiary"
                aria-label={ui("Choose keystore")}
                disabled={disabled}
                onClick={() => upload.current?.click()}
              >
                <Paperclip />
              </IconButton>
            </div>
          </div>
          <div className="flex flex-col gap-1">
            <label
              htmlFor={`${id}-password`}
              className="font-secondary-action text-content-primary"
            >
              {ui("Keystore password")}
            </label>
            <Input
              ref={passwordInput}
              id={`${id}-password`}
              type="password"
              className="mt-1"
              disabled={disabled}
              autoComplete="off"
              maxLength={MAX_SHAREPOINT_SECRET_LENGTH}
              placeholder={ui("Leave empty when the keystore has no password")}
              onChange={(event) => {
                password.current = event.target.value;
              }}
            />
          </div>
        </div>
      )}
      <p id={`${id}-privacy`} className="font-secondary-body text-content-muted">
        {method === "CLIENT_SECRET"
          ? ui(
              "Sent once to MemoryOS, stored encrypted, and never returned. It is not kept in browser storage and is cleared when you submit or leave this dialog.",
            )
          : ui(
              "Maximum 16 KiB, exactly one RSA key of at least 2048 bits with an unexpired certificate. Only the private key and certificate are stored; the uploaded keystore and its password are not.",
            )}
      </p>
      {reading ? (
        <p role="status" className="text-sm text-content-secondary">
          {ui("Reading keystore…")}
        </p>
      ) : null}
      {error ? (
        <p id={`${id}-error`} role="alert" className="text-sm text-status-danger-content">
          {ui(error)}
        </p>
      ) : null}
    </fieldset>
  );
}
