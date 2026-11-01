package io.memoryos.worker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * MEM-244: the Worker composes {@code core} from what each module publishes. It used to scan fifteen hand-picked
 * packages and list other modules' entity packages, so a module that gained a bean broke the Worker's context.
 */
class WorkerDependencyRulesTest {
    private final JavaClasses workerClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.memoryos.worker");

    @Test
    void theWorkerNamesNoImplementationOfAModule() {
        noClasses()
                .that().resideInAPackage("io.memoryos.worker..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.memoryos..application..",
                        "io.memoryos..persistence.."
                )
                .because("the Worker may depend only on what a module publishes")
                .check(workerClasses);
    }

    @Test
    void theWorkerScansOnlyItself() {
        var application = MemoryOsWorkerApplication.class.getAnnotation(SpringBootApplication.class);

        assertThat(application.scanBasePackages()).isEmpty();
        assertThat(application.scanBasePackageClasses()).isEmpty();
        assertThat(workerClasses).allSatisfy(type -> {
            assertThat(type.isAnnotatedWith(ComponentScan.class)).as(type.getName()).isFalse();
            assertThat(type.isAnnotatedWith(EntityScan.class)).as(type.getName()).isFalse();
            assertThat(type.isAnnotatedWith(EnableJpaRepositories.class)).as(type.getName()).isFalse();
        });
    }

    @Test
    void theWorkerImportsOfCoreOnlyWhatAModulePublishesForIt() {
        assertThat(workerClasses).allSatisfy(type -> {
            if (!type.isAnnotatedWith(Import.class)) return;
            assertThat(Arrays.stream(type.reflect().getAnnotation(Import.class).value())
                    .filter(imported -> imported.getPackageName().startsWith("io.memoryos.")
                            && !imported.getPackageName().startsWith("io.memoryos.worker")))
                    .as(type.getName())
                    .allSatisfy(imported -> assertThat(imported.getSimpleName()).endsWith("WorkerComponents"));
        });
    }
}
