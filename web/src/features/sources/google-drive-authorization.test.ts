import { describe, expect, it } from "vitest";
import {
  googleDriveAuthorizationSearch,
  launchGoogleDriveAuthorization,
} from "./google-drive-authorization";

describe("Google Drive authorization boundaries", () => {
  it("accepts only safe callback outcomes and drops unrelated callback values", () => {
    expect(
      googleDriveAuthorizationSearch({ googleDrive: "connected", code: "private-code" }),
    ).toEqual({ googleDrive: "connected" });
    expect(
      googleDriveAuthorizationSearch({
        googleDrive: "authorization-failed",
        error_description: "private-detail",
      }),
    ).toEqual({ googleDrive: "authorization-failed" });
    expect(
      googleDriveAuthorizationSearch({ googleDrive: "private-detail", code: "private-code" }),
    ).toEqual({ googleDrive: undefined });
  });

  it("retains only valid credential identities and the connector step, never Source callback state", () => {
    const credentialId = "81c51573-31a9-4e67-91c5-f276960c94af";
    expect(
      googleDriveAuthorizationSearch({
        googleDrive: "connected",
        credentialId,
        sourceId: "obsolete-source",
        code: "private-code",
      }),
    ).toEqual({ googleDrive: "connected", credentialId });
    expect(
      googleDriveAuthorizationSearch({
        credentialId,
        step: "connector",
        oauthClientJson: "private-json",
      }),
    ).toEqual({ credentialId, step: "connector" });
    expect(
      googleDriveAuthorizationSearch({ credentialId: "private-token", step: "private-step" }),
    ).toEqual({ googleDrive: undefined, credentialId: undefined, step: undefined });
  });

  it("rejects unsafe authorization destinations before attempting browser navigation", () => {
    expect(() =>
      launchGoogleDriveAuthorization("https://accounts.google.com.evil.example/authorize"),
    ).toThrow();
    expect(() =>
      launchGoogleDriveAuthorization("http://accounts.google.com/o/oauth2/v2/auth"),
    ).toThrow();
    expect(() =>
      launchGoogleDriveAuthorization("https://user:password@accounts.google.com/o/oauth2/v2/auth"),
    ).toThrow();
    expect(() => launchGoogleDriveAuthorization("javascript:alert(document.cookie)")).toThrow();
  });
});
