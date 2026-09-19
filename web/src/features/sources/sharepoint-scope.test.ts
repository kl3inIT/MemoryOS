import { describe, expect, it } from "vitest";
import {
  coversSharePointAddress,
  emptySharePointScopeDraft,
  parseSharePointAddress,
  sharePointAddressProblems,
  sharePointExclusionProblems,
  sharePointScheduleError,
  sharePointScopeError,
  sharePointScopeRequest,
  type SharePointAddress,
} from "./sharepoint-scope";

const policy = {
  maxRootsPerSource: 100,
  maxExclusionsPerKind: 100,
  maxRequestBytes: 2_097_152,
};

function addressOf(value: string): SharePointAddress {
  const result = parseSharePointAddress(value);
  if ("error" in result) throw new Error(`expected an address, got ${result.error}`);
  return result.address;
}

describe("SharePoint address parsing", () => {
  it("reduces a sharing link to the underlying library path", () => {
    const address = addressOf(
      "https://contoso.sharepoint.com/:f:/r/sites/Finance/Shared%20Documents/Reports?csf=1",
    );
    expect(address).toMatchObject({
      kind: "FOLDER",
      host: "contoso.sharepoint.com",
      sitePath: "/sites/Finance",
      librarySegment: "Shared Documents",
      folderSegments: ["Reports"],
    });
  });

  it("treats a library Forms view address as the library itself", () => {
    const address = addressOf(
      "https://contoso.sharepoint.com/sites/Finance/Shared Documents/Forms/AllItems.aspx",
    );
    expect(address.kind).toBe("LIBRARY");
    expect(address.folderSegments).toEqual([]);
  });

  it("accepts /teams/ and /personal/ site prefixes", () => {
    expect(addressOf("https://contoso.sharepoint.com/teams/Engineering").kind).toBe("SITE");
    expect(
      addressOf("https://contoso-my.sharepoint.com/personal/an_contoso_com/Documents").kind,
    ).toBe("LIBRARY");
  });

  it("decodes percent-encoded segments including a Vietnamese site name", () => {
    const address = addressOf(
      "https://contoso.sharepoint.com/sites/T%C3%A0i%20ch%C3%ADnh/Shared%20Documents",
    );
    expect(address.sitePath).toBe("/sites/Tài chính");
    expect(address.kind).toBe("LIBRARY");
  });

  it("rejects non-https, foreign hosts and missing site prefixes", () => {
    for (const value of [
      "http://contoso.sharepoint.com/sites/Finance",
      "https://contoso.example.com/sites/Finance",
      "https://contoso.sharepoint.com/Finance",
      "not a url",
      "",
    ]) {
      expect("error" in parseSharePointAddress(value)).toBe(true);
    }
  });
});

describe("SharePoint address coverage", () => {
  it("a site covers its libraries and folders; a folder covers its descendants", () => {
    const site = addressOf("https://contoso.sharepoint.com/sites/Finance");
    const library = addressOf("https://contoso.sharepoint.com/sites/Finance/Shared Documents");
    const folder = addressOf(
      "https://contoso.sharepoint.com/sites/Finance/Shared Documents/Reports",
    );
    expect(coversSharePointAddress(site, library)).toBe(true);
    expect(coversSharePointAddress(site, folder)).toBe(true);
    expect(coversSharePointAddress(library, folder)).toBe(true);
    expect(coversSharePointAddress(folder, library)).toBe(false);
    expect(coversSharePointAddress(library, site)).toBe(false);
  });

  it("does not cover across hosts or unrelated libraries", () => {
    const site = addressOf("https://contoso.sharepoint.com/sites/Finance");
    const otherHost = addressOf("https://fabrikam.sharepoint.com/sites/Finance/Shared Documents");
    const otherLibrary = addressOf("https://contoso.sharepoint.com/sites/Finance/Restricted");
    expect(coversSharePointAddress(site, otherHost)).toBe(false);
    expect(coversSharePointAddress(otherLibrary, otherHost)).toBe(false);
    expect(
      coversSharePointAddress(
        addressOf("https://contoso.sharepoint.com/sites/Finance/Shared Documents"),
        otherLibrary,
      ),
    ).toBe(false);
  });
});

