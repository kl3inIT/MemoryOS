export type SharePointSetupStep = "credential" | "content" | "access" | "review";

const STEPS: SharePointSetupStep[] = ["credential", "content", "access", "review"];
const GUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Keeps the setup step and chosen credential in the address, so a reload resumes where it was. */
export function sharePointSetupSearch(search: Record<string, unknown>): {
  credentialId?: string;
  step?: SharePointSetupStep;
} {
  return {
    credentialId:
      typeof search.credentialId === "string" && GUID.test(search.credentialId)
        ? search.credentialId
        : undefined,
    step:
      typeof search.step === "string" && STEPS.includes(search.step as SharePointSetupStep)
        ? (search.step as SharePointSetupStep)
        : undefined,
  };
}
