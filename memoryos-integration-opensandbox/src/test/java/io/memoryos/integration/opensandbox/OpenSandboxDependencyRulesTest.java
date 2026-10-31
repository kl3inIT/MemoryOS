package io.memoryos.integration.opensandbox;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class OpenSandboxDependencyRulesTest {
    private static final String PACKAGE = "io.memoryos.integration.opensandbox";

    @Test
    void usesOnlyTheSandboxContract() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(PACKAGE);

        noClasses()
                .that().resideInAPackage(PACKAGE + "..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.memoryos..application..",
                        "io.memoryos..persistence..",
                        "io.memoryos.ingestion..",
                        "io.memoryos.chat..",
                        "io.memoryos.integration.google..",
                        "io.memoryos.integration.lark..",
                        "io.memoryos.integration.microsoft..",
                        "io.memoryos.integration.zalo.."
                )
                .because("the sandbox runtime implements the sandbox capability's contract and nothing else (ADR 0027, ADR 0029)")
                .check(classes);
    }
}
