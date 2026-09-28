import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useId, useImperativeHandle, useLayoutEffect, useRef, useState, type Ref } from "react";
import { ChevronDown, Paperclip, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Field, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
} from "@/components/ui/input-group";

const MAX_OAUTH_CLIENT_BYTES = 16 * 1024;

export type GoogleDriveOAuthClientInputHandle = {
  takeJson: () => string | undefined;
  clear: () => void;
};

export function GoogleDriveOAuthClientInput({
  ref,
  disabled = false,
  onReadyChange,
}: {
  ref: Ref<GoogleDriveOAuthClientInputHandle>;
  disabled?: boolean;
  onReadyChange: (ready: boolean) => void;
}) {
  const ui = useAppTranslation();

  const id = useId();
  const input = useRef<HTMLInputElement>(null);
  const draft = useRef("");
  const upload = useRef<HTMLInputElement>(null);
  const readVersion = useRef(0);
  const [error, setError] = useState<AppCopy | null>(null);
  const [reading, setReading] = useState(false);
  const [fileName, setFileName] = useState<string | null>(null);
  const [hasDraft, setHasDraft] = useState(false);
  const callback = `${window.location.origin}/login/oauth2/code/google-drive`;

  function clear() {
    readVersion.current += 1;
    draft.current = "";
    if (input.current) input.current.value = "";
    if (upload.current) upload.current.value = "";
    setFileName(null);
    setHasDraft(false);
    setReading(false);
    setError(null);
    onReadyChange(false);
  }

  useImperativeHandle(ref, () => ({
    clear,
    takeJson() {
      const json = draft.current.trim();
      clear();
      return json || undefined;
    },
  }));

  useLayoutEffect(() => {
    const field = input.current;
    const file = upload.current;
    return () => {
      readVersion.current += 1;
      draft.current = "";
      if (field) field.value = "";
      if (file) file.value = "";
    };
  }, []);

  function checkDraft(value: string) {
    onReadyChange(false);
    setError(null);
    if (!value.trim()) return;
    if (new TextEncoder().encode(value).length > MAX_OAUTH_CLIENT_BYTES) {
      setError("Choose or paste an OAuth client JSON no larger than 16 KiB.");
      return;
    }
    try {
      const parsed: unknown = JSON.parse(value);
      const web = parsed && typeof parsed === "object" && "web" in parsed ? parsed.web : null;
      if (
        !web ||
        typeof web !== "object" ||
        !("client_id" in web) ||
        typeof web.client_id !== "string" ||
        !web.client_id.trim() ||
        !("client_secret" in web) ||
        typeof web.client_secret !== "string" ||
        !web.client_secret.trim() ||
        (parsed && typeof parsed === "object" && ("installed" in parsed || "type" in parsed))
      ) {
        setError(
          "Use the downloaded Web application OAuth client JSON, not a desktop or service-account credential.",
        );
        return;
      }
      onReadyChange(true);
    } catch {
      setError(
        "The JSON could not be read. Paste the complete downloaded Web application OAuth client JSON.",
      );
    }
  }

  async function readFile(file: File | undefined) {
    clear();
    if (!file) return;
    if (!file.size || file.size > MAX_OAUTH_CLIENT_BYTES) {
      setError("Choose an OAuth client JSON between 1 byte and 16 KiB.");
      return;
    }
    const version = readVersion.current;
    setReading(true);
    try {
      const json = await file.text();
      if (version !== readVersion.current || !input.current) return;
      draft.current = json;
      input.current.value = file.name;
      setFileName(file.name);
      setHasDraft(Boolean(json));
      checkDraft(json);
    } catch {
      if (version === readVersion.current)
        setError("The file could not be read. Choose it again or paste its JSON.");
    } finally {
      if (version === readVersion.current) setReading(false);
    }
  }

  function updateText(value: string) {
    readVersion.current += 1;
    setReading(false);
    setFileName(null);
    setHasDraft(Boolean(value));
    if (upload.current) upload.current.value = "";
    draft.current = value;
    checkDraft(value);
  }

  return (
    <fieldset disabled={disabled} className="flex min-w-0 flex-col gap-4">
      <legend className="sr-only">{ui("Your Google OAuth app")}</legend>
      <p className="font-main-ui-action text-content-primary">{ui("OAuth app")}</p>
      <Collapsible className="group">
        <CollapsibleTrigger asChild>
          <Button size="sm" prominence="tertiary" className="w-fit px-0">
            {ui("Setup instructions")}
            <ChevronDown
              data-icon="inline-end"
              aria-hidden="true"
              className="group-data-[state=open]:rotate-180 motion-safe:transition-transform"
            />
          </Button>
        </CollapsibleTrigger>
        <CollapsibleContent>
          <div className="mt-3 flex flex-col gap-3 font-secondary-body text-content-muted">
            <p>
              {ui(
                "Enable the Drive, Sheets and Docs APIs, configure the consent screen, and create a Web application OAuth client in your Google Cloud project.",
              )}{" "}
              <a
                className="underline underline-offset-4"
                href="https://developers.google.com/identity/protocols/oauth2/web-server#creatingcred"
                target="_blank"
                rel="noreferrer"
              >
                {ui("Google OAuth setup guide")}
              </a>
              .
            </p>
            <p id={`${id}-callback`}>{ui("Authorized redirect URI for this MemoryOS instance:")}</p>
            <Input aria-labelledby={`${id}-callback`} readOnly value={callback} />
          </div>
        </CollapsibleContent>
      </Collapsible>
      <Field data-invalid={error ? true : undefined}>
        <FieldLabel htmlFor={`${id}-json`} className="sr-only">
          {ui("Upload or paste OAuth app JSON")}
        </FieldLabel>
        <input
          ref={upload}
          type="file"
          accept=".json,application/json"
          aria-label={ui("Upload OAuth client JSON")}
          className="hidden"
          tabIndex={-1}
          disabled={disabled}
          onChange={(event) => void readFile(event.target.files?.[0])}
        />
        <InputGroup>
          <InputGroupInput
            ref={input}
            id={`${id}-json`}
            type="text"
            placeholder={ui("Upload or paste OAuth app JSON")}
            readOnly={fileName !== null}
            disabled={disabled}
            maxLength={MAX_OAUTH_CLIENT_BYTES + 1}
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
            aria-describedby={error ? `${id}-error` : undefined}
            aria-invalid={Boolean(error)}
            onChange={(event) => {
              if (fileName !== null) return;
              updateText(event.target.value);
            }}
            onPaste={(event) => {
              if (disabled) return;
              const text = event.clipboardData.getData("text");
              if (!text) return;
              event.preventDefault();
              // Keep the original JSON before a single-line input normalizes line breaks.
              updateText(text);
              event.currentTarget.value = text.replace(/\r\n?|\n/g, " ");
            }}
          />
          <InputGroupAddon align="inline-end">
            {fileName !== null || hasDraft || reading ? (
              <InputGroupButton
                size="icon-xs"
                aria-label={ui("Clear client JSON")}
                disabled={disabled}
                onClick={() => {
                  clear();
                  input.current?.focus();
                }}
              >
                <X aria-hidden="true" />
              </InputGroupButton>
            ) : null}
            {fileName === null ? (
              <InputGroupButton
                size="icon-xs"
                aria-label={ui("Attach file")}
                disabled={disabled}
                onClick={() => upload.current?.click()}
              >
                <Paperclip aria-hidden="true" />
              </InputGroupButton>
            ) : null}
          </InputGroupAddon>
        </InputGroup>
      </Field>
      {reading ? (
        <p role="status" className="text-sm text-content-secondary">
          {ui("Reading client JSON…")}
        </p>
      ) : null}
      {error ? (
        <FieldError id={`${id}-error`} role="alert">
          {ui(error)}
        </FieldError>
      ) : null}
    </fieldset>
  );
}
