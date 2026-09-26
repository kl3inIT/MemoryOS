import { useMatch } from "@tanstack/react-router";
import { sharePointSetupSteps } from "@/features/sources/sharepoint/sharepoint-setup-search";

/** Steps of a connector setup flow, shown in place of navigation while the flow is open. */
export type SourceSetupProgress = { steps: readonly string[]; current: number };

/** The connector setup flow the current route is in, if any. */
export function useSourceSetupProgress(): SourceSetupProgress | undefined {
  const googleDrive = useMatch({
    from: "/_authenticated/admin/sources/new/google-drive",
    shouldThrow: false,
  });
  const sharePoint = useMatch({
    from: "/_authenticated/admin/sources/new/sharepoint",
    shouldThrow: false,
  });
  if (googleDrive)
    return {
      steps: ["Credential", "Connector"],
      current: googleDrive.search.step === "connector" ? 1 : 0,
    };
  if (sharePoint) {
    const step = sharePoint.search.step ?? "credential";
    return {
      steps: sharePointSetupSteps.map((entry) => entry.label),
      current: Math.max(
        0,
        sharePointSetupSteps.findIndex((entry) => entry.id === step),
      ),
    };
  }
  return undefined;
}
