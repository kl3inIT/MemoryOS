import { renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { i18n } from "@/i18n/index";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ChatLibraryEntry } from "@/lib/hey-api/types.gen";
import { entryDownloadUrl, entryPreviewTarget, entryReason } from "./library-entries";

const entry = (overrides: Partial<ChatLibraryEntry> = {}): ChatLibraryEntry => ({
  kind: "UPLOAD",
  id: "11111111-1111-4111-8111-111111111111",
  name: "hop-dong.pdf",
  mediaType: "application/pdf",
  sizeBytes: 2048,
  category: "DOCUMENT",
  at: "2026-09-20T08:00:00Z",
  owned: true,
  starred: false,
  openedAt: null,
  ownerName: null,
  reason: { kind: "OWNER", names: [] },
  sessionId: null,
  sessionTitle: null,
  messageId: null,
  meeting: null,
  agents: [],
  document: null,
  ...overrides,
});

const document = (overrides: Partial<NonNullable<ChatLibraryEntry["document"]>> = {}) => ({
  generation: "7",
  title: "Quy chế lương",
  sourceId: "22222222-2222-4222-8222-222222222222",
  sourceName: "Nhân sự",
  sourceType: "FILE" as const,
  providerUrl: null,
  ...overrides,
});

beforeEach(async () => {
  await i18n.changeLanguage("vi");
});

/** The reason line as the row shows it, in Vietnamese. */
function reasonLine(value: ChatLibraryEntry) {
  const { result } = renderHook(() => useAppTranslation());
  const reason = entryReason(value);
  return reason && result.current(reason);
}

describe("the reason a row is visible", () => {
  it("says nothing for the person's own files", () => {
    expect(reasonLine(entry())).toBeUndefined();
  });

  it("names who shared a meeting with the person, or says it was shared when the owner is unknown", () => {
    const shared = entry({
      kind: "MEETING",
      owned: false,
      reason: { kind: "MEMBER_SHARE", names: [] },
    });
    expect(reasonLine({ ...shared, ownerName: "Trần Thu Hà" })).toBe("Chia sẻ bởi Trần Thu Hà");
    expect(reasonLine(shared)).toBe("Được chia sẻ với bạn");
  });

  it("names the Groups that carried a meeting share", () => {
    expect(
      reasonLine(
        entry({
          kind: "MEETING",
          owned: false,
          ownerName: "Trần Thu Hà",
          reason: { kind: "GROUP_SHARE", names: ["Kế toán", "Ban giám đốc"] },
        }),
      ),
    ).toBe("Qua nhóm Kế toán, Ban giám đốc");
  });

  it("names the assistants granting a file, falling back to the granting assistants", () => {
    const agentFile = entry({
      kind: "AGENT_FILE",
      owned: false,
      reason: { kind: "AGENT", names: ["Trợ lý nhân sự"] },
      agents: [{ id: "33333333-3333-4333-8333-333333333333", name: "Trợ lý pháp chế" }],
    });
    expect(reasonLine(agentFile)).toBe("Qua trợ lý Trợ lý nhân sự");
    expect(reasonLine({ ...agentFile, reason: { kind: "AGENT", names: [] } })).toBe(
      "Qua trợ lý Trợ lý pháp chế",
    );
  });

  it("names the Source and how its documents reach the person", () => {
    const source = { kind: "DOCUMENT" as const, owned: false };
    expect(
      reasonLine(
        entry({ ...source, reason: { kind: "PUBLIC_SOURCE", names: [] }, document: document() }),
      ),
    ).toBe("Nhân sự · Công khai trong tổ chức");
    expect(
      reasonLine(
        entry({
          ...source,
          reason: { kind: "GROUP_SOURCE", names: ["Kế toán"] },
          document: document(),
        }),
      ),
    ).toBe("Nhân sự · Qua nhóm Kế toán");
    expect(
      reasonLine(
        entry({
          ...source,
          reason: { kind: "PROVIDER_SOURCE", names: [] },
          document: document({ sourceName: "Drive công ty", sourceType: "GOOGLE_DRIVE" }),
        }),
      ),
    ).toBe("Drive công ty · Quyền từ Google Drive");
    expect(
      reasonLine(
        entry({
          ...source,
          reason: { kind: "PROVIDER_SOURCE", names: [] },
          document: document({ sourceName: "Hồ sơ dự án", sourceType: "SHAREPOINT" }),
        }),
      ),
    ).toBe("Hồ sơ dự án · Quyền từ SharePoint");
  });
});

describe("where an entry is previewed and downloaded", () => {
  it("reads every file through the route owned rows use, an assistant's file as an upload", () => {
    expect(entryPreviewTarget(entry())).toMatchObject({ source: "attachment" });
    expect(entryPreviewTarget(entry({ kind: "GENERATED" }))).toMatchObject({ source: "generated" });
    expect(entryPreviewTarget(entry({ kind: "IMAGE" }))).toMatchObject({ source: "image" });
    const agentFile = entry({ kind: "AGENT_FILE", owned: false, name: "noi-quy.docx" });
    expect(entryPreviewTarget(agentFile)).toEqual({
      source: "attachment",
      id: agentFile.id,
      filename: "noi-quy.docx",
      mediaType: "application/pdf",
    });
    expect(entryDownloadUrl(agentFile)).toBe(`/api/chat/files/${agentFile.id}/content`);
  });

  it("previews neither a meeting nor a Source document in the file preview", () => {
    const meeting = entry({
      kind: "MEETING",
      mediaType: null,
      sizeBytes: null,
      meeting: { status: "ENDED", minutesReady: true, hasTranscript: true, durationMs: 60_000 },
    });
    expect(entryPreviewTarget(meeting)).toBeUndefined();
    expect(entryDownloadUrl(meeting)).toBeUndefined();
    expect(entryPreviewTarget(entry({ kind: "DOCUMENT", document: document() }))).toBeUndefined();
  });

  it("downloads a Source document's served generation, and nothing while it is being indexed", () => {
    const served = entry({ kind: "DOCUMENT", owned: false, document: document() });
    expect(entryDownloadUrl(served)).toMatch(
      new RegExp(`/api/search/documents/${served.id}/original\\?generation=7$`),
    );
    expect(
      entryDownloadUrl({ ...served, document: document({ generation: null }) }),
    ).toBeUndefined();
  });
});
