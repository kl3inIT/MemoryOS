package io.memoryos;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** What memoryos-sources may depend on (ADR 0016, ADR 0034). */
class SourcesDependencyRulesTest {
    private static final String ADAPTERS = "io.memoryos.connector.adapter..";
    private static final String EXTRACTION = "io.memoryos.ingestion.extraction..";
    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.memoryos.connector.adapter", "io.memoryos.ingestion.extraction");

    @Test
    void sourcesUseOnlyPublicCapabilityContracts() {
        noClasses()
                .that().resideInAnyPackage(ADAPTERS, EXTRACTION)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.memoryos..application..",
                        "io.memoryos..persistence..")
                .because("the platform adapters and content extraction depend only on public capability APIs")
                .check(classes);
    }

    @Test
    void connectorAdaptersDoNotDependOnIngestion() {
        noClasses()
                .that().resideInAPackage(ADAPTERS)
                .should().dependOnClassesThat().resideInAPackage("io.memoryos.ingestion..")
                .because("a platform adapter writes the document model, which document owns, and registers its snapshot"
                        + " readers by input format")
                .check(classes);
    }

    @Test
    void extractionNamesNoPlatform() {
        noClasses()
                .that().resideInAPackage(EXTRACTION)
                .should().dependOnClassesThat().resideInAPackage(ADAPTERS)
                .because("a provider's snapshot is read by the reader its adapter registers for that input format")
                .check(classes);
    }
}
