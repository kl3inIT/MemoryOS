package io.memoryos.connector;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

public final class SharePointException extends BusinessException {
    private SharePointException(String code, FailureCategory category, String safeMessage, String diagnosticMessage) {
        super(code, category, safeMessage, diagnosticMessage);
    }

    /** SharePoint or the directory refused to say who may read: the Entra application lacks what Auto Sync needs. */
    public static final String PERMISSIONS_DENIED = "SOURCE_SHAREPOINT_SYNC_PERMISSIONS_DENIED";

    /**
     * SharePoint REST, which is where permissions are read, takes only the application token of a certificate
     * credential.
     */
    public static SharePointException syncRequiresCertificate() {
        return new SharePointException("SOURCE_SHAREPOINT_SYNC_REQUIRES_CERTIFICATE", FailureCategory.VALIDATION,
                "Auto Sync reads SharePoint permissions, which needs a certificate credential. Use a certificate"
                        + " credential, or choose another access.",
                "SharePoint permission sync requested with a client-secret credential");
    }

    public static SharePointException permissionsDenied() {
        return new SharePointException(PERMISSIONS_DENIED, FailureCategory.NOT_PERMITTED,
                "Microsoft refused to list SharePoint permissions or group members. Grant the Entra application"
                        + " SharePoint Sites.FullControl.All and Microsoft Graph GroupMember.Read.All.",
                "SharePoint role assignments or group members refused for the application");
    }

    public static SharePointException notConfigured() {
        return new SharePointException("SOURCE_SHAREPOINT_NOT_CONFIGURED", FailureCategory.SERVICE_UNAVAILABLE,
                "SharePoint credentials are not configured. Contact the deployment owner.",
                "SharePoint credential encryption key is missing or unusable");
    }

    public static SharePointException invalidDirectory() {
        return new SharePointException("SOURCE_SHAREPOINT_DIRECTORY_INVALID", FailureCategory.VALIDATION,
                "Supply the Directory (tenant) ID and Application (client) ID as GUIDs.",
                "SharePoint directory or application identifier is not a GUID");
    }

    public static SharePointException invalidSecret() {
        return new SharePointException("SOURCE_SHAREPOINT_SECRET_INVALID", FailureCategory.VALIDATION,
                "Supply the client secret Value, between 1 and 256 characters.",
                "SharePoint client secret is empty or too long");
    }

    public static SharePointException invalidCertificate(String safeMessage, String diagnosticMessage) {
        return new SharePointException("SOURCE_SHAREPOINT_CERTIFICATE_INVALID", FailureCategory.VALIDATION,
                safeMessage, diagnosticMessage);
    }

    /** Every rejection code, so a caller can recognise one without listing the reasons again. */
    public static boolean isCredentialRejection(String code) {
        return code != null && code.startsWith("SOURCE_SHAREPOINT_CREDENTIAL_");
    }

    /**
     * Entra rejected the credential itself, so nothing is stored. Each reason carries its own code
     * because what an administrator must change differs, and a browser cannot read English detail.
     */
    public static SharePointException rejected(SharePointProviderException.Reason reason) {
        return new SharePointException(switch (reason) {
            case INVALID_CLIENT_SECRET -> "SOURCE_SHAREPOINT_CREDENTIAL_SECRET_REJECTED";
            case EXPIRED_CLIENT_SECRET -> "SOURCE_SHAREPOINT_CREDENTIAL_SECRET_EXPIRED";
            case CERTIFICATE_NOT_REGISTERED -> "SOURCE_SHAREPOINT_CREDENTIAL_CERTIFICATE_UNKNOWN";
            case DIRECTORY_NOT_FOUND -> "SOURCE_SHAREPOINT_CREDENTIAL_DIRECTORY_UNKNOWN";
            case APPLICATION_NOT_FOUND -> "SOURCE_SHAREPOINT_CREDENTIAL_APPLICATION_UNKNOWN";
            case CONSENT_REQUIRED -> "SOURCE_SHAREPOINT_CREDENTIAL_CONSENT_REQUIRED";
            case UNCLASSIFIED -> "SOURCE_SHAREPOINT_CREDENTIAL_REJECTED";
        }, FailureCategory.VALIDATION,
                switch (reason) {
                    case INVALID_CLIENT_SECRET -> "Microsoft rejected the client secret. Copy the secret Value, not the Secret ID.";
                    case EXPIRED_CLIENT_SECRET -> "The client secret has expired. Create a new secret in Entra.";
                    case CERTIFICATE_NOT_REGISTERED -> "Upload this certificate to the Entra app registration before saving it here.";
                    case DIRECTORY_NOT_FOUND -> "Microsoft does not know this Directory (tenant) ID.";
                    case APPLICATION_NOT_FOUND -> "Microsoft does not know this Application (client) ID in that directory.";
                    case CONSENT_REQUIRED -> "Grant admin consent for Sites.Read.All in Entra, then save again.";
                    case UNCLASSIFIED -> "Microsoft rejected these credentials.";
                },
                "Entra rejected the SharePoint credential: " + reason);
    }

    public static SharePointException invalidRootUrl(String safeMessage) {
        return new SharePointException("SOURCE_SHAREPOINT_ROOT_URL_INVALID", FailureCategory.VALIDATION,
                safeMessage, "invalid SharePoint site, library or folder address");
    }

    public static SharePointException invalidExclusion() {
        return new SharePointException("SOURCE_SHAREPOINT_EXCLUSION_INVALID", FailureCategory.VALIDATION,
                "Each exclusion must contain 1 to 512 characters, with at most 100 exclusions.",
                "invalid SharePoint exclusion pattern");
    }

    public static SharePointException overlappingRoots() {
        return new SharePointException("SOURCE_SHAREPOINT_ROOTS_OVERLAP", FailureCategory.VALIDATION,
                "Select either a site, library or folder, not one inside another.", "overlapping SharePoint roots");
    }

    public static SharePointException mixedTenants() {
        return new SharePointException("SOURCE_SHAREPOINT_ROOTS_MIXED_TENANTS", FailureCategory.VALIDATION,
                "Every address must be on the same SharePoint host.", "SharePoint roots span more than one host");
    }

    public static SharePointException unavailable() {
        return new SharePointException("SOURCE_SHAREPOINT_UNAVAILABLE", FailureCategory.SERVICE_UNAVAILABLE,
                "Microsoft did not answer. Try again in a moment.", "SharePoint provider request did not complete");
    }

    public static SharePointException needsUpdate() {
        return new SharePointException("SOURCE_SHAREPOINT_NEEDS_UPDATE", FailureCategory.CONFLICT,
                "Update the SharePoint credential before continuing.", "SharePoint credential is marked NEEDS_UPDATE");
    }
}
