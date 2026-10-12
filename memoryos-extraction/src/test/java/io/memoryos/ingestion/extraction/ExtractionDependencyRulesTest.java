package io.memoryos.ingestion.extraction;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ExtractionDependencyRulesTest {
    private static final String PACKAGE = "io.memoryos.ingestion.extraction";

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
                        "io.memoryos.integration.."
                )
                .because("extraction depends on public capability APIs and names no platform (ADR 0027)")
                .check(classes);
    }
}
