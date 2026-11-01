import { describe, expect, it } from "vitest";
import { BuilderChanges } from "./agent-builder-changes";
import { initialValues, type AgentValues } from "./agent-form";

function form(start: Partial<AgentValues> = {}) {
  const values: AgentValues = { ...initialValues(), name: "Chính sách nhân sự", ...start };
  const changes = new BuilderChanges(
    () => values,
    (field, value) => {
      (values as Record<string, unknown>)[field] = value;
    },
  );
  return { values, changes };
}

describe("BuilderChanges", () => {
  it("puts a change in the form once, keeping only the fields it really changes", () => {
    const { values, changes } = form({ title: "" });
    const change = {
      toolCallId: "call_1",
      change: {
        name: "Chính sách nhân sự",
        title: "Chuyên viên chính sách nhân sự",
        grounded: true,
        starterPrompts: ["Nghỉ phép năm bao nhiêu ngày?"],
      },
    };
    changes.apply(change);
    changes.apply({ ...change, change: { title: "Bị bỏ qua" } });

    expect(values.title).toBe("Chuyên viên chính sách nhân sự");
    expect(values.grounded).toBe(true);
    expect(values.starterPrompts).toEqual(["Nghỉ phép năm bao nhiêu ngày?"]);
    expect(changes.get("call_1")?.fields.map((field) => field.field)).toEqual([
      "title",
      "starterPrompts",
      "grounded",
    ]);
  });

  it("undoes a change field by field and leaves a field the person edited since", () => {
    const { values, changes } = form({ title: "", instructions: "" });
    changes.apply({
      toolCallId: "call_1",
      change: { title: "Chuyên viên", instructions: "Chỉ trả lời từ tài liệu." },
    });
    values.instructions = "Chỉ trả lời từ tài liệu, kèm nguồn.";

    expect(changes.undo("call_1")).toEqual(["instructions"]);
    expect(values.title).toBe("");
    expect(values.instructions).toBe("Chỉ trả lời từ tài liệu, kèm nguồn.");
    expect(changes.get("call_1")?.undone).toBe(true);
    expect(changes.undo("call_1")).toEqual([]);
  });

  it("tells its cards when a change lands or is undone", () => {
    const { changes } = form();
    let seen = 0;
    changes.subscribe(() => {
      seen += 1;
    });
    changes.apply({ toolCallId: "call_1", change: { greeting: "Chào bạn!" } });
    changes.undo("call_1");
    expect(seen).toBe(2);
  });
});
