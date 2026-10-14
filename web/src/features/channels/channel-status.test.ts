import { describe, expect, it } from "vitest";
import type { ChannelResponse } from "@/lib/hey-api/types.gen";
import { channelLine, settling } from "./channel-status";

const bot = (overrides: Partial<ChannelResponse>): ChannelResponse => ({
  id: "b1",
  provider: "LARK",
  name: "Trợ lý",
  region: "LARK",
  appId: "cli_a1b2c3d4e5f6",
  noticeLanguage: "vi",
  enabled: true,
  serviceAccountId: "s1",
  missingScopes: [],
  status: "CONNECTED",
  revision: 1,
  createdAt: "2026-10-14T00:00:00Z",
  updatedAt: "2026-10-14T00:00:00Z",
  ...overrides,
});

describe("channelLine", () => {
  it("names what is wrong before what is missing", () => {
    expect(channelLine(bot({ status: "FAILED", missingScopes: ["im:chat:readonly"] }))).toEqual({
      line: "Không kết nối được tới Lark",
      tone: "danger",
    });
    expect(channelLine(bot({ status: "NOT_RUNNING" })).tone).toBe("warning");
  });

  it("warns of a missing scope only once the bot is connected", () => {
    expect(channelLine(bot({ missingScopes: ["im:chat:readonly"] }))).toEqual({
      line: "Thiếu {{count}} quyền trên Lark",
      tone: "warning",
    });
    expect(channelLine(bot({}))).toEqual({ line: "Đang trả lời trên Lark", tone: "muted" });
    expect(channelLine(bot({ status: "OFF", enabled: false })).tone).toBe("muted");
  });
});

describe("settling", () => {
  it("polls only while a bot switched on is not connected", () => {
    expect(settling([bot({}), bot({ status: "OFF", enabled: false })])).toBe(false);
    expect(settling([bot({ status: "CONNECTING" })])).toBe(true);
    expect(settling(undefined)).toBe(false);
  });
});
