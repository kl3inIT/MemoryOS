// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import {
  emptyLarkScopeDraft,
  larkFolderToken,
  larkInterval,
  larkScopeError,
  larkScopeRequest,
  type LarkScopeDraft,
} from "./lark-scope";

function draft(change: Partial<LarkScopeDraft>): LarkScopeDraft {
  return { ...emptyLarkScopeDraft(), ...change };
}

describe("Lark folder links", () => {
  it("reads the token from a folder link on the credential's region", () => {
    expect(
      larkFolderToken(
        "https://acme.larksuite.com/drive/folder/Jb6HfbPLxlVls8dU0SGj0Z0cpee/",
        "LARK",
      ),
    ).toBe("Jb6HfbPLxlVls8dU0SGj0Z0cpee");
    expect(larkFolderToken("https://acme.feishu.cn/drive/folder/fldcnAbc12345678", "FEISHU")).toBe(
      "fldcnAbc12345678",
    );
  });

  it("accepts a bare token, as a saved root returns it", () => {
    expect(larkFolderToken("  Jb6HfbPLxlVls8dU0SGj0Z0cpee ", "LARK")).toBe(
      "Jb6HfbPLxlVls8dU0SGj0Z0cpee",
    );
  });

  it("refuses links on another region or host, a document link and a plain-HTTP link", () => {
    expect(larkFolderToken("https://acme.feishu.cn/drive/folder/fldcnAbc12345678", "LARK")).toBe(
      null,
    );
    expect(
      larkFolderToken("https://larksuite.com.evil.example/drive/folder/fldcnAbc12345678", "LARK"),
    ).toBe(null);
    expect(larkFolderToken("https://acme.larksuite.com/docx/doxusAbc12345678", "LARK")).toBe(null);
    expect(larkFolderToken("http://acme.larksuite.com/drive/folder/fldcnAbc12345678", "LARK")).toBe(
      null,
    );
  });
});

describe("Lark scope", () => {
  it("needs at least one space or folder", () => {
    expect(larkScopeError(draft({}), "LARK")).toBe(
      "Choose at least one Wiki space or Drive folder.",
    );
  });

  it("names the first line that is not a folder link", () => {
    expect(
      larkScopeError(draft({ folderLinksText: "https://acme.larksuite.com/wiki/abc" }), "LARK"),
    ).toEqual({
      app: "“{{v1}}” is not a Drive folder link on {{v2}}.",
      values: { v1: "https://acme.larksuite.com/wiki/abc", v2: "larksuite.com" },
    });
  });

  it("refuses an interval longer than a year", () => {
    expect(
      larkScopeError(
        draft({ spaceIds: ["7695460898437074458"], interval: { value: "53", unit: "week" } }),
        "LARK",
      ),
    ).toBe("Choose a synchronization interval between one minute and one year.");
  });

  it("sends spaces, then each folder once by token, with the interval in minutes", () => {
    const scope = draft({
      spaceIds: ["7695460898437074458"],
      folderLinksText:
        "https://acme.larksuite.com/drive/folder/Jb6HfbPLxlVls8dU0SGj0Z0cpee\n\nJb6HfbPLxlVls8dU0SGj0Z0cpee",
      interval: larkInterval(120),
    });
    expect(larkScopeError(scope, "LARK")).toBeNull();
    expect(larkScopeRequest(scope, "LARK")).toEqual({
      roots: [
        { kind: "WIKI_SPACE", value: "7695460898437074458" },
        { kind: "DRIVE_FOLDER", value: "Jb6HfbPLxlVls8dU0SGj0Z0cpee" },
      ],
      syncIntervalMinutes: 120,
    });
  });
});
