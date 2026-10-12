/** The Lark setup flow, in order. The page renders one step; the shell sidebar shows progress. */
export const larkSetupSteps = [
  { id: "credential", label: "Credential" },
  { id: "content", label: "Content" },
  { id: "access", label: "Access" },
  { id: "review", label: "Review" },
] as const;

export type LarkSetupStep = (typeof larkSetupSteps)[number]["id"];

const STEPS: readonly string[] = larkSetupSteps.map((step) => step.id);
const GUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Keeps the setup step and chosen credential in the address, so a reload resumes where it was. */
export function larkSetupSearch(search: Record<string, unknown>): {
  credentialId?: string;
  step?: LarkSetupStep;
} {
  return {
    credentialId:
      typeof search.credentialId === "string" && GUID.test(search.credentialId)
        ? search.credentialId
        : undefined,
    step:
      typeof search.step === "string" && STEPS.includes(search.step)
        ? (search.step as LarkSetupStep)
        : undefined,
  };
}
