package io.memoryos;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class SourcesDependencyRulesTest {

    @Test
    void providersUseOnlyPublicCapabilityContracts() {
        var providerClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.memoryos.connector.adapter", "io.memoryos.ingestion.extraction");

        noClasses()
                .that().resideInAnyPackage("io.memoryos.connector.adapter..", "io.memoryos.ingestion.extraction..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.memoryos..application..",
                        "io.memoryos..persistence.."
                )
                .because("provider adapters may depend only on public capability APIs")
                .check(providerClasses);
    }

    @Test
    void connectorAdaptersDoNotDependOnIngestion() {
        var adapterClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.memoryos.connector.adapter");

        noClasses()
                .that().resideInAPackage("io.memoryos.connector.adapter..")
                .should().dependOnClassesThat().resideInAPackage("io.memoryos.ingestion..")
                .because("connector adapters write the document model, which document owns (ADR 0016)")
                .check(adapterClasses);
    }
}
