import { expect, it } from "vitest";
import { branchSteps } from "./chat-branch-steps";

// root ─ q1 ─ a1 ─ q2 ─ a2          (selected path)
//         └ a1' ─ q3 ─ a3            (a regenerated answer with its own follow-up)
const tree = [
  { id: "root", parentMessageId: null, latestChildMessageId: "q1" },
  { id: "q1", parentMessageId: "root", latestChildMessageId: "a1" },
  { id: "a1", parentMessageId: "q1", latestChildMessageId: "q2" },
  { id: "q2", parentMessageId: "a1", latestChildMessageId: "a2" },
  { id: "a2", parentMessageId: "q2", latestChildMessageId: null },
  { id: "a1'", parentMessageId: "q1", latestChildMessageId: "q3" },
  { id: "q3", parentMessageId: "a1'", latestChildMessageId: "a3" },
  { id: "a3", parentMessageId: "q3", latestChildMessageId: null },
];

it("needs no version change for a message already on the selected path", () => {
  expect(branchSteps(tree, "a2")).toEqual([]);
});

it("selects the versions leading to a message on another branch, from the top down", () => {
  // Only the fork is switched: below it, a1' already remembers q3 and q3 remembers a3.
  expect(branchSteps(tree, "a3")).toEqual([{ messageId: "a1'", expectedChildId: "a1" }]);
});

it("finds nothing for a message outside the conversation or a broken chain", () => {
  expect(branchSteps(tree, "elsewhere")).toEqual([]);
  expect(
    branchSteps([{ id: "x", parentMessageId: "missing", latestChildMessageId: null }], "x"),
  ).toEqual([]);
});
