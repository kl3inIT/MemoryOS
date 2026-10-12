import { useAppTranslation } from "@/i18n/use-app-translation";
import { KeyRound } from "lucide-react";
import { EmptyState } from "@/components/composites/empty-state";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { RadioGroup } from "@/components/ui/radio-group";
import { Table, TableCaption, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { SetupSection } from "@/features/sources/shared/setup-section";
import { LarkCredentialDialog } from "./lark-credential-dialog";
import { LarkCredentialRow } from "./lark-credential-row";
import { useLarkCredentials } from "./use-lark-credentials";

/** The credential step: pick a verified Lark custom app, or register one. */
export function LarkCredentialSection({
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
  const manager = useLarkCredentials({ onSelect, onBusyChange });
  const { credentials, dialog } = manager;

  return (
    <>
      <SetupSection labelledBy="lark-credential-heading">
        <h2 id="lark-credential-heading" className="font-heading-h3 text-content-primary">
          {ui("Select a credential")}
        </h2>
        <RadioGroup
          value={selectedId ?? ""}
          onValueChange={(value) => {
            manager.setError(null);
            onSelect(value);
          }}
        >
          {/* One row group per credential, so its actions stay with it. */}
          <Table aria-labelledby="lark-credential-caption" className="min-w-2xl table-fixed">
            <TableCaption id="lark-credential-caption" className="sr-only">
              {ui("Lark credentials")}
            </TableCaption>
            <colgroup>
              <col className="w-14" />
              <col />
              <col className="w-52" />
              <col className="w-36" />
            </colgroup>
            <TableHeader>
              <TableRow>
                <TableHead scope="col">
                  <span className="sr-only">{ui("Select")}</span>
                </TableHead>
                <TableHead scope="col">{ui("Name")}</TableHead>
                <TableHead scope="col">{ui("Bot")}</TableHead>
                <TableHead scope="col">{ui("Scopes")}</TableHead>
              </TableRow>
            </TableHeader>
            {credentials.data?.map((credential) => (
              <LarkCredentialRow
                key={credential.id}
                credential={credential}
                selected={credential.id === selectedId}
                disabled={disabled}
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
            title={ui("No Lark credentials yet")}
            detail={ui(
              "Register the Lark custom app once, then every Lark Source in this organization can use it.",
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

      <LarkCredentialDialog
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
