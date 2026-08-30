package io.memoryos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithArchitectureTest {

    private static final Set<String> CAPABILITIES = Set.of(
            "identity",
            "tenant",
            "invitation",
            "connector",
            "document",
            "ingestion"
    );

    private final ApplicationModules modules = ApplicationModules.of(MemoryOsModules.class);

    @Test
    void modulesAreWellFormedAndComplete() {
        modules.verify();

        assertEquals(CAPABILITIES.size(), modules.stream().count());
        CAPABILITIES.forEach(capability -> {
            var module = modules.getModuleByName(capability);
            assertTrue(module.isPresent(), capability);
            assertFalse(module.orElseThrow().isOpen(), capability + " must remain closed");
        });
    }
}
