import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test

plugins {
    base
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

group = "io.memoryos"
version = "0.1.0-SNAPSHOT"

subprojects {
    group = rootProject.group
    version = rootProject.version

    pluginManager.withPlugin("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(25)
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}

tasks.named("clean") {
    dependsOn(":core:clean", ":api:clean", ":worker:clean")
}

tasks.named("check") {
    dependsOn(":core:check", ":api:check", ":worker:check")
}
