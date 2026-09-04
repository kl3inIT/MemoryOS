import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useMemo, useState } from "react";
import { Input } from "@/components/ui/input";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  createFileSourceMutation,
  listSourcesQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { sourceMutationError } from "./source-errors";
import { sourceProviders } from "./source-provider-catalog";
import { SourceSetupWizard, type SourceSetupStep } from "./source-setup-wizard";

type FileSetupStepId = "configuration";

const fileProvider = sourceProviders[0];

export function CreateFileSourcePage() {
  const queryClient = useQueryClient();
  const navigate = useNavigate({ from: "/admin/sources/new/file" });
  const createSource = useMutation(createFileSourceMutation());
  const [sourceName, setSourceName] = useState("");
  const [activeStepId, setActiveStepId] = useState<FileSetupStepId>("configuration");
  const [error, setError] = useState<string | null>(null);
  const normalizedName = sourceName.trim();

  const steps = useMemo(
    () =>
      [
        {
          id: "configuration",
          label: "Configuration",
          title: "Name this file source",
          description:
            "Use a name that tells your organization which documents belong in this source.",
          canContinue: normalizedName.length > 0,
          completionLabel: "Create source",
          content: (
            <div className="max-w-xl">
              <label
                htmlFor="file-source-name"
                className="font-secondary-action text-content-primary"
              >
                Source name
              </label>
              <Input
                id="file-source-name"
                value={sourceName}
                maxLength={120}
                autoFocus
                onChange={(event) => setSourceName(event.target.value)}
                placeholder="e.g. Product documentation"
                className="mt-2 w-full"
              />
              <p className="mt-2 font-secondary-body text-content-muted">
                You can upload PDF, DOCX, TXT, and Markdown files after creating the source.
              </p>
            </div>
          ),
        },
      ] satisfies readonly [SourceSetupStep<FileSetupStepId>],
    [normalizedName.length, sourceName],
  );

  async function createFileSource() {
    if (!normalizedName || createSource.isPending) return;
    setError(null);
    try {
      const created = await createSource.mutateAsync({
        body: { name: normalizedName },
        headers: sameOriginMutationHeaders,
      });
      await queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() });
      await navigate({
        to: "/admin",
        search: { sourceId: created.source.id },
      });
    } catch (cause) {
      setError(sourceMutationError(cause, "create"));
    }
  }

  return (
    <SourceSetupWizard
      provider={fileProvider}
      steps={steps}
      activeStepId={activeStepId}
      onActiveStepChange={setActiveStepId}
      onComplete={createFileSource}
      pending={createSource.isPending}
      error={error}
    />
  );
}
