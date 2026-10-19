package io.memoryos.integration.whatsapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class WhatsAppDatabaseTest {
    @Test
    void theProcessesOwnDatabaseIsAddressedWithTheBridgesSchema() {
        assertEquals("postgres://memoryos_app:p%40ss%20w%3Ard@memoryos-postgres:5432/memoryos?search_path=whatsapp",
                WhatsAppDatabase.address("jdbc:postgresql://memoryos-postgres:5432/memoryos", "memoryos_app", "p@ss w:rd"));
        assertEquals("postgres://u@db/m?search_path=whatsapp&sslmode=require",
                WhatsAppDatabase.address("jdbc:postgresql://db/m?sslmode=require&prepareThreshold=0", "u", null));
    }

    @Test
    void anotherDatabaseIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> WhatsAppDatabase.address("jdbc:h2:mem:memoryos", "sa", ""));
    }
}
