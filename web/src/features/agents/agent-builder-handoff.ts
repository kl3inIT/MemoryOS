/**
 * MEM-280: the description the create dialog hands to the new assistant's Agent Builder (LobeHub's "What should
 * this Agent do?"). Taken once, by the editor that opens next, so a reload or a later visit sends nothing.
 */
const briefs = new Map<string, string>();

export function handBrief(agentId: string, description: string) {
  briefs.set(agentId, description);
}

export function hasBrief(agentId: string) {
  return briefs.has(agentId);
}

export function takeBrief(agentId: string) {
  const brief = briefs.get(agentId);
  briefs.delete(agentId);
  return brief;
}
