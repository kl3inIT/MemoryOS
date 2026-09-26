import { useAppTranslation } from "@/i18n/use-app-translation";
import { KeyRound } from "lucide-react";
import { useState } from "react";
import { EmptyState } from "@/components/composites/empty-state";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { RadioGroup } from "@/components/ui/radio-group";
import { Table, TableCaption, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { SetupSection } from "@/features/sources/shared/setup-section";
import { SharePointCredentialDialog } from "./sharepoint-credential-dialog";
import { SharePointCredentialRow } from "./sharepoint-credential-row";
import { useSharePointCredentials } from "./use-sharepoint-credentials";

/** The credential step: pick a verified Entra application, or register one. */
export function SharePointCredentialSection({
  selectedId,
  disabled = false,
  onSelect,
  onBusyChange,
}: {
  selectedId: string | undefined;
  disabled?: boolean;
  onSelect: (credentialId: string) => void;
  onBusyChange?: (busy: boolean) => void;
}) {
  const ui = useAppTranslation();
  const manager = useSharePointCredentials({ onSelect, onBusyChange });
  const { credentials, dialog } = manager;
  // Captured once per mount; a credential's expiry does not need to tick live.
  const [now] = useState(() => Date.now());

  return (
    <>
      <SetupSection labelledBy="sharepoint-credential-heading">
        <div className="flex flex-col gap-2">
          <h2 id="sharepoint-credential-heading" className="font-heading-h3 text-content-primary">
            {ui("Select a credential")}
          </h2>
          <p className="text-sm text-content-secondary">
            {ui(
              "MemoryOS signs in as an Entra application, so there is no consent screen and no reader account.",
            )}
          </p>
        </div>
        <RadioGroup
          value={selectedId ?? ""}
          onValueChange={(value) => {
            manager.setError(null);
            onSelect(value);
          }}
        >
          {/* One row group per credential, so its actions stay with it. */}
          <Table aria-labelledby="sharepoint-credential-caption" className="min-w-2xl table-fixed">
            <TableCaption id="sharepoint-credential-caption" className="sr-only">
              {ui("SharePoint credentials")}
            </TableCaption>
            <colgroup>
              <col className="w-14" />
              <col />
              <col className="w-44" />
              <col className="w-52" />
            </colgroup>
            <TableHeader>
              <TableRow>
                <TableHead scope="col">
                  <span className="sr-only">{ui("Select")}</span>
                </TableHead>
                <TableHead scope="col">{ui("Name")}</TableHead>
                <TableHead scope="col">{ui("Authentication")}</TableHead>
                <TableHead scope="col">{ui("SharePoint host")}</TableHead>
              </TableRow>
            </TableHeader>
            {credentials.data?.map((credential) => (
              <SharePointCredentialRow
                key={credential.id}
                credential={credential}
                selected={credential.id === selectedId}
                disabled={disabled}
                now={now}
                manager={manager}
              />
            ))}
          </Table>
        </RadioGroup>
        {credentials.isPending ? (
          <p role="status" className="text-sm text-content-secondary">
            {ui("Loading credentials…")}
          </p>
        ) : credentials.isError ? (
          <Alert variant="destructive">
            <AlertDescription>
              {ui("Credentials could not be loaded. Refresh before making changes.")}
            </AlertDescription>
            <div className="mt-2">
              <Button
                size="sm"
                prominence="secondary"
                pending={credentials.isFetching}
                onClick={() => void credentials.refetch()}
              >
                {ui("Try again")}
              </Button>
            </div>
          </Alert>
        ) : !credentials.data?.length ? (
          <EmptyState
            icon={<KeyRound />}
            title={ui("No SharePoint credentials yet")}
            detail={ui(
              "Register the Entra application once, then every SharePoint Source in this Tenant can use it.",
            )}
          />
        ) : null}
        {manager.error && !dialog.open ? (
          <Alert variant="destructive">
            <AlertDescription>{ui(manager.error)}</AlertDescription>
          </Alert>
        ) : null}
        <div>
          <Button
            disabled={disabled || manager.busy || manager.unavailable}
            onClick={(event) => manager.openDialog(event.currentTarget, null)}
          >
            {ui("Create New")}
          </Button>
        </div>
      </SetupSection>

      <SharePointCredentialDialog
        open={dialog.open}
        session={dialog.session}
        replacing={dialog.replacing}
        busy={manager.busy}
        triggerRef={manager.dialogTrigger}
        onOpenChange={manager.changeDialog}
        onBusyChange={manager.setSaving}
        onSaved={manager.credentialSaved}
      />
    </>
  );
}
