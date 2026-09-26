import { useAppTranslation } from "@/i18n/use-app-translation";
import { revalidateLogic, useStore } from "@tanstack/react-form";
import { Link } from "@tanstack/react-router";
import { ArrowLeft, FileText, TriangleAlert, Upload } from "lucide-react";
import { z } from "zod";
import { useAppForm } from "@/components/form/app-form";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Field, FieldDescription, FieldTitle } from "@/components/ui/field";
import { Progress } from "@/components/ui/progress";
import {
  useApplicationSession,
  useCapabilityAuthority,
} from "@/features/identity/application-session-context";
import { zCreateFileSourceRequest } from "@/lib/hey-api/zod.gen";
import { SourceAccessField, SourceGroupsField } from "@/features/sources/shared/source-form-fields";
import { FileSourceDropzone } from "./file-source-dropzone";
import { type FileSourceValues, useFileSourceCreation } from "./use-file-source-creation";

export function CreateFileSourcePage() {
  const session = useApplicationSession();
  const authority = useCapabilityAuthority("SOURCES_MANAGE");
  // A different person or authority starts the setup again.
  return (
    <FileSourceSetup key={`${session.actorId}:${session.authorizationVersion}:${authority}`} />
  );
}

function FileSourceSetup() {
  const ui = useAppTranslation();
  const session = useApplicationSession();
  const authority = useCapabilityAuthority("SOURCES_MANAGE");
  const scoped = authority === "scoped";
  const creation = useFileSourceCreation({
    scoped,
    canCreate: authority !== "none",
    resetKey: `${session.actorId}:${session.authorizationVersion}:${authority}`,
  });
  const defaultValues: FileSourceValues = {
    sourceName: "",
    access: scoped ? "PRIVATE" : "PUBLIC",
    groupIds: new Set(),
  };
  const form = useAppForm({
    defaultValues,
    validationLogic: revalidateLogic(),
    validators: {
      onDynamic: z.object({
        sourceName: zCreateFileSourceRequest.shape.name,
        access: z.enum(["PUBLIC", "PRIVATE"]),
        groupIds: z.custom<ReadonlySet<string>>(),
      }),
    },
    onSubmit: ({ value }) => creation.submit(value),
  });
  const named = useStore(form.store, (state) => Boolean(state.values.sourceName.trim()));
  const { busy, sourceId, ownPending, pendingFinalize, phase, files } = creation;
  const settingsLocked = busy || Boolean(sourceId);

  return (
    <SettingsLayout>
      <PageHeader
        icon={<FileText />}
        title={ui("Add file source")}
        description={ui("Upload a document to start indexing.")}
        actions={
          <Button asChild prominence="secondary" disabled={busy}>
            <Link to="/admin/sources/new">
              <ArrowLeft data-icon="inline-start" aria-hidden="true" />
              {ui("Exit setup")}
            </Link>
          </Button>
        }
      />
      <form
        className="flex min-w-0 flex-col gap-6"
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          void form.handleSubmit();
        }}
      >
        <form.AppField name="sourceName">
          {(field) => (
            <field.TextField
              label={ui("Source name")}
              maxLength={120}
              disabled={settingsLocked}
              placeholder={ui("e.g. Product documentation")}
            />
          )}
        </form.AppField>
        {scoped ? (
          <Field>
            <FieldTitle>{ui("Visibility")}</FieldTitle>
            <FieldDescription>
              {ui("Private · only members of the selected groups can search and read these files.")}
            </FieldDescription>
          </Field>
        ) : (
          <form.AppField name="access">
            {() => (
              <SourceAccessField
                label={ui("Visibility")}
                modes={["PUBLIC", "PRIVATE"]}
                disabled={settingsLocked}
              />
            )}
          </form.AppField>
        )}
        {/* Only group access reads through groups; scoped managers are held to Private. */}
        <form.Subscribe selector={(state) => state.values.access === "PRIVATE"}>
          {(groupAccess) =>
            groupAccess ? (
              <form.AppField name="groupIds">
                {() => (
                  <SourceGroupsField
                    label={ui("Access groups")}
                    placeholder={
                      scoped ? ui("Select at least one group you manage.") : ui("Select groups")
                    }
                    disabled={settingsLocked}
                  />
                )}
              </form.AppField>
            ) : null
          }
        </form.Subscribe>
        <FileSourceDropzone
          creation={creation}
          onFilesChosen={(batch) => {
            // A single file names the Source until the person names it.
            if (batch.length === 1 && !form.state.values.sourceName.trim() && !sourceId)
              form.setFieldValue(
                "sourceName",
                batch[0]!.name.replace(/\.[^.]+$/, "").slice(0, 120),
              );
          }}
        />
        {creation.error ? (
          <Alert variant="destructive">
            <TriangleAlert aria-hidden="true" />
            <AlertDescription>{ui(creation.error)}</AlertDescription>
          </Alert>
        ) : null}
        {ownPending && !busy ? (
          <p role="status" className="font-secondary-body text-content-secondary">
            {ui("The file reached object storage; retry finalization without uploading it again.")}
          </p>
        ) : null}
        {creation.blocked && pendingFinalize ? (
          <p className="font-secondary-body text-content-secondary">
            {ui("Finish your pending upload first.")}{" "}
            <Link
              to="/admin/sources/$sourceId"
              params={{ sourceId: pendingFinalize.sourceId }}
              className="underline"
            >
              {ui("Return to pending upload")}
            </Link>
          </p>
        ) : null}
        {busy ? (
          <div
            role="status"
            aria-live="polite"
            className="flex flex-col gap-2 font-secondary-body text-content-secondary"
          >
            <p>
              {phase ? ui(phase) : null}
              {files.length > 1
                ? ui(" · {{v1}}", {
                    v1: ui("File {{v1}} of {{v2}}", {
                      v1: creation.currentIndex + 1,
                      v2: files.length,
                    }),
                  })
                : null}
              {phase === "Uploading file…" ? ui(" {{v1}}%", { v1: creation.progress }) : ""}
            </p>
            {phase === "Uploading file…" ? (
              <Progress value={creation.progress} aria-label={ui("Upload progress")} />
            ) : null}
          </div>
        ) : null}
        <footer className="flex flex-wrap items-center justify-end gap-3 border-t border-border-subtle pt-5">
          {sourceId ? (
            <Button asChild prominence="secondary" disabled={busy}>
              <Link to="/admin/sources/$sourceId" params={{ sourceId }}>
                {ui("View source")}
              </Link>
            </Button>
          ) : null}
          <Button
            type="submit"
            pending={busy}
            disabled={
              authority === "none" || busy || creation.blocked || files.length === 0 || !named
            }
          >
            <Upload data-icon="inline-start" aria-hidden="true" />
            {creation.uploadAccepted
              ? ui("Open created Source")
              : ownPending
                ? ui("Retry finalization")
                : sourceId
                  ? ui("Retry upload")
                  : ui("Upload and create")}
          </Button>
        </footer>
      </form>
    </SettingsLayout>
  );
}
