import { GenerativeUIRender, useAuiState } from "@assistant-ui/react";
import { useMemo, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { PanelsTopLeft } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ErrorState } from "@/components/assistant-ui/elements/error-state";
import { useChatFilePanel } from "./chat-panel-context";
import { artifactSpec, type ChatArtifact } from "./chat-artifacts";

const components = {
  Card: ({ title, children }: { title?: string; children?: ReactNode }) => (
    <Card className="min-w-0">
      <CardHeader>
        <CardTitle className="break-words">{title}</CardTitle>
      </CardHeader>
      <CardContent className="min-w-0 space-y-3">{children}</CardContent>
    </Card>
  ),
  Heading: ({ text }: { text?: string }) => <h3 className="break-words font-semibold">{text}</h3>,
  Text: ({ text }: { text?: string }) => (
    <p className="whitespace-pre-wrap break-words text-sm leading-6">{text}</p>
  ),
  Metric: ({ label, value }: { label?: string; value?: string }) => (
    <dl className="rounded-xl border border-border-subtle p-4">
      <dt className="break-words text-sm text-content-muted">{label}</dt>
      <dd className="mt-1 break-words text-xl font-semibold tabular-nums">{value}</dd>
    </dl>
  ),
  Table: ({ children }: { children?: ReactNode }) => (
    <div className="max-w-full overflow-x-auto rounded-lg border border-border-subtle">
      <table className="w-full border-collapse text-sm">
        <tbody>{children}</tbody>
      </table>
    </div>
  ),
  Row: ({ children }: { children?: ReactNode }) => (
    <tr className="border-b border-border-subtle last:border-0">{children}</tr>
  ),
  Cell: ({ text }: { text?: string }) => (
    <td className="min-w-24 break-words px-3 py-2 align-top">{text}</td>
  ),
};

export function ChatArtifactView({ artifact }: { artifact: ChatArtifact }) {
  const { t } = useTranslation("renderers");
  const spec = useMemo(() => artifactSpec(artifact.spec), [artifact.spec]);
  if (!spec) return <ErrorState title={t("artifactUnavailable")} detail={t("artifactFallback")} />;
  return (
    <div className="min-w-0 space-y-4" data-slot="chat-artifact">
      <p className="text-xs text-content-muted">{t("readOnlyArtifact")}</p>
      {/* Same allowlist renderer as MessagePrimitive.GenerativeUI, without a part scope in the sidebar. */}
      <GenerativeUIRender spec={spec} components={components} />
    </div>
  );
}

const noArtifacts: ChatArtifact[] = [];
export function ChatArtifactCards() {
  const { t } = useTranslation("renderers");
  const panel = useChatFilePanel();
  const id = useAuiState((state) => state.message.id);
  const artifacts = useAuiState(
    (state) =>
      (state.message.metadata.custom.artifacts as ChatArtifact[] | undefined) ?? noArtifacts,
  );
  if (!artifacts.length) return null;
  return (
    <div className="mt-4 grid min-w-0 gap-2">
      {artifacts.map((artifact) => (
        <button
          key={artifact.id}
          type="button"
          aria-label={t("openArtifact", { title: artifact.title })}
          aria-expanded={panel.artifactId === artifact.id}
          aria-controls={panel.artifactId === artifact.id ? panel.panelId : undefined}
          onClick={(event) => panel.openArtifact(id, artifact.id, event.currentTarget)}
          className="flex max-w-sm min-w-0 items-center gap-3 rounded-xl border border-border-default bg-surface-raised p-4 text-left hover:bg-surface-sunken focus-visible:outline-2 focus-visible:outline-ring"
        >
          <PanelsTopLeft className="size-5 shrink-0" />
          <span className="min-w-0">
            <span className="block truncate font-medium" title={artifact.title}>
              {artifact.title}
            </span>
            <span className="text-xs text-content-muted">{t("readOnlyArtifact")}</span>
          </span>
        </button>
      ))}
    </div>
  );
}
