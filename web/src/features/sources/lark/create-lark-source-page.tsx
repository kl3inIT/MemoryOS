import { useAppTranslation } from "@/i18n/use-app-translation";
import { useStore } from "@tanstack/react-form";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { ArrowLeft, ArrowRight } from "lucide-react";
import { useState } from "react";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  useApplicationSession,
  useCapabilityAuthority,
} from "@/features/identity/application-session-context";
import {
  createLarkSourceMutation,
  getLarkSelectionRequestOptions,
  listLarkCredentialsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { CreateLarkSourceData } from "@/lib/hey-api/types.gen";
import { SourceCreationStatus } from "@/features/sources/shared/source-creation-status";
import {
  sourceCreationLabel,
  useSourceCreation,
} from "@/features/sources/shared/use-source-creation";
import { LarkCredentialSection } from "./lark-credential-section";
import { LarkIcon } from "@/components/lark-icon";
import { larkRegion, larkScopeError, larkScopeRequest } from "./lark-scope";
import { larkSetupSteps, type LarkSetupStep } from "./lark-setup-search";
import { LarkAccessStep, LarkContentStep, LarkReviewStep } from "./lark-setup-steps";
import { useLarkSourceForm } from "./use-lark-source-form";

export function CreateLarkSourcePage() {
  const session = useApplicationSession();
  return (
    <LarkSourceSetup
      key={`${session.actorId}:${session.authorizationVersion}:${session.capabilities.join(",")}:${session.scopedCapabilities.join(",")}`}
    />
  );
}

function LarkSourceSetup() {
  const ui = useAppTranslation();

  const { credentialId, step } = useSearch({ from: "/_authenticated/admin/sources/new/lark" });
  const navigate = useNavigate({ from: "/admin/sources/new/lark" });
  const authority = useCapabilityAuthority("SOURCES_MANAGE");
  const scoped = authority === "scoped";
  const canManage = authority !== "none";
  const current: LarkSetupStep = step ?? "credential";
  const currentStep = larkSetupSteps.find((entry) => entry.id === current) ?? larkSetupSteps[0];
  const currentIndex = larkSetupSteps.indexOf(currentStep);
  const credentials = useQuery({
    ...listLarkCredentialsOptions(),
    enabled: canManage,
    retry: false,
  });
  const createSource = useMutation(createLarkSourceMutation());
  const creation = useSourceCreation({
    provider: "lark",
    recover: (requestId) => getLarkSelectionRequestOptions({ path: { requestId } }),
    create: (body: CreateLarkSourceData["body"]) => createSource.mutateAsync({ body }),
    failureCode: "SOURCE_LARK_SELECTION_FAILED",
    errorKind: "lark",
    refresh: () => credentials.refetch(),
  });
  const { tracking, createdSourceId, error, setError, pendingValidation, frozen } = creation;
  const form = useLarkSourceForm({
    scoped,
    onEdit: () => creation.edit(() => {}),
    onSubmit: create,
  });
  const values = useStore(form.store, (state) => state.values);
  const [credentialBusy, setCredentialBusy] = useState(false);
  const selected = credentials.data?.find((credential) => credential.id === credentialId);
  const ready = selected?.status === "ACTIVE";
  const region = larkRegion(selected?.region);
  const unavailable = !canManage || credentials.isPending || credentials.isError;
  const busy = credentialBusy || createSource.isPending;
  const scopeError = larkScopeError(values.scope, region);
  const proposal = {
    name: values.sourceName.trim(),
    credentialId: selected?.id ?? "",
    scope: larkScopeRequest(values.scope, region),
    access: values.access,
    ...(values.access === "PRIVATE" && values.groupIds.size > 0
      ? { groupIds: [...values.groupIds] }
      : {}),
  };
  const controlsDisabled = busy || unavailable || frozen || Boolean(createdSourceId);
  const steps = { form, go, busy, controlsDisabled };

  function go(next: LarkSetupStep) {
    setError(null);
    void navigate({ search: { credentialId, step: next } });
  }

  function create() {
    if (busy) return;
    void creation.submit(proposal, !unavailable && ready && Boolean(proposal.name) && !scopeError);
  }

  return (
    <SettingsLayout wide>
      <PageHeader
        icon={<LarkIcon />}
        title={ui("Lark")}
        description={ui("Index Wiki spaces, Docs, Sheets and Drive files from Lark.")}
        actions={
          <Button asChild prominence="secondary" disabled={busy}>
            <Link to="/admin/sources/new">
              <ArrowLeft data-icon="inline-start" aria-hidden="true" />
              {ui("Exit setup")}
            </Link>
          </Button>
        }
      />
      {!canManage ? (
        <Alert variant="destructive">
          <AlertDescription>
            {ui("You do not have permission to manage credentials and Sources.")}
          </AlertDescription>
        </Alert>
      ) : null}
      {error ? (
        <Alert variant="destructive">
          <AlertDescription>{ui(error)}</AlertDescription>
        </Alert>
      ) : null}
      <SourceCreationStatus
        creation={creation}
        busy={busy}
        pendingMessage={ui(
          "Lark is checking that the app can read every space and folder in this scope. Your Source is not active yet. Leaving this page does not cancel verification; return here to recover its status.",
        )}
      />

      <div className="flex min-w-0 flex-col gap-6">
        {/* The shell sidebar shows the steps; it is hidden on small screens, so say where the flow stands. */}
        <p className="font-secondary-body text-content-muted md:hidden">
          {ui("Step {{number}} of {{count}} · {{step}}", {
            number: currentIndex + 1,
            count: larkSetupSteps.length,
            step: ui(currentStep.label),
          })}
        </p>
        {current === "credential" ? (
          <>
            <LarkCredentialSection
              selectedId={credentialId}
              disabled={busy || frozen || Boolean(createdSourceId)}
              onSelect={(value) => void navigate({ search: { credentialId: value, step } })}
              onBusyChange={setCredentialBusy}
            />
            <footer className="flex justify-end">
              <Button disabled={busy || unavailable || !ready} onClick={() => go("content")}>
                {ui("Continue")}
                <ArrowRight data-icon="inline-end" aria-hidden="true" />
              </Button>
            </footer>
          </>
        ) : current === "content" ? (
          <LarkContentStep {...steps} selected={selected} ready={ready} scopeError={scopeError} />
        ) : current === "access" ? (
          <LarkAccessStep {...steps} scoped={scoped} />
        ) : (
          <LarkReviewStep
            {...steps}
            frozen={frozen}
            selected={selected}
            scopeError={scopeError}
            creating={createSource.isPending}
            submitLabel={ui(sourceCreationLabel(creation))}
            submitDisabled={
              busy ||
              pendingValidation ||
              tracking.recovering ||
              tracking.recoveryError ||
              (!createdSourceId &&
                !tracking.uncertain &&
                (unavailable || !ready || !proposal.name || Boolean(scopeError)))
            }
          />
        )}
      </div>
    </SettingsLayout>
  );
}
