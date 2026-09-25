package io.memoryos.provider;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.Test;

class ProviderDependencyRulesTest {

    @Test
    void providersUseOnlyPublicCapabilityContracts() {
        var providerClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.memoryos.provider");

        noClasses()
                .that().resideInAPackage("io.memoryos.provider..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.memoryos..application..",
                        "io.memoryos..persistence.."
                )
                .because("provider adapters may depend only on public capability APIs")
                .check(providerClasses);
    }
}
