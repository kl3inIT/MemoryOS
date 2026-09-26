import { StatusBadge } from "@/components/ui/status-badge";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { GoogleDriveCredentialResponse } from "@/lib/hey-api/types.gen";
import { GoogleDriveIcon } from "./google-drive-icon";

type GoogleDriveConnectionAccountProps = {
  credential: GoogleDriveCredentialResponse | undefined;
  connected: boolean;
};

/**
 * The account the Source will read through, as connector setups show it: the provider, the chosen
 * credential with its account, and whether it is connected. Follows Lindy's Connections, folk's
 * Accounts and Coda.
 */
export function GoogleDriveConnectionAccount({
  credential,
  connected,
}: GoogleDriveConnectionAccountProps) {
  const ui = useAppTranslation();
  const tone = connected ? "success" : "warning";
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-xl border border-border-subtle bg-surface-raised px-4 py-3">
      <span
        aria-hidden="true"
        className="grid size-10 shrink-0 place-items-center rounded-lg border border-border-subtle bg-surface-base [&_svg]:size-5"
      >
        <GoogleDriveIcon />
      </span>
      <div className="min-w-0 flex-1">
        <p className="font-main-ui-action text-content-primary">{ui("Google Drive")}</p>
        <p className="truncate text-sm text-content-muted">
          {!credential
            ? ui("No credential selected")
            : credential.authMethod === "SERVICE_ACCOUNT"
              ? ui("{{v1}} (service account acting as {{v2}})", {
                  v1: credential.name,
                  v2: credential.accountEmail,
                })
              : ui("{{v1}} ({{v2}})", { v1: credential.name, v2: credential.accountEmail })}
        </p>
      </div>
      <StatusBadge tone={tone} variant="pill">
        {connected ? ui("Connected") : ui("Not connected")}
      </StatusBadge>
    </div>
  );
}
