import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useId, useImperativeHandle, useLayoutEffect, useRef, useState, type Ref } from "react";
import { ChevronDown, Paperclip, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { InputGroup, InputGroupAddon, InputGroupButton } from "@/components/ui/input-group";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { cn } from "@/lib/utils";
import { GOOGLE_SERVICE_ACCOUNT_SCOPES } from "./google-drive-credential";

const MAX_SERVICE_ACCOUNT_KEY_BYTES = 16 * 1024;

export type GoogleDriveServiceAccountInputHandle = {
  takeJson: () => string | undefined;
  clear: () => void;
};

type ParsedKey = { clientEmail: string; clientId: string };

function parseKey(json: string): ParsedKey | null {
  try {
    const parsed: unknown = JSON.parse(json);
    if (!parsed || typeof parsed !== "object") return null;
    const key = parsed as Record<string, unknown>;
    const text = (name: string) =>
      typeof key[name] === "string" && key[name].trim() ? key[name] : null;
    const clientEmail = text("client_email");
    const clientId = text("client_id");
    if (key.type !== "service_account" || !clientEmail || !clientId || !text("private_key"))
      return null;
    return { clientEmail, clientId };
  } catch {
    return null;
  }
}

/**
 * The JSON key of a Google Cloud service account, read from its downloaded file. The key stays in a
 * ref, never in React state or query caches, and is cleared as soon as it is taken or the form closes.
 */
export function GoogleDriveServiceAccountInput({
  ref,
  disabled = false,
  onReadyChange,
}: {
  ref: Ref<GoogleDriveServiceAccountInputHandle>;
  disabled?: boolean;
  onReadyChange: (ready: boolean) => void;
}) {
  const ui = useAppTranslation();
  const id = useId();
  const draft = useRef("");
  const upload = useRef<HTMLInputElement>(null);
  const readVersion = useRef(0);
  const [error, setError] = useState<AppCopy | null>(null);
  const [reading, setReading] = useState(false);
  const [fileName, setFileName] = useState<string | null>(null);
  const [key, setKey] = useState<ParsedKey | null>(null);

  function clear() {
    readVersion.current += 1;
    draft.current = "";
    if (upload.current) upload.current.value = "";
    setFileName(null);
    setKey(null);
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
    const file = upload.current;
    return () => {
      readVersion.current += 1;
      draft.current = "";
      if (file) file.value = "";
    };
  }, []);

  async function readFile(file: File | undefined) {
    clear();
    if (!file) return;
    if (!file.size || file.size > MAX_SERVICE_ACCOUNT_KEY_BYTES) {
      setError("Choose a service account JSON key between 1 byte and 16 KiB.");
      return;
    }
    const version = readVersion.current;
    setReading(true);
    try {
      const json = await file.text();
      if (version !== readVersion.current) return;
      setFileName(file.name);
      const parsed = parseKey(json);
      if (!parsed) {
        setError(
          "Use the JSON key downloaded for a Google Cloud service account, not an OAuth client.",
        );
        return;
      }
      draft.current = json;
      setKey(parsed);
      onReadyChange(true);
    } catch {
      if (version === readVersion.current) setError("The file could not be read. Choose it again.");
    } finally {
      if (version === readVersion.current) setReading(false);
    }
  }

  return (
    <fieldset disabled={disabled} className="flex min-w-0 flex-col gap-4">
      <legend className="sr-only">{ui("Service account key")}</legend>
      <p className="font-main-ui-action text-content-primary">{ui("Service account key")}</p>
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
                "Enable the Drive, Docs, Sheets and Admin SDK APIs in your Google Cloud project, create a service account and download its JSON key.",
              )}{" "}
              <a
                className="underline underline-offset-4"
                href="https://developers.google.com/workspace/guides/create-credentials#service-account"
                target="_blank"
                rel="noreferrer"
              >
                {ui("Google service account guide")}
              </a>
              .
            </p>
            <p id={`${id}-scopes`}>
              {ui(
                "In the Google Admin console, open Security › API controls › Domain-wide delegation, add the service account's client ID and grant these scopes:",
              )}
            </p>
            <Input
              aria-labelledby={`${id}-scopes`}
              readOnly
              value={GOOGLE_SERVICE_ACCOUNT_SCOPES.join(",")}
            />
          </div>
        </CollapsibleContent>
      </Collapsible>
      <input
        ref={upload}
        type="file"
        accept=".json,application/json"
        aria-label={ui("Upload service account JSON key")}
        className="hidden"
        tabIndex={-1}
        disabled={disabled}
        onChange={(event) => void readFile(event.target.files?.[0])}
      />
      <InputGroup aria-invalid={error ? true : undefined}>
        <span
          id={`${id}-file`}
          className={cn(
            "min-w-0 flex-1 truncate px-2.5 text-sm",
            fileName ? "text-content-primary" : "text-content-muted",
          )}
        >
          {fileName ?? ui("Attach the service account JSON key")}
        </span>
        <InputGroupAddon align="inline-end">
          {fileName !== null || reading ? (
            <InputGroupButton
              size="icon-xs"
              aria-label={ui("Clear service account key")}
              disabled={disabled}
              onClick={clear}
            >
              <X aria-hidden="true" />
            </InputGroupButton>
          ) : (
            <InputGroupButton
              size="icon-xs"
              aria-label={ui("Attach file")}
              aria-describedby={`${id}-file`}
              disabled={disabled}
              onClick={() => upload.current?.click()}
            >
              <Paperclip aria-hidden="true" />
            </InputGroupButton>
          )}
        </InputGroupAddon>
      </InputGroup>
      {key ? (
        <dl className="flex flex-col gap-1 text-sm">
          <div className="flex min-w-0 gap-3">
            <dt className="shrink-0 text-content-muted">{ui("Service account")}</dt>
            <dd className="min-w-0 wrap-anywhere text-content-primary">{key.clientEmail}</dd>
          </div>
          <div className="flex min-w-0 gap-3">
            <dt className="shrink-0 text-content-muted">{ui("Client ID")}</dt>
            <dd className="min-w-0 font-mono text-xs text-content-primary">{key.clientId}</dd>
          </div>
        </dl>
      ) : null}
      {reading ? (
        <p role="status" className="text-sm text-content-secondary">
          {ui("Reading service account key…")}
        </p>
      ) : null}
      {error ? (
        <p role="alert" className="text-sm text-status-danger-content">
          {ui(error)}
        </p>
      ) : null}
    </fieldset>
  );
}
