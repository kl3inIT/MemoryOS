plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.actuator.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.security.test)
    testRuntimeOnly(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}

springBoot {
    mainClass = "io.memoryos.api.MemoryOsApiApplication"
}
