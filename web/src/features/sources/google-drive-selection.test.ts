import { describe, expect, it } from "vitest";
import { googleDriveSelectionError } from "./google-drive-selection";

const policy = {
  maxExplicitRootsPerSource: 1000,
  maxLinkedDocuments: 500,
  maxRequestBytes: 2_097_152,
};

describe("Google Drive selection admission", () => {
  it("counts the full UTF-8 request including the Source name and idempotency key", () => {
    const body = {
      name: "Tài liệu",
      credentialId: "81c51573-31a9-4e67-91c5-f276960c94af",
      requestId: "7c6d85d0-ddd4-445c-9fd0-280f2b3e5b19",
      scopeMode: "SPECIFIC" as const,
      links: ["https://docs.google.com/document/d/document-a/edit"],
    };
    const bytes = new TextEncoder().encode(JSON.stringify(body)).byteLength;
    expect(googleDriveSelectionError(body, { ...policy, maxRequestBytes: bytes })).toBeNull();
    expect(
      googleDriveSelectionError(body, { ...policy, maxRequestBytes: bytes - 1 }),
    ).not.toBeNull();
  });

  it("bounds hidden linked approvals independently of root capacity", () => {
    const body = {
      scopeMode: "SPECIFIC" as const,
      links: ["https://docs.google.com/document/d/document-a/edit"],
      linkedDocumentIds: ["visible", "hidden-on-another-page"],
    };
    expect(googleDriveSelectionError(body, { ...policy, maxLinkedDocuments: 2 })).toBeNull();
    expect(googleDriveSelectionError(body, { ...policy, maxLinkedDocuments: 1 })).not.toBeNull();
  });
});
