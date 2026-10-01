import { expect, it } from "vitest";
import type { AvailableModel, McpConnection } from "@/lib/hey-api/types.gen";
import { chatToolDefaults, turnModelOf } from "./chat-tool-defaults";

function model(id: string, toolCalling = true, isDefault = false) {
  return { id, isDefault, capabilities: { toolCalling } } as AvailableModel;
}

function connection(id: string, change: Partial<McpConnection> = {}) {
  return { id, connectionState: "CONNECTED", enabledToolCount: 3, ...change } as McpConnection;
}

const tools = model("tools");
const everything = {
  grounded: false,
  allowed: { web: true, image: true, mcpServerIds: null },
  model: tools,
  web: { searchAvailable: true, automaticModelIds: ["tools"], nativeModelIds: [] },
  image: { available: true },
  mayGenerateImages: true,
  connections: [connection("crm"), connection("wiki")],
  deepResearch: false,
};

it("turns on every tool the conversation can use", () => {
  expect(chatToolDefaults(everything)).toEqual({
    web: "auto",
    image: "auto",
    mcpServerIds: ["crm", "wiki"],
  });
});

it("keeps every tool off until the turn's model is known, on a model without tools and when answering from documents only", () => {
  const off = { web: "off", image: "off", mcpServerIds: [] };
  expect(chatToolDefaults({ ...everything, model: undefined })).toEqual(off);
  expect(chatToolDefaults({ ...everything, model: model("plain", false) })).toEqual(off);
  expect(chatToolDefaults({ ...everything, grounded: true })).toEqual(off);
});

it("turns a tool on only where it can run", () => {
  expect(chatToolDefaults({ ...everything, web: undefined }).web).toBe("off");
  expect(
    chatToolDefaults({ ...everything, web: { ...everything.web, searchAvailable: false } }).web,
  ).toBe("off");
  const hosted = {
    searchAvailable: false,
    automaticModelIds: ["tools"],
    nativeModelIds: ["tools"],
  };
  expect(chatToolDefaults({ ...everything, web: hosted }).web).toBe("auto");
  // Deep research never uses provider-hosted search, and the server refuses Web without a search connection.
  expect(chatToolDefaults({ ...everything, web: hosted, deepResearch: true }).web).toBe("off");
  expect(chatToolDefaults({ ...everything, image: { available: false } }).image).toBe("off");
  expect(chatToolDefaults({ ...everything, image: undefined }).image).toBe("off");
  expect(chatToolDefaults({ ...everything, mayGenerateImages: false }).image).toBe("off");
});

it("never turns on a tool the agent forbids", () => {
  expect(
    chatToolDefaults({
      ...everything,
      allowed: { web: false, image: false, mcpServerIds: ["wiki"] },
    }),
  ).toEqual({ web: "off", image: "off", mcpServerIds: ["wiki"] });
});

it("offers only MCP servers that are connected and have tools, at most eight", () => {
  const connections = [
    connection("signed-out", { connectionState: "NOT_CONNECTED" }),
    connection("expired", { connectionState: "REAUTH_REQUIRED" }),
    connection("empty", { enabledToolCount: 0 }),
    connection("shared", { connectionState: "SHARED" }),
    ...Array.from({ length: 9 }, (_, index) => connection(`server-${index}`)),
  ];
  const ids = chatToolDefaults({ ...everything, connections }).mcpServerIds;
  expect(ids).toHaveLength(8);
  expect(ids[0]).toBe("shared");
  expect(ids).not.toContain("signed-out");
  expect(ids).not.toContain("expired");
  expect(ids).not.toContain("empty");
  expect(chatToolDefaults({ ...everything, connections: undefined }).mcpServerIds).toEqual([]);
});

it("resolves the turn's model as the server does", () => {
  const catalog = [
    model("default", true, true),
    model("agent"),
    model("personal"),
    model("chosen"),
  ];
  expect(turnModelOf(catalog, ["chosen", "agent", "personal"])?.id).toBe("chosen");
  expect(turnModelOf(catalog, [undefined, "agent", "personal"])?.id).toBe("agent");
  expect(turnModelOf(catalog, [undefined, null, "personal"])?.id).toBe("personal");
  expect(turnModelOf(catalog, [undefined, null, undefined])?.id).toBe("default");
  // A model that is no longer offered falls back to the organization's default.
  expect(turnModelOf(catalog, ["removed", "agent"])?.id).toBe("default");
  expect(turnModelOf(undefined, ["chosen"])).toBeUndefined();
});
