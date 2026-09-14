package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PersonaPermissionsTest {
    @Test
    void keysMatchTheUpdateAndDeleteGuards() {
        // Owned custom assistants are editable and deletable regardless of model management.
        assertEquals(new PersonaPermissions(true, true), PersonaPermissions.of(false, false));
        assertEquals(new PersonaPermissions(true, true), PersonaPermissions.of(false, true));
        // The built-in assistant is editable only with MODELS_MANAGE and is never deletable.
        assertEquals(new PersonaPermissions(false, false), PersonaPermissions.of(true, false));
        assertEquals(new PersonaPermissions(true, false), PersonaPermissions.of(true, true));
    }
}
