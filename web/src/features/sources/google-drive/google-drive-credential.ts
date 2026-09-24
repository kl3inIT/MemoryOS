import type {
  GoogleDriveConfigurationResponse,
  GoogleDriveCredentialResponse,
} from "@/lib/hey-api/types.gen";

type CredentialReadiness = Pick<GoogleDriveCredentialResponse, "status" | "authMethod"> & {
  oauthClientConfigured: boolean;
};

/** A service account carries its own key; an OAuth credential also needs its saved OAuth app. */
export function googleDriveCredentialReady(credential: CredentialReadiness | undefined): boolean {
  return (
    credential?.status === "ACTIVE" &&
    (credential.authMethod === "SERVICE_ACCOUNT" || credential.oauthClientConfigured)
  );
}

export function googleDriveConfigurationConnected(
  configuration: GoogleDriveConfigurationResponse | undefined,
): boolean {
  return googleDriveCredentialReady(
    configuration && {
      status: configuration.credentialStatus,
      authMethod: configuration.credentialAuthMethod,
      oauthClientConfigured: configuration.oauthClientConfigured,
    },
  );
}

/** The scopes the Admin console must delegate; MemoryOS requests exactly these. */
export const GOOGLE_SERVICE_ACCOUNT_SCOPES = [
  "https://www.googleapis.com/auth/drive.readonly",
  "https://www.googleapis.com/auth/documents.readonly",
  "https://www.googleapis.com/auth/spreadsheets.readonly",
  "https://www.googleapis.com/auth/admin.directory.user.readonly",
  "https://www.googleapis.com/auth/admin.directory.group.readonly",
];