describe("SharePoint per-line problems", () => {
  it("reports duplicates, mixed hosts and nested selections on their own lines", () => {
    const problems = sharePointAddressProblems(
      [
        "https://contoso.sharepoint.com/sites/Finance",
        "https://contoso.sharepoint.com/sites/Finance",
        "https://fabrikam.sharepoint.com/sites/Other",
        "https://contoso.sharepoint.com/sites/Finance/Shared Documents",
      ].join("\n"),
    );
    expect(problems.map((problem) => problem.line)).toEqual([2, 3, 4]);
  });

  it("bounds exclusions to 512 characters each", () => {
    expect(sharePointExclusionProblems("*/Archive/*")).toEqual([]);
    const problems = sharePointExclusionProblems(`ok\n${"x".repeat(513)}`);
    expect(problems).toHaveLength(1);
    expect(problems[0].line).toBe(2);
  });
});

describe("SharePoint scope admission", () => {
  it("requires at least one address in SPECIFIC mode and none in ALL_SITES", () => {
    const draft = { ...emptySharePointScopeDraft(), siteUrlsText: "" };
    expect(sharePointScopeError(draft, policy)).not.toBeNull();
    expect(sharePointScopeError({ ...draft, scopeMode: "ALL_SITES" }, policy)).toBeNull();
  });

  it("enforces the root and exclusion policy limits", () => {
    const many = Array.from(
      { length: 101 },
      (_, index) => `https://contoso.sharepoint.com/sites/Site${index}`,
    ).join("\n");
    const draft = { ...emptySharePointScopeDraft(), siteUrlsText: many };
    expect(sharePointScopeError(draft, policy)).not.toBeNull();
    const exclusions = { ...draft, siteUrlsText: "https://contoso.sharepoint.com/sites/Finance" };
    expect(
      sharePointScopeError(
        { ...exclusions, excludedPathsText: Array.from({ length: 101 }, () => "*/x/*").join("\n") },
        policy,
      ),
    ).not.toBeNull();
  });

  it("rejects a scope that collects nothing and counts the UTF-8 request bytes", () => {
    const draft = {
      ...emptySharePointScopeDraft(),
      siteUrlsText: "https://contoso.sharepoint.com/sites/Tài chính",
      includeDocuments: false,
      includePages: false,
    };
    expect(sharePointScopeError(draft, policy)).not.toBeNull();
    const collecting = { ...draft, includeDocuments: true };
    const body = sharePointScopeRequest(collecting);
    const bytes = new TextEncoder().encode(JSON.stringify(body)).byteLength;
    expect(
      sharePointScopeError(collecting, { ...policy, maxRequestBytes: bytes }, body),
    ).toBeNull();
    expect(
      sharePointScopeError(collecting, { ...policy, maxRequestBytes: bytes - 1 }, body),
    ).not.toBeNull();
  });
});

describe("SharePoint schedule validation", () => {
  it("bounds minutes and hours, allowing 0 to disable pruning", () => {
    const draft = emptySharePointScopeDraft();
    expect(sharePointScheduleError(draft)).toBeNull();
    expect(sharePointScheduleError({ ...draft, syncIntervalMinutes: "0" })).not.toBeNull();
    expect(sharePointScheduleError({ ...draft, syncIntervalMinutes: "1.5" })).not.toBeNull();
    expect(sharePointScheduleError({ ...draft, pruneIntervalHours: "0" })).toBeNull();
    expect(sharePointScheduleError({ ...draft, pruneIntervalHours: "8761" })).not.toBeNull();
    expect(sharePointScheduleError({ ...draft, pruneIntervalHours: "-1" })).not.toBeNull();
  });
});
