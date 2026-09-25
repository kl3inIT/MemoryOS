export type BranchStep = { messageId: string; expectedChildId: string | null };

/**
 * The version selections that put `target` on the conversation's selected path, from the root down. Each step
 * selects one message among its siblings, so its parent's current choice is the expected child the server checks.
 * Empty when the target is already shown or is not in this conversation.
 */
export function branchSteps(
  branches: readonly {
    id: string;
    parentMessageId: string | null;
    latestChildMessageId: string | null;
  }[],
  target: string,
): BranchStep[] {
  const byId = new Map(branches.map((branch) => [branch.id, branch]));
  const steps: BranchStep[] = [];
  let node = byId.get(target);
  const seen = new Set<string>();
  while (node?.parentMessageId && !seen.has(node.id)) {
    seen.add(node.id);
    const parent = byId.get(node.parentMessageId);
    if (!parent) return [];
    if (parent.latestChildMessageId !== node.id)
      steps.unshift({ messageId: node.id, expectedChildId: parent.latestChildMessageId });
    node = parent;
  }
  return node ? steps : [];
}
