import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useId, useImperativeHandle, useLayoutEffect, useRef, useState, type Ref } from "react";
import { Eye, EyeOff, Paperclip, X } from "lucide-react";
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
  FieldTitle,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
} from "@/components/ui/input-group";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";

const MAX_SHAREPOINT_KEYSTORE_BYTES = 16 * 1024;
const MAX_SHAREPOINT_SECRET_LENGTH = 256;

export type SharePointAuthMethod = "CLIENT_SECRET" | "CERTIFICATE";

type SharePointAuthentication =
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
    <FieldSet disabled={disabled} className="min-w-0">
      <FieldLegend className="sr-only">{ui("SharePoint authentication")}</FieldLegend>
      <AuthMethodChoice
        id={id}
        method={method}
        onMethodChange={(value) => {
          clear();
          onMethodChange(value);
        }}
      />
      {method === "CLIENT_SECRET" ? (
        <Field data-invalid={error ? true : undefined}>
          <FieldLabel htmlFor={`${id}-secret`}>{ui("Client secret Value")}</FieldLabel>
          <InputGroup>
            <InputGroupInput
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
              onChange={(event) => updateSecret(event.target.value)}
            />
            <InputGroupAddon align="inline-end">
              <InputGroupButton
                size="icon-xs"
                aria-label={revealSecret ? ui("Hide secret") : ui("Show secret")}
                disabled={disabled}
                onClick={() => setRevealSecret(!revealSecret)}
              >
                {revealSecret ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
              </InputGroupButton>
            </InputGroupAddon>
          </InputGroup>
        </Field>
      ) : (
        <FieldGroup>
          <Field data-invalid={error ? true : undefined}>
            <FieldLabel htmlFor={`${id}-keystore`}>{ui("Keystore (.pfx or .p12)")}</FieldLabel>
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
            <InputGroup aria-invalid={error ? true : undefined}>
              <span className="min-w-0 flex-1 truncate px-2.5 text-sm text-content-secondary">
                {fileName ?? ui("No keystore chosen")}
              </span>
              <InputGroupAddon align="inline-end">
                {fileName ? (
                  <InputGroupButton
                    size="icon-xs"
                    aria-label={ui("Clear keystore")}
                    disabled={disabled}
                    onClick={() => clear()}
                  >
                    <X aria-hidden="true" />
                  </InputGroupButton>
                ) : null}
                <InputGroupButton
                  size="icon-xs"
                  aria-label={ui("Choose keystore")}
                  disabled={disabled}
                  onClick={() => upload.current?.click()}
                >
                  <Paperclip aria-hidden="true" />
                </InputGroupButton>
              </InputGroupAddon>
            </InputGroup>
          </Field>
          <Field>
            <FieldLabel htmlFor={`${id}-password`}>{ui("Keystore password")}</FieldLabel>
            <Input
              ref={passwordInput}
              id={`${id}-password`}
              type="password"
              disabled={disabled}
              autoComplete="off"
              maxLength={MAX_SHAREPOINT_SECRET_LENGTH}
              placeholder={ui("Leave empty when the keystore has no password")}
              onChange={(event) => {
                password.current = event.target.value;
              }}
            />
          </Field>
        </FieldGroup>
      )}
      <FieldDescription id={`${id}-privacy`}>
        {method === "CLIENT_SECRET"
          ? ui(
              "Sent once to MemoryOS, stored encrypted, and never returned. It is not kept in browser storage and is cleared when you submit or leave this dialog.",
            )
          : ui(
              "Maximum 16 KiB, exactly one RSA key of at least 2048 bits with an unexpired certificate. Only the private key and certificate are stored; the uploaded keystore and its password are not.",
            )}
      </FieldDescription>
      {reading ? (
        <p role="status" className="text-sm text-content-secondary">
          {ui("Reading keystore…")}
        </p>
      ) : null}
      {error ? (
        <FieldError id={`${id}-error`} role="alert">
          {ui(error)}
        </FieldError>
      ) : null}
    </FieldSet>
  );
}

/** Client secret or certificate, as choice cards: the whole card is the radio's label. */
function AuthMethodChoice({
  id,
  method,
  onMethodChange,
}: {
  id: string;
  method: SharePointAuthMethod;
  onMethodChange: (method: SharePointAuthMethod) => void;
}) {
  const ui = useAppTranslation();
  return (
    <Field>
      <FieldTitle>{ui("Authentication")}</FieldTitle>
      <RadioGroup
        value={method}
        onValueChange={(value) => onMethodChange(value as SharePointAuthMethod)}
      >
        <div className="flex flex-wrap gap-3">
          {(["CLIENT_SECRET", "CERTIFICATE"] as const).map((value) => (
            <FieldLabel key={value} htmlFor={`${id}-${value}`} className="min-w-48 flex-1">
              <Field orientation="horizontal">
                <RadioGroupItem id={`${id}-${value}`} value={value} />
                <FieldContent>
                  <FieldTitle>
                    {value === "CLIENT_SECRET" ? ui("Client secret") : ui("Certificate")}
                  </FieldTitle>
                  <FieldDescription>
                    {value === "CLIENT_SECRET"
                      ? ui("Fastest to set up; Entra expires it on its own schedule.")
                      : ui("Upload a PKCS#12 keystore whose certificate is registered on the app.")}
                  </FieldDescription>
                </FieldContent>
              </Field>
            </FieldLabel>
          ))}
        </div>
      </RadioGroup>
    </Field>
  );
}
