package io.memoryos;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import java.util.Set;

import org.junit.jupiter.api.Test;

class CoreDependencyRulesTest {

    private static final Set<String> CAPABILITIES = Set.of(
            "identity",
            "tenant",
            "invitation",
            "objectstorage",
            "connector",
            "document",
            "ingestion"
    );

    private final JavaClasses coreClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.memoryos");

    @Test
    void persistencePackagesAreVisibleOnlyToTheirOwningCapability() {
        CAPABILITIES.forEach(capability -> persistencePackageIsOwnedBy(capability).check(coreClasses));
    }

    @Test
    void coreDoesNotDependOnDeployables() {
        noClasses()
                .that().resideInAPackage("io.memoryos..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.memoryos.api..",
                        "io.memoryos.worker.."
                )
                .because("core must not depend on a deployable composition root")
                .check(coreClasses);
    }

    private static ArchRule persistencePackageIsOwnedBy(String capability) {
        String ownerPackage = "io.memoryos." + capability + "..";
        String persistencePackage = "io.memoryos." + capability + ".persistence..";

        return noClasses()
                .that().resideOutsideOfPackage(ownerPackage)
                .should().dependOnClassesThat().resideInAnyPackage(persistencePackage)
                .because(capability + " owns its persistence implementation");
    }
}
