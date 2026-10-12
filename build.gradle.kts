plugins {
    base
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

group = "io.memoryos"
version = "0.1.0-SNAPSHOT"

tasks.named("clean") {
    dependsOn(":memoryos-core:clean", ":sources:clean", ":memoryos-api:clean", ":memoryos-worker:clean")
}

tasks.named("check") {
    dependsOn(":memoryos-core:check", ":sources:check", ":memoryos-api:check", ":memoryos-worker:check")
}
