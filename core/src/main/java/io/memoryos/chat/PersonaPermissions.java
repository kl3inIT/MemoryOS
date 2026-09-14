package io.memoryos.chat;

/** Read-side affordance map for one assistant, projected from the update and delete guards; a browser hint only. */
public record PersonaPermissions(boolean edit, boolean delete) {
    /** @param modelsManager the actor holds {@code MODELS_MANAGE}, which the update guard requires for the built-in assistant */
    static PersonaPermissions of(boolean builtin, boolean modelsManager) {
        return new PersonaPermissions(!builtin || modelsManager, !builtin);
    }
}
