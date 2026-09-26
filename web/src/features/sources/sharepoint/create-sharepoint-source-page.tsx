import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { ArrowLeft, ArrowRight, Pencil } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { Label } from "@/components/ui/label";
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
import { SharePointCredentialSection } from "./sharepoint-credential-section";
import { SharePointIcon } from "./sharepoint-icon";
import { SharePointScheduleFields } from "./sharepoint-schedule-fields";
import { SharePointScopeFields } from "./sharepoint-scope-fields";
import {
  emptySharePointScopeDraft,
  parseSharePointLines,
  sharePointScopeError,
  sharePointScopeRequest,
  type SharePointScopeDraft,
} from "./sharepoint-scope";
import { SourceAccessChoice } from "@/features/sources/shared/source-access-choice";
import { SourceGroupPicker } from "@/features/sources/shared/source-group-picker";
import { sharePointSetupSteps, type SharePointSetupStep } from "./sharepoint-setup-search";
import { SourceCreationStatus } from "@/features/sources/shared/source-creation-status";
import {
  sourceCreationLabel,
  unsubmittedRequestId,
  useSourceCreation,
} from "@/features/sources/shared/use-source-creation";

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
  const [sourceName, setSourceName] = useState("");
  const [access, setAccess] = useState<"PUBLIC" | "PRIVATE">(scoped ? "PRIVATE" : "PUBLIC");
  const [groupIds, setGroupIds] = useState<Set<string>>(() => new Set());
  const [draft, setDraft] = useState<SharePointScopeDraft>(emptySharePointScopeDraft);
  const [credentialBusy, setCredentialBusy] = useState(false);
  const selected = credentials.data?.find((credential) => credential.id === credentialId);
  const ready = selected?.status === "ACTIVE";
  const unavailable = !canManage || credentials.isPending || credentials.isError;
  const busy = credentialBusy || createSource.isPending;
  const proposal = {
    name: sourceName.trim(),
    credentialId: selected?.id ?? "",
    scope: sharePointScopeRequest(draft),
    access,
    ...(access === "PRIVATE" && groupIds.size > 0 ? { groupIds: [...groupIds] } : {}),
  };
  const scopeError = sharePointScopeError(draft, policy.data, {
    ...proposal,
    requestId: tracking.requestId ?? unsubmittedRequestId,
  });
  const controlsDisabled = busy || unavailable || frozen || Boolean(createdSourceId);

  function go(next: SharePointSetupStep) {
    setError(null);
    void navigate({ search: { credentialId, step: next } });
  }

  const editProposal = creation.edit;

  function create() {
    if (busy) return;
    void creation.submit(
      proposal,
      !unavailable && ready && Boolean(sourceName.trim()) && !scopeError,
    );
  }

  const rootCount =
    draft.scopeMode === "ALL_SITES" ? 0 : parseSharePointLines(draft.siteUrlsText).length;

  return (
    <SettingsLayout wide>
      <PageHeader
        icon={<SharePointIcon />}
        title={ui("SharePoint")}
        description={ui("Index sites, document libraries and pages from SharePoint Online.")}
        actions={
          <Button asChild prominence="secondary" disabled={busy}>
            <Link to="/admin/sources/new">
              <ArrowLeft />
              {ui("Exit setup")}
            </Link>
          </Button>
        }
      />
      {!canManage ? (
        <p role="alert">{ui("You do not have permission to manage credentials and Sources.")}</p>
      ) : null}
      {error ? (
        <p role="alert" className="text-sm text-status-danger-content">
          {ui(error)}
        </p>
      ) : null}
      <SourceCreationStatus
        creation={creation}
        busy={busy}
        pendingMessage={ui(
          "Microsoft is resolving every address in this scope. Your Source is not active yet. Leaving this page does not cancel verification; return here to recover its status.",
        )}
      />
      {policy.isError ? (
        <div className="space-y-2">
          <p role="alert" className="text-sm text-status-danger-content">
            {ui("Selection limits could not be loaded. Creation is disabled.")}
          </p>
          <Button prominence="secondary" onClick={() => void policy.refetch()}>
            {ui("Retry selection limits")}
          </Button>
        </div>
      ) : null}

      <div className="min-w-0">
        {/* The shell sidebar shows the steps; it is hidden on small screens, so say where the flow stands. */}
        <p className="mb-4 font-secondary-body text-content-muted md:hidden">
          {ui("Step {{number}} of {{count}} · {{step}}", {
            number: currentIndex + 1,
            count: sharePointSetupSteps.length,
            step: ui(sharePointSetupSteps[currentIndex].label),
          })}
        </p>
        <div className="min-w-0 flex-1 space-y-6">
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
                  {ui("Continue")} <ArrowRight />
                </Button>
              </footer>
            </>
          ) : null}

          {current === "content" ? (
            <form
              className="space-y-6 rounded-2xl border border-border-default bg-surface-base p-6"
              onSubmit={(event) => {
                event.preventDefault();
                if (!scopeError) go("access");
              }}
            >
              <h2 className="font-heading-h3">{ui("Choose what to synchronize")}</h2>
              <p className="break-words text-sm text-content-secondary">
                {ui("Credential:")} {selected?.name ?? ui("Not selected")}
                {selected?.tenantHost ? ui(" ({{v1}})", { v1: selected.tenantHost }) : ""}
              </p>
              {!ready ? (
                <p role="alert" className="text-sm text-status-warning-content">
                  {ui(
                    "Select a verified credential before choosing content. Return to the credential step to test or replace it.",
                  )}
                </p>
              ) : null}
              <SharePointScopeFields
                draft={draft}
                policy={policy.data}
                disabled={controlsDisabled || !ready}
                onChange={(next) => editProposal(() => setDraft(next))}
              />
              {scopeError ? (
                <p role="alert" className="text-sm text-status-danger-content">
                  {ui(scopeError)}
                </p>
              ) : null}
              <footer className="flex flex-wrap justify-between gap-3">
                <Button prominence="secondary" disabled={busy} onClick={() => go("credential")}>
                  <ArrowLeft /> {ui("Credential")}
                </Button>
                <Button type="submit" disabled={busy || !ready || Boolean(scopeError)}>
                  {ui("Continue")} <ArrowRight />
                </Button>
              </footer>
            </form>
          ) : null}

          {current === "access" ? (
            <form
              className="space-y-6 rounded-2xl border border-border-default bg-surface-base p-6"
              onSubmit={(event) => {
                event.preventDefault();
                if (sourceName.trim()) go("review");
              }}
            >
              <h2 className="font-heading-h3">{ui("Name and access")}</h2>
              <div>
                <Label htmlFor="sharepoint-source-name">{ui("Source name")}</Label>
                <Input
                  id="sharepoint-source-name"
                  value={sourceName}
                  maxLength={120}
                  required
                  disabled={controlsDisabled}
                  onChange={(event) => editProposal(() => setSourceName(event.target.value))}
                  placeholder={ui("e.g. Finance SharePoint")}
                  autoComplete="off"
                  className="mt-2"
                />
              </div>
              <div className="space-y-2">
                <Label id="sharepoint-source-access-label">{ui("Visibility")}</Label>
                <SourceAccessChoice
                  id="sharepoint-source-access"
                  labelledBy="sharepoint-source-access-label"
                  modes={scoped ? ["PRIVATE"] : ["PRIVATE", "PUBLIC"]}
                  value={access}
                  disabled={controlsDisabled}
                  onValueChange={(next) =>
                    editProposal(() => setAccess(next === "PUBLIC" ? "PUBLIC" : "PRIVATE"))
                  }
                />
              </div>
              {access === "PRIVATE" ? (
                <div className="space-y-2">
                  <SourceGroupPicker
                    label={ui("Access groups")}
                    placeholder={
                      scoped ? ui("Select at least one group you manage.") : ui("Select groups")
                    }
                    selected={groupIds}
                    disabled={controlsDisabled}
                    onChange={(ids) => editProposal(() => setGroupIds(ids))}
                  />
                  <p className="font-secondary-body text-content-muted">
                    {ui(
                      "Group members can search and read what this Source imports. SharePoint's own per-item permissions are not synchronized.",
                    )}
                  </p>
                </div>
              ) : null}
              <footer className="flex flex-wrap justify-between gap-3">
                <Button prominence="secondary" disabled={busy} onClick={() => go("content")}>
                  <ArrowLeft /> {ui("Content")}
                </Button>
                <Button type="submit" disabled={busy || !sourceName.trim()}>
                  {ui("Continue")} <ArrowRight />
                </Button>
              </footer>
            </form>
          ) : null}

          {current === "review" ? (
            <form
              className="space-y-6 rounded-2xl border border-border-default bg-surface-base p-6"
              onSubmit={(event) => {
                event.preventDefault();
                create();
              }}
            >
              <h2 className="font-heading-h3">{ui("Review and create")}</h2>
              <div className="grid gap-3">
                <Card size="sm">
                  <CardHeader>
                    <CardTitle>{ui("Credential")}</CardTitle>
                    <CardAction>
                      <Button
                        size="sm"
                        prominence="tertiary"
                        disabled={busy || frozen}
                        onClick={() => go("credential")}
                      >
                        <Pencil /> {ui("Edit")}
                      </Button>
                    </CardAction>
                  </CardHeader>
                  <CardContent>
                    <dl className="grid gap-3 text-sm sm:grid-cols-2">
                      <div>
                        <dt className="text-content-muted">{ui("Name")}</dt>
                        <dd className="mt-1 wrap-anywhere text-content-primary">
                          {selected?.name ?? ui("Not selected")}
                        </dd>
                      </div>
                      <div>
                        <dt className="text-content-muted">{ui("SharePoint host")}</dt>
                        <dd className="mt-1 wrap-anywhere text-content-primary">
                          {selected?.tenantHost ?? ui("Not resolved yet")}
                        </dd>
                      </div>
                    </dl>
                  </CardContent>
                </Card>
                <Card size="sm">
                  <CardHeader>
                    <CardTitle>{ui("Content")}</CardTitle>
                    <CardAction>
                      <Button
                        size="sm"
                        prominence="tertiary"
                        disabled={busy || frozen}
                        onClick={() => go("content")}
                      >
                        <Pencil /> {ui("Edit")}
                      </Button>
                    </CardAction>
                  </CardHeader>
                  <CardContent>
                    <dl className="grid gap-3 text-sm sm:grid-cols-2">
                      <div>
                        <dt className="text-content-muted">{ui("Scope")}</dt>
                        <dd className="mt-1 text-content-primary">
                          {draft.scopeMode === "ALL_SITES"
                            ? ui("All sites")
                            : ui("{{count}} addresses", { count: rootCount })}
                        </dd>
                      </div>
                      <div>
                        <dt className="text-content-muted">{ui("Collects")}</dt>
                        <dd className="mt-1 text-content-primary">
                          {draft.includeDocuments && draft.includePages
                            ? ui("Documents and site pages")
                            : draft.includePages
                              ? ui("Site pages")
                              : ui("Documents")}
                        </dd>
                      </div>
                    </dl>
                  </CardContent>
                </Card>
                <Card size="sm">
                  <CardHeader>
                    <CardTitle>{ui("Name and access")}</CardTitle>
                    <CardAction>
                      <Button
                        size="sm"
                        prominence="tertiary"
                        disabled={busy || frozen}
                        onClick={() => go("access")}
                      >
                        <Pencil /> {ui("Edit")}
                      </Button>
                    </CardAction>
                  </CardHeader>
                  <CardContent>
                    <dl className="grid gap-3 text-sm sm:grid-cols-2">
                      <div>
                        <dt className="text-content-muted">{ui("Source name")}</dt>
                        <dd className="mt-1 wrap-anywhere text-content-primary">
                          {sourceName.trim() || ui("Not set")}
                        </dd>
                      </div>
                      <div>
                        <dt className="text-content-muted">{ui("Visibility")}</dt>
                        <dd className="mt-1 text-content-primary">
                          {access === "PUBLIC"
                            ? ui("Public · everyone in this Tenant")
                            : ui("Private · selected group members")}
                        </dd>
                      </div>
                      {access === "PRIVATE" ? (
                        <div>
                          <dt className="text-content-muted">{ui("Access groups")}</dt>
                          <dd className="mt-1 text-content-primary">
                            {groupIds.size > 0
                              ? ui("{{v1}} selected", { v1: groupIds.size })
                              : ui("None")}
                          </dd>
                        </div>
                      ) : null}
                    </dl>
                  </CardContent>
                </Card>
              </div>
              <SharePointScheduleFields
                draft={draft}
                disabled={controlsDisabled}
                onChange={(next) => editProposal(() => setDraft({ ...draft, ...next }))}
              />
              {scopeError ? (
                <p role="alert" className="text-sm text-status-danger-content">
                  {ui(scopeError)}
                </p>
              ) : null}
              <p className="text-sm text-content-muted">
                {ui(
                  "Creating answers immediately with a receipt. Every address is then resolved with Microsoft, and the Source starts its first run once they all resolve.",
                )}
              </p>
              <footer className="flex flex-wrap justify-between gap-3">
                <Button
                  prominence="secondary"
                  disabled={busy || frozen}
                  onClick={() => go("access")}
                >
                  <ArrowLeft /> {ui("Access")}
                </Button>
                <Button
                  type="submit"
                  pending={createSource.isPending}
                  disabled={
                    busy ||
                    pendingValidation ||
                    tracking.recovering ||
                    tracking.recoveryError ||
                    (!createdSourceId &&
                      !tracking.uncertain &&
                      (unavailable || !ready || !sourceName.trim() || Boolean(scopeError)))
                  }
                >
                  {ui(sourceCreationLabel(creation))} <ArrowRight />
                </Button>
              </footer>
            </form>
          ) : null}
        </div>
      </div>
    </SettingsLayout>
  );
}
