export type GoogleDriveAuthorizationOutcome = "connected" | "authorization-failed";

export function googleDriveAuthorizationSearch(search: Record<string, unknown>): {
  googleDrive?: GoogleDriveAuthorizationOutcome;
  credentialId?: string;
  step?: "connector";
} {
  return {
    googleDrive:
      search.googleDrive === "connected" || search.googleDrive === "authorization-failed"
        ? search.googleDrive
        : undefined,
    credentialId:
      typeof search.credentialId === "string" &&
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(search.credentialId)
        ? search.credentialId
        : undefined,
    step: search.step === "connector" ? "connector" : undefined,
  };
}

export function launchGoogleDriveAuthorization(authorizationUrl: string) {
  const destination = new URL(authorizationUrl, window.location.origin);
  const allowedOrigin =
    destination.origin === window.location.origin ||
    destination.origin === "https://accounts.google.com";
  if (!allowedOrigin || destination.username || destination.password) {
    throw new Error("Google authorization returned an invalid destination");
  }
  window.location.assign(destination.href);
}
