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
            systemProperty("memoryos.object-storage.s3.service-endpoint", "http://127.0.0.1:1")
            systemProperty("memoryos.object-storage.s3.upload-endpoint", "http://127.0.0.1:1")
            systemProperty("memoryos.object-storage.s3.region", "us-east-1")
            systemProperty("memoryos.object-storage.s3.bucket", "memoryos-test")
            systemProperty("memoryos.object-storage.s3.access-key", "test-access-key")
            systemProperty("memoryos.object-storage.s3.secret-key", "test-secret-key")
            systemProperty("memoryos.object-storage.s3.path-style-access", "true")
            systemProperty("memoryos.object-storage.s3.readiness-key", "system/readiness")
            systemProperty("management.health.object-storage.enabled", "false")
        }
    }
}

tasks.named("clean") {
    dependsOn(":core:clean", ":connector:clean", ":api:clean", ":worker:clean")
}

tasks.named("check") {
    dependsOn(":core:check", ":connector:check", ":api:check", ":worker:check")
}
