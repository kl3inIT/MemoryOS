plugins {
    `java-library`
}

dependencies {
    api(platform(libs.spring.modulith.bom))
    api(libs.spring.modulith.api)
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.starter.jdbc)

    testImplementation(platform(libs.spring.modulith.bom))
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.h2)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}
