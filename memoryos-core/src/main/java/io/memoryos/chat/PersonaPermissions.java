package io.memoryos.chat;

/**
 * Read-side affordance map for one agent, projected from the service guards; a browser hint only.
 *
 * @param edit     edit fields, tools and labels
 * @param share    replace people and Group shares
 * @param setPublic change Tenant-wide visibility (owner, owner Group member or agent manager)
 * @param delete   soft-delete the agent
 * @param transfer transfer ownership
 * @param leave    the actor holds a direct share it can leave
 * @param manage   the actor holds {@code AGENTS_MANAGE} (listing, featuring, restore)
 */
public record PersonaPermissions(boolean edit, boolean share, boolean setPublic, boolean delete, boolean transfer,
                                 boolean leave, boolean manage) {
}
