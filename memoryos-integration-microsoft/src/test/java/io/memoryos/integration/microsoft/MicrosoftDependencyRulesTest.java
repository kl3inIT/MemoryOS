package io.memoryos.integration.microsoft;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class MicrosoftDependencyRulesTest {
    private static final String PACKAGE = "io.memoryos.integration.microsoft";

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
                        "io.memoryos.integration.google.."
                )
                .because("an integration depends on public capability APIs, writes the document model that document owns, and on no other platform (ADR 0027)")
                .check(classes);
    }
}
