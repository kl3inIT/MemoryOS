import { describe, expect, it } from "vitest";
import type { ChannelResponse } from "@/lib/hey-api/types.gen";
import { channelFailure, channelLine, needsRelink, settling } from "./channel-status";

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

describe("channelLine for a Zalo bot", () => {
  const zalo = (overrides: Partial<ChannelResponse>) =>
    bot({ provider: "ZALO_BOT", region: undefined, transport: "WEBHOOK", ...overrides });

  it("names Zalo, and why it failed when the failure has a line of its own", () => {
    expect(channelLine(zalo({})).line).toBe("Đang trả lời trên Zalo");
    expect(channelLine(zalo({ status: "CONNECTING" })).line).toBe("Đang kết nối tới Zalo…");
    expect(
      channelLine(zalo({ status: "FAILED", failureCode: "CHANNEL_WEBHOOK_UNREACHABLE" })),
    ).toEqual({ line: "Zalo không gọi được tới webhook", tone: "danger" });
    expect(channelLine(zalo({ status: "FAILED", failureCode: "CHANNEL_SOMETHING" })).line).toBe(
      "Không kết nối được tới Zalo",
    );
  });

  it("words the last failure where it has its own words, else keeps its code", () => {
    expect(channelFailure(zalo({ failureCode: "CHANNEL_WEBHOOK_UNREACHABLE" }))).toBe(
      "Zalo không gọi được tới webhook",
    );
    expect(channelFailure(zalo({ failureCode: "CHANNEL_SOMETHING" }))).toBe("CHANNEL_SOMETHING");
    expect(channelFailure(zalo({}))).toBeUndefined();
  });

  it("keeps Lark's own line for a failure code only Zalo explains", () => {
    expect(
      channelLine(bot({ status: "FAILED", failureCode: "CHANNEL_WEBHOOK_UNREACHABLE" })).line,
    ).toBe("Không kết nối được tới Lark");
  });
});

describe("a WhatsApp bot", () => {
  const whatsapp = (overrides: Partial<ChannelResponse>) =>
    bot({ provider: "WHATSAPP", region: undefined, appId: "84912345678", ...overrides });

  it("asks for its code to be scanned while it shows one", () => {
    expect(channelLine(whatsapp({ status: "PAIRING" }))).toEqual({
      line: "Chờ quét mã QR",
      tone: "warning",
    });
    expect(settling([whatsapp({ status: "PAIRING" })])).toBe(true);
  });

  it("names what WhatsApp ended, and only those wait to be linked again", () => {
    const loggedOut = whatsapp({ status: "FAILED", failureCode: "CHANNEL_WHATSAPP_LOGGED_OUT" });
    expect(channelLine(loggedOut)).toEqual({
      line: "WhatsApp đã đăng xuất thiết bị",
      tone: "danger",
    });
    expect(needsRelink(loggedOut)).toBe(true);
    expect(needsRelink({ ...loggedOut, enabled: false, status: "OFF" })).toBe(false);
    expect(
      needsRelink(whatsapp({ status: "FAILED", failureCode: "CHANNEL_PROVIDER_UNAVAILABLE" })),
    ).toBe(false);
  });
});

describe("settling", () => {
  it("polls only while a bot switched on is not connected", () => {
    expect(settling([bot({}), bot({ status: "OFF", enabled: false })])).toBe(false);
    expect(settling([bot({ status: "CONNECTING" })])).toBe(true);
    expect(settling(undefined)).toBe(false);
  });
});
