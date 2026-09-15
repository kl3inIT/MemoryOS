import { afterEach, expect, it, vi } from "vitest";
import { readWebPreference, writeWebPreference } from "./chat-web-preference";
import { MemoryOsChatTransport } from "./chat-transport";
import type { ChatSession } from "@/lib/hey-api/types.gen";

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

it("restores each chat's Web intent without leaking between actors or tenants", () => {
  const session = { id: "chat-1" } as ChatSession;
  const first = new MemoryOsChatTransport(session, undefined, undefined, "tenant:alice");
  first.selectWeb("auto");
  expect(new MemoryOsChatTransport(session, undefined, undefined, "tenant:alice").webSearch).toBe(
    "auto",
  );
  expect(new MemoryOsChatTransport(session, undefined, undefined, "tenant:bob").webSearch).toBe(
    "off",
  );
  expect(readWebPreference("another:alice", session.id)).toBe("off");
  expect(readWebPreference("tenant:alice", "chat-2")).toBe("off");
  first.selectWeb("off");
  expect(readWebPreference("tenant:alice", session.id)).toBe("off");
});

it("does not inherit Web from another conversation into a new chat", () => {
  writeWebPreference("tenant:alice", "chat-1", "auto");
  expect(new MemoryOsChatTransport(undefined, undefined, undefined, "tenant:alice").webSearch).toBe(
    "off",
  );
  writeWebPreference("tenant:alice", undefined, "auto");
  expect(localStorage.length).toBe(1);
});

it("ignores invalid preferences and keeps chat usable when storage is denied", () => {
  localStorage.setItem("memoryos:web:alice:chat", "unexpected");
  expect(readWebPreference("alice", "chat")).toBe("off");
  vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
    throw new Error("disabled");
  });
  vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
    throw new Error("full");
  });
  expect(readWebPreference("alice", "chat")).toBe("off");
  expect(() => writeWebPreference("alice", "chat", "auto")).not.toThrow();
});
