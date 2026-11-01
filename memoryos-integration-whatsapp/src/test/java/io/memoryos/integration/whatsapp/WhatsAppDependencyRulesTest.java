package io.memoryos.integration.whatsapp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class WhatsAppDependencyRulesTest {
    private static final String PACKAGE = "io.memoryos.integration.whatsapp";

    @Test
    void usesOnlyPublicCapabilityContracts() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(PACKAGE);

        noClasses()
                .that().resideInAPackage(PACKAGE + "..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.memoryos..application..",
                        "io.memoryos..persistence..",
                        "io.memoryos.ingestion..",
                        "io.memoryos.integration.lark..",
                        "io.memoryos.integration.google..",
                        "io.memoryos.integration.microsoft..",
                        "io.memoryos.integration.zalo..",
                        "com.lark.."
                )
                .because("an integration depends on public capability APIs and on no other platform (ADR 0027)")
                .check(classes);
    }
}
