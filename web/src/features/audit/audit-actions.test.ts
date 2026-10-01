import { expect, it } from "vitest";
import { actionLabel, detailText, fieldLabel } from "./audit-actions";

it("names a guardrail block and reads what stopped it", () => {
  const ui = (copy: unknown) => String(copy);
  expect(actionLabel("chat_guardrail.block", ui)).toBe("Question blocked");
  expect(fieldLabel("rule", ui)).toBe("Blocked by");
  expect(fieldLabel("session", ui)).toBe("Conversation");
  expect(detailText("topic", ui)).toBe("Sensitive topic");
  expect(detailText("phrase", ui)).toBe("Blocked phrase");
  expect(detailText("LEADERS", ui)).toBe("Lãnh tụ và lãnh đạo");
});
