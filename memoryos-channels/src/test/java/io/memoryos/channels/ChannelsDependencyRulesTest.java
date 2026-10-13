package io.memoryos.channels;

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

/** MEM-246: the channels process composes {@code core} from what the channel capability publishes, as the Worker does. */
class ChannelsDependencyRulesTest {
    private final JavaClasses channelsClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.memoryos.channels");

    @Test
    void theProcessNamesNoImplementationOfAModule() {
        noClasses()
                .that().resideInAPackage("io.memoryos.channels..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.memoryos..application..",
                        "io.memoryos..persistence.."
                )
                .because("the channels process may depend only on what a module publishes")
                .check(channelsClasses);
    }

    @Test
    void theProcessScansOnlyItself() {
        var application = MemoryOsChannelsApplication.class.getAnnotation(SpringBootApplication.class);

        assertThat(application.scanBasePackages()).isEmpty();
        assertThat(application.scanBasePackageClasses()).isEmpty();
        assertThat(channelsClasses).allSatisfy(type -> {
            assertThat(type.isAnnotatedWith(ComponentScan.class)).as(type.getName()).isFalse();
            assertThat(type.isAnnotatedWith(EntityScan.class)).as(type.getName()).isFalse();
            assertThat(type.isAnnotatedWith(EnableJpaRepositories.class)).as(type.getName()).isFalse();
        });
    }

    @Test
    void theProcessImportsOfCoreOnlyWhatTheChannelCapabilityPublishesForIt() {
        assertThat(channelsClasses).allSatisfy(type -> {
            if (!type.isAnnotatedWith(Import.class)) return;
            assertThat(Arrays.stream(type.reflect().getAnnotation(Import.class).value())
                    .filter(imported -> imported.getPackageName().startsWith("io.memoryos.")
                            && !imported.getPackageName().startsWith("io.memoryos.channels"))
                    .as(type.getName())
                    .allSatisfy(imported -> assertThat(imported.getSimpleName()).endsWith("RuntimeComponents"));
        });
    }
}
