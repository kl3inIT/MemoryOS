package io.memoryos.integration.lark;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class LarkDependencyRulesTest {
    private static final String PACKAGE = "io.memoryos.integration.lark";

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
                        "io.memoryos.integration.google..",
                        "io.memoryos.integration.microsoft..",
                        "io.memoryos.integration.zalo.."
                )
                .because("an integration depends on public capability APIs, writes the document model that document owns, and on no other platform (ADR 0027)")
                .check(classes);
    }
}
