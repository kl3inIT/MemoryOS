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
  createSharePointSourceMutation,
  getSharePointSelectionPolicyOptions,
  getSharePointSelectionRequestOptions,
  listSharePointCredentialsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { CreateSharePointSourceData } from "@/lib/hey-api/types.gen";
import { SourceCreationStatus } from "@/features/sources/shared/source-creation-status";
import {
  sourceCreationLabel,
  unsubmittedRequestId,
  useSourceCreation,
} from "@/features/sources/shared/use-source-creation";
import { SharePointCredentialSection } from "./sharepoint-credential-section";
import { SharePointIcon } from "./sharepoint-icon";
import { sharePointScopeError, sharePointScopeRequest } from "./sharepoint-scope";
import { sharePointSetupSteps, type SharePointSetupStep } from "./sharepoint-setup-search";
import {
  SharePointAccessStep,
  SharePointContentStep,
  SharePointReviewStep,
} from "./sharepoint-setup-steps";
import { useSharePointSourceForm } from "./use-sharepoint-source-form";

export function CreateSharePointSourcePage() {
  const session = useApplicationSession();
  return (
    <SharePointSourceSetup
      key={`${session.actorId}:${session.authorizationVersion}:${session.capabilities.join(",")}:${session.scopedCapabilities.join(",")}`}
    />
  );
}

function SharePointSourceSetup() {
  const ui = useAppTranslation();

  const { credentialId, step } = useSearch({
    from: "/_authenticated/admin/sources/new/sharepoint",
  });
  const navigate = useNavigate({ from: "/admin/sources/new/sharepoint" });
  const authority = useCapabilityAuthority("SOURCES_MANAGE");
  const scoped = authority === "scoped";
  const canManage = authority !== "none";
  const current: SharePointSetupStep = step ?? "credential";
  const currentIndex = Math.max(
    0,
    sharePointSetupSteps.findIndex((entry) => entry.id === current),
  );
  const credentials = useQuery({
    ...listSharePointCredentialsOptions(),
    enabled: canManage,
    retry: false,
  });
  const policy = useQuery({
    ...getSharePointSelectionPolicyOptions(),
    enabled: canManage,
    retry: false,
  });
  const createSource = useMutation(createSharePointSourceMutation());
  const creation = useSourceCreation({
    provider: "sharepoint",
    recover: (requestId) => getSharePointSelectionRequestOptions({ path: { requestId } }),
    create: (body: CreateSharePointSourceData["body"]) => createSource.mutateAsync({ body }),
    failureCode: "SOURCE_SHAREPOINT_SELECTION_FAILED",
    errorKind: "sharepoint",
    refresh: () => credentials.refetch(),
  });
  const { tracking, createdSourceId, error, setError, pendingValidation, frozen } = creation;
  const form = useSharePointSourceForm({
    scoped,
    onEdit: () => creation.edit(() => {}),
    onSubmit: create,
  });
  const values = useStore(form.store, (state) => state.values);
  const [credentialBusy, setCredentialBusy] = useState(false);
  const selected = credentials.data?.find((credential) => credential.id === credentialId);
  const ready = selected?.status === "ACTIVE";
  const unavailable = !canManage || credentials.isPending || credentials.isError;
  const busy = credentialBusy || createSource.isPending;
  const proposal = {
    name: values.sourceName.trim(),
    credentialId: selected?.id ?? "",
    scope: sharePointScopeRequest(values.scope),
    access: values.access,
    ...(values.access === "PRIVATE" && values.groupIds.size > 0
      ? { groupIds: [...values.groupIds] }
      : {}),
  };
  const scopeError = sharePointScopeError(values.scope, policy.data, {
    ...proposal,
    requestId: tracking.requestId ?? unsubmittedRequestId,
  });
  const controlsDisabled = busy || unavailable || frozen || Boolean(createdSourceId);
  const steps = { form, go, busy, controlsDisabled };

  function go(next: SharePointSetupStep) {
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
        icon={<SharePointIcon />}
        title={ui("SharePoint")}
        description={ui("Index sites, document libraries and pages from SharePoint Online.")}
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
          "Microsoft is resolving every address in this scope. Your Source is not active yet. Leaving this page does not cancel verification; return here to recover its status.",
        )}
      />
      {policy.isError ? (
        <Alert variant="destructive">
          <AlertDescription>
            {ui("Selection limits could not be loaded. Creation is disabled.")}
          </AlertDescription>
          <div className="mt-2">
            <Button size="sm" prominence="secondary" onClick={() => void policy.refetch()}>
              {ui("Retry selection limits")}
            </Button>
          </div>
        </Alert>
      ) : null}

      <div className="flex min-w-0 flex-col gap-6">
        {/* The shell sidebar shows the steps; it is hidden on small screens, so say where the flow stands. */}
        <p className="font-secondary-body text-content-muted md:hidden">
          {ui("Step {{number}} of {{count}} · {{step}}", {
            number: currentIndex + 1,
            count: sharePointSetupSteps.length,
            step: ui(sharePointSetupSteps[currentIndex].label),
          })}
        </p>
        {current === "credential" ? (
          <>
            <SharePointCredentialSection
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
          <SharePointContentStep
            {...steps}
            selected={selected}
            ready={ready}
            policy={policy.data}
            scopeError={scopeError}
          />
        ) : current === "access" ? (
          <SharePointAccessStep {...steps} scoped={scoped} />
        ) : (
          <SharePointReviewStep
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
