plugins {
    `java-library`
}

dependencies {
    api(platform(libs.spring.modulith.bom))
    api(libs.spring.modulith.api)
    implementation(platform(libs.spring.boot.dependencies))
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.spring.boot.starter.jdbc)
    compileOnly(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.core)
    implementation(libs.jackson.databind)
    implementation(libs.keycloak.admin.client)
    implementation(libs.aws.sdk.s3)
    implementation(libs.aws.sdk.auth)
    implementation(libs.aws.sdk.url.connection.client)

    testImplementation(platform(libs.spring.modulith.bom))
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.h2)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}
