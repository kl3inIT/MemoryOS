import { afterEach, expect, it, vi } from "vitest";
import { readWebPreference, webUsableOn, writeWebPreference } from "./chat-web-preference";
import { MemoryOsChatTransport } from "@/features/chat/runtime/chat-transport";
import type { ChatSession } from "@/lib/hey-api/types.gen";

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

it("restores each chat's Web choice without leaking between actors or tenants", () => {
  const session = { id: "chat-1" } as ChatSession;
  const first = new MemoryOsChatTransport(session, undefined, undefined, "tenant:alice");
  first.selectWeb("auto");
  expect(new MemoryOsChatTransport(session, undefined, undefined, "tenant:alice").webSearch).toBe(
    "auto",
  );
  expect(
    new MemoryOsChatTransport(session, undefined, undefined, "tenant:bob").webSearch,
  ).toBeUndefined();
  expect(readWebPreference("another:alice", session.id)).toBeUndefined();
  expect(readWebPreference("tenant:alice", "chat-2")).toBeUndefined();
  // Turning Web off is a choice of its own: it is remembered, so the default does not turn it back on.
  first.selectWeb("off");
  expect(readWebPreference("tenant:alice", session.id)).toBe("off");
  expect(new MemoryOsChatTransport(session, undefined, undefined, "tenant:alice").webSearch).toBe(
    "off",
  );
});

it("gives a conversation opened from history the choices made in it", () => {
  writeWebPreference("tenant:alice", "chat-1", "off");
  localStorage.setItem("memoryos:image:tenant:alice:chat-1", "auto");
  const transport = new MemoryOsChatTransport(undefined, undefined, undefined, "tenant:alice");
  transport.restore({ id: "chat-1" } as ChatSession, []);
  expect(transport.webSearch).toBe("off");
  expect(transport.image).toBe("auto");
  expect(transport.mcpServerIds).toBeUndefined();
  // Reloading the same conversation keeps what was chosen since.
  transport.selectWeb("auto");
  transport.restore({ id: "chat-1" } as ChatSession, []);
  expect(transport.webSearch).toBe("auto");
});

it("does not inherit Web from another conversation into a new chat", () => {
  writeWebPreference("tenant:alice", "chat-1", "off");
  expect(
    new MemoryOsChatTransport(undefined, undefined, undefined, "tenant:alice").webSearch,
  ).toBeUndefined();
  writeWebPreference("tenant:alice", undefined, "auto");
  writeWebPreference("tenant:alice", "chat-2", undefined);
  expect(localStorage.length).toBe(1);
});

it("ignores invalid preferences and keeps chat usable when storage is denied", () => {
  localStorage.setItem("memoryos:web:alice:chat", "unexpected");
  expect(readWebPreference("alice", "chat")).toBeUndefined();
  vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
    throw new Error("disabled");
  });
  vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
    throw new Error("full");
  });
  expect(readWebPreference("alice", "chat")).toBeUndefined();
  expect(() => writeWebPreference("alice", "chat", "auto")).not.toThrow();
});

it("says Web can run on a model that hosts search or that calls tools with a search connection", () => {
  const availability = {
    searchAvailable: true,
    automaticModelIds: ["tools"],
    nativeModelIds: ["hosted"],
  };
  expect(webUsableOn(availability, "tools")).toBe(true);
  expect(webUsableOn({ ...availability, searchAvailable: false }, "tools")).toBe(false);
  expect(webUsableOn({ ...availability, searchAvailable: false }, "hosted")).toBe(true);
  // Deep research never uses provider-hosted search.
  expect(webUsableOn({ ...availability, searchAvailable: false }, "hosted", false)).toBe(false);
  expect(webUsableOn(availability, "plain")).toBe(false);
  expect(webUsableOn(availability, undefined)).toBe(false);
  expect(webUsableOn(undefined, "tools")).toBe(false);
});
