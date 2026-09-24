import { describe, expect, it } from "vitest";
import { ApiError } from "@/lib/api";
import { embeddingTestOutcome } from "./embedding-connection";
import {
  applyPreset,
  draftFrom,
  generationRequest,
  matchingPreset,
  rebuildPercent,
  remainingTime,
  searchActions,
  searchSettingsError,
  searchSettingsProblem,
  type Generation,
  type ModelPreset,
  type SearchSettings,
} from "./search-settings";

const qwenPrefix = "Instruct: Given a question, retrieve passages that answer it\nQuery: ";

const present: Generation = {
  id: "0b8c62c4-6e8a-4d07-9a55-1f7b0c3f2a10",
  status: "PRESENT",
  providerId: "5d1a9f38-8e0c-4d63-b7f0-2f7b6f8f4e01",
  providerName: "OpenAI",
  dataBoundary: "EXTERNAL",
  model: "text-embedding-3-large",
  dimensions: 3072,
  queryPrefix: "",
  documentPrefix: "",
  minimumSemanticScore: 0.7,
  chunkConvention: "v3",
  automatic: false,
  documentCount: 11_575,
  createdAt: "2026-08-01T08:00:00Z",
  activatedAt: "2026-08-01T08:05:00Z",
  retainedUntil: null,
  cleanupBlocked: false,
};
const qwen: ModelPreset = {
  model: "Qwen/Qwen3-Embedding-0.6B",
  label: "Qwen3-Embedding 0.6B",
  dimensions: 1024,
  queryPrefix: qwenPrefix,
  documentPrefix: "",
  maxInputTokens: 32_768,
};
const future: Generation = {
  ...present,
  id: "a3f1c0de-2b1e-4c8f-9d77-6a0e5b2c1f33",
  status: "FUTURE",
  providerId: "c2d9a7b1-4f3e-4b6a-8e21-9d0f7a6b5c44",
  providerName: "serving-embedding",
  dataBoundary: "INTERNAL",
  model: qwen.model,
  dimensions: 1024,
  queryPrefix: qwenPrefix,
  documentCount: 8_214,
  activatedAt: null,
};
const settings = (overrides: Partial<SearchSettings> = {}): SearchSettings => ({
  present,
  future: null,
  past: [],
  rebuild: null,
  ...overrides,
});

describe("change-model draft", () => {
  it("opens on the present generation", () => {
    expect(draftFrom(present)).toEqual({
      providerId: present.providerId,
      model: "text-embedding-3-large",
      dimensions: "3072",
      queryPrefix: "",
      documentPrefix: "",
      minimumSemanticScore: "0.7",
    });
  });

  it("prefills model, dimensions and both prefixes from a preset and keeps provider and score", () => {
    const draft = applyPreset(
      { ...draftFrom(present), providerId: future.providerId, minimumSemanticScore: "0.65" },
      qwen,
    );
    expect(draft).toEqual({
      providerId: future.providerId,
      model: qwen.model,
      dimensions: "1024",
      queryPrefix: qwenPrefix,
      documentPrefix: "",
      minimumSemanticScore: "0.65",
    });
    expect(matchingPreset(draft, [qwen])).toBe(qwen);
  });

  it("stops matching the preset once a prefilled field is edited", () => {
    const draft = { ...applyPreset(draftFrom(present), qwen), dimensions: "512" };
    expect(matchingPreset(draft, [qwen])).toBeUndefined();
  });

  it("sends prefixes verbatim, with their newline and trailing space", () => {
    const parsed = generationRequest(applyPreset(draftFrom(present), qwen));
    expect(parsed.problems).toEqual([]);
    expect(parsed.request).toMatchObject({
      model: qwen.model,
      dimensions: 1024,
      queryPrefix: qwenPrefix,
      minimumSemanticScore: 0.7,
    });
  });

  it("names every field that cannot be sent", () => {
    const parsed = generationRequest({
      providerId: "",
      model: "  ",
      dimensions: "10.5",
      queryPrefix: "",
      documentPrefix: "",
      minimumSemanticScore: "1.2",
    });
    expect(parsed.request).toBeNull();
    expect(parsed.problems).toEqual(["provider", "model", "dimensions", "score"]);
    expect(generationRequest({ ...draftFrom(present), dimensions: "0" }).problems).toEqual([
      "dimensions",
    ]);
    expect(generationRequest({ ...draftFrom(present), minimumSemanticScore: "" }).problems).toEqual(
      ["score"],
    );
  });
});

describe("rebuild progress", () => {
  const progress = { ready: 8_214, total: 11_575, failed: 3, pending: 3_358 };

  it("rounds down and never reads 100% before every document is ready", () => {
    expect(rebuildPercent({ ...progress, estimatedSecondsRemaining: 700, switchable: false })).toBe(
      70,
    );
    expect(
      rebuildPercent({
        ready: 11_574,
        total: 11_575,
        failed: 0,
        pending: 1,
        estimatedSecondsRemaining: 1,
        switchable: false,
      }),
    ).toBe(99);
    expect(
      rebuildPercent({
        ready: 11_575,
        total: 11_575,
        failed: 0,
        pending: 0,
        estimatedSecondsRemaining: 0,
        switchable: true,
      }),
    ).toBe(100);
  });

  it("treats an empty corpus as done only once it is switchable", () => {
    const empty = { ready: 0, total: 0, failed: 0, pending: 0, estimatedSecondsRemaining: null };
    expect(rebuildPercent({ ...empty, switchable: false })).toBe(0);
    expect(rebuildPercent({ ...empty, switchable: true })).toBe(100);
  });

  it("formats the remaining time in minutes and hours", () => {
    expect(remainingTime(null).app).toBe("Đang ước lượng");
    expect(remainingTime(-1).app).toBe("Đang ước lượng");
    expect(remainingTime(42).app).toBe("Dưới 1 phút");
    expect(remainingTime(12 * 60 + 20)).toEqual({
      app: "Khoảng {{minutes}} phút",
      values: { minutes: 12 },
    });
    expect(remainingTime(2 * 3600)).toEqual({ app: "Khoảng {{hours}} giờ", values: { hours: 2 } });
    expect(remainingTime(3600 + 25 * 60)).toEqual({
      app: "Khoảng {{hours}} giờ {{minutes}} phút",
      values: { hours: 1, minutes: 25 },
    });
  });
});

