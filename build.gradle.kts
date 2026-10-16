plugins {
    base
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

group = "io.memoryos"
version = "0.1.0-SNAPSHOT"

tasks.named("clean") {
    dependsOn(subprojects.map { "${it.path}:clean" })
}

tasks.named("check") {
    dependsOn(subprojects.map { "${it.path}:check" })
}
