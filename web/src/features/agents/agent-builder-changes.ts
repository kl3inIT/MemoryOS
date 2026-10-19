import type { AssistantChange } from "@/features/chat/runtime/chat-stream-events";
import type { AgentValues } from "./agent-form";

/** The form fields the Agent Builder may change (MEM-280), in the order its change card lists them. */
const builderFields = [
  "name",
  "title",
  "description",
  "avatarSeed",
  "greeting",
  "starterPrompts",
  "tools",
  "sourceIds",
  "documentSetIds",
  "mcpServerIds",
  "modelConfigurationId",
  "reasoningEffort",
  "grounded",
  "instructions",
] as const satisfies readonly (keyof AgentValues)[];
export type BuilderField = (typeof builderFields)[number];

/** One field of a change: the form's value it replaced and the value it set. */
export type FieldChange<K extends BuilderField = BuilderField> = {
  field: K;
  before: AgentValues[K];
  after: AgentValues[K];
};

/** A change of the builder as the form took it, and whether the person has undone it. */
export type BuilderChange = { id: string; fields: FieldChange[]; undone: boolean };

const same = (left: unknown, right: unknown) => JSON.stringify(left) === JSON.stringify(right);

/** The change's new value of each field, in the form's terms; a field equal to the form's value is no change. */
function changedFields(values: AgentValues, change: AssistantChange["change"]): FieldChange[] {
  const fields: FieldChange[] = [];
  for (const field of builderFields) {
    const sent = change[field];
    if (sent === undefined) continue;
    // An empty reasoning level clears it, as an empty level does in the form.
    const after = sent as AgentValues[typeof field];
    if (!same(values[field], after))
      fields.push({ field, before: values[field], after } as FieldChange);
  }
  return fields;
}

/** What the form needs to take to undo a change: each field back, unless the person has edited it since. */
function undoFields(values: AgentValues, change: BuilderChange) {
  const restored: FieldChange[] = [];
  const kept: BuilderField[] = [];
  for (const field of change.fields) {
    if (same(values[field.field], field.after)) restored.push(field);
    else kept.push(field.field);
  }
  return { restored, kept };
}

/**
 * The builder thread's changes, by tool call, for the change cards to read. A change is taken once: a reconnect that
 * replays its event finds it here and leaves the form alone.
 */
export class BuilderChanges {
  private changes = new Map<string, BuilderChange>();
  private version = 0;
  private listeners = new Set<() => void>();

  private readonly values: () => AgentValues;
  private readonly set: <K extends BuilderField>(field: K, value: AgentValues[K]) => void;

  constructor(
    values: () => AgentValues,
    set: <K extends BuilderField>(field: K, value: AgentValues[K]) => void,
  ) {
    this.values = values;
    this.set = set;
  }

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };
  getVersion = () => this.version;
  get(id: string) {
    return this.changes.get(id);
  }

  apply(change: Pick<AssistantChange, "toolCallId" | "change">) {
    if (this.changes.has(change.toolCallId)) return;
    const fields = changedFields(this.values(), change.change);
    for (const field of fields) this.set(field.field, field.after);
    this.changes.set(change.toolCallId, { id: change.toolCallId, fields, undone: false });
    this.changed();
  }

  /** Puts the change's fields back and says which ones were left because the person edited them since. */
  undo(id: string) {
    const change = this.changes.get(id);
    if (!change || change.undone) return [];
    const { restored, kept } = undoFields(this.values(), change);
    for (const field of restored) this.set(field.field, field.before);
    this.changes.set(id, { ...change, undone: true });
    this.changed();
    return kept;
  }

  private changed() {
    this.version += 1;
    for (const listener of this.listeners) listener();
  }
}