describe("available actions", () => {
  const now = Date.parse("2026-09-23T10:00:00Z");
  const past: Generation = {
    ...present,
    id: "f0e1d2c3-b4a5-4697-8877-665544332211",
    status: "PAST",
    retainedUntil: "2026-09-28T10:00:00Z",
  };

  it("offers a model change and a restore only while nothing is rebuilt", () => {
    const idle = searchActions(settings({ past: [past] }), now);
    expect(idle).toMatchObject({ changeModel: true, showSwitch: false, cancel: false });
    expect(idle.restore(past)).toBe(true);
    expect(idle.restore({ ...past, retainedUntil: "2026-09-23T09:59:00Z" })).toBe(false);
    expect(idle.restore({ ...past, retainedUntil: null })).toBe(false);

    const rebuilding = searchActions(settings({ future, past: [past] }), now);
    expect(rebuilding.changeModel).toBe(false);
    expect(rebuilding.restore(past)).toBe(false);
  });

  it("enables the switch only when the server says the rebuild is complete", () => {
    const rebuild = {
      ready: 8_214,
      total: 11_575,
      failed: 0,
      pending: 3_361,
      estimatedSecondsRemaining: 740,
      switchable: false,
    };
    expect(searchActions(settings({ future, rebuild }), now)).toMatchObject({
      showSwitch: true,
      switchEnabled: false,
      cancel: true,
    });
    expect(
      searchActions(settings({ future, rebuild: { ...rebuild, switchable: true } }), now),
    ).toMatchObject({ showSwitch: true, switchEnabled: true });
  });

  it("hides the switch for an automatic rebuild, which switches itself, but keeps cancel", () => {
    const actions = searchActions(
      settings({
        future: { ...future, automatic: true },
        rebuild: {
          ready: 11_575,
          total: 11_575,
          failed: 0,
          pending: 0,
          estimatedSecondsRemaining: 0,
          switchable: true,
        },
      }),
      now,
    );
    expect(actions).toMatchObject({ showSwitch: false, switchEnabled: false, cancel: true });
  });
});

describe("failures", () => {
  const conflict = (detail?: string) =>
    new ApiError(409, { status: 409, detail, code: "SEARCH_CONFLICT" });

  it("gives each conflict the meaning its operation has", () => {
    expect(searchSettingsError(conflict(), "create")).toBe("Đang có một index được dựng lại.");
    expect(searchSettingsError(conflict(), "switch")).toBe("Index mới chưa dựng xong.");
    expect(searchSettingsError(conflict(), "deleteProvider")).toBe(
      "Provider đang được một index dùng.",
    );
    expect(searchSettingsError(new ApiError(403, {}), "load")).toBe(
      "Chỉ quản trị model của Tenant vận hành được đổi cấu hình tìm kiếm.",
    );
    expect(searchSettingsError(new TypeError("offline"), "load")).toBe(
      "Không gửi được yêu cầu. Kiểm tra kết nối rồi thử lại.",
    );
  });

  it("appends the server's detail to a conflict as an inert value", () => {
    expect(
      searchSettingsProblem(conflict("Generation Qwen3 still uses {{provider}}"), "deleteProvider"),
    ).toEqual({
      app: "{{message}} {{detail}}",
      values: {
        message: { app: "Provider đang được một index dùng.", values: undefined },
        detail: "Generation Qwen3 still uses {{provider}}",
      },
    });
    expect(searchSettingsProblem(conflict(), "deleteProvider")).toBe(
      "Provider đang được một index dùng.",
    );
    expect(searchSettingsProblem(new ApiError(400, { detail: "raw" }), "create")).toBe(
      "Cấu hình không hợp lệ. Kiểm tra endpoint, model và số chiều.",
    );
  });
});

describe("connection check", () => {
  it("reports the returned model, its real dimensions and latency", () => {
    expect(
      embeddingTestOutcome({
        ok: true,
        model: qwen.model,
        dimensions: 1024,
        latencyMs: 38,
        error: null,
      }),
    ).toEqual({
      ok: true,
      message: {
        app: "Kết nối được · {{model}} · {{dimensions}} chiều · {{latency}} ms",
        values: { model: qwen.model, dimensions: 1024, latency: 38 },
      },
    });
  });

  it("keeps the provider's reason beside a failed call", () => {
    expect(
      embeddingTestOutcome({
        ok: false,
        model: null,
        dimensions: null,
        latencyMs: 212,
        error: "401 Unauthorized",
      }),
    ).toMatchObject({ ok: false, detail: "401 Unauthorized" });
  });
});
