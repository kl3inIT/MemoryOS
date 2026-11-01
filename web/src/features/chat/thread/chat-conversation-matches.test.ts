import { expect, it } from "vitest";
import { findConversationHits } from "./chat-conversation-matches";
import { groupThreadTitles } from "@/components/assistant-ui/elements/thread-list";

it("finds literal text in supplied branch messages, never hidden tool payloads", () => {
  const messages = [
    {
      id: "u",
      parts: [
        { type: "text", text: "HUT [Q1] 2026 và hut [Q1]" },
        { type: "tool-call", text: "secret [Q1]" },
      ],
    },
    { id: "a", parts: [{ type: "text", text: "Kết quả HUT [Q1]" }] },
  ];
  const hits = findConversationHits(messages, "[Q1]");
  expect(hits).toHaveLength(3);
  expect(hits.map((hit) => hit.messageId)).toEqual(["u", "u", "a"]);
  expect(hits[0]?.before).toBe("HUT ");
  expect(findConversationHits(messages, "HUT")).toHaveLength(3);
  expect(findConversationHits(messages, ".*")).toHaveLength(0);
  expect(findConversationHits(messages, "  ")).toHaveLength(0);
});

it("keeps original Unicode offsets and bounds rendered matches", () => {
  const messages = [{ id: "a", parts: [{ type: "text", text: "İ ĐÚNG đáp án" }] }];
  expect(findConversationHits(messages, "đúng")[0]).toMatchObject({
    before: "İ ",
    match: "ĐÚNG",
    after: " đáp án",
  });
  expect(
    findConversationHits([{ id: "b", parts: [{ type: "text", text: "a ".repeat(1000) }] }], "a"),
  ).toHaveLength(500);
});

it("groups topics into LobeHub's time buckets by last activity and keeps their identities", () => {
  const now = new Date(2026, 8, 13, 10);
  const at = (...parts: [number, number, number, number?]) => new Date(...parts).toISOString();
  const items = [
    { id: "lastYear", title: "HUT 2025", updatedAt: at(2025, 11, 30) },
    { id: "august", title: "Kế hoạch", updatedAt: at(2026, 7, 1) },
    { id: "today", title: "HUT Q1", updatedAt: at(2026, 8, 13, 9) },
    { id: "thisMonth", title: "Ngày phép", updatedAt: at(2026, 8, 2) },
    { id: "week", title: "Công tác phí", updatedAt: at(2026, 8, 8) },
    { id: "yesterday", title: "Report", updatedAt: at(2026, 8, 12, 23) },
  ];
  const groups = groupThreadTitles(items, now);
  expect(groups.map((group) => group.kind)).toEqual([
    "today",
    "yesterday",
    "week",
    "thisMonth",
    "month",
    "year",
  ]);
  expect(groups.flatMap((group) => group.items.map((item) => item.id))).toEqual([
    "today",
    "yesterday",
    "week",
    "thisMonth",
    "august",
    "lastYear",
  ]);
  expect(items[0]?.id).toBe("lastYear");
});
